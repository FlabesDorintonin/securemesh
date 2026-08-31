#include "VanguardRuntime.h"
#include "VanguardAirtime.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <random>
#include <vector>

namespace {

constexpr size_t NODE_COUNT = 5;
constexpr uint32_t A = 0xA;
constexpr uint32_t B = 0xB;
constexpr uint32_t C = 0xC;
constexpr uint32_t D = 0xD;
constexpr uint32_t E = 0xE;
constexpr uint32_t IDS[NODE_COUNT] = {A, B, C, D, E};
constexpr int CAPTURE_DB = 6;
constexpr uint64_t RADIO_TURNAROUND_US = 2200;
constexpr uint64_t TICK_STEP_US = 10000;
constexpr size_t HOP_WIRE_OVERHEAD_BYTES = 48; // outer header + hop authentication allowance

using Runtime = VanguardRuntime::State<>;
using Engine = Vanguard::Engine<16>;

struct Link {
  bool enabled = false;
  uint16_t deliveryPermille = 1000;
  int16_t rxPowerDbm = -75;
  VanguardRuntime::LinkMetric metric {1u << 16, 32000};
};

struct Node {
  uint32_t id = 0;
  Engine engine;
  Runtime runtime;
  VanguardManifest::Manifest<> manifest;
};

struct AirTx {
  uint64_t sequence = 0;
  uint32_t sender = 0;
  VanguardRuntime::TxControl control;
  uint64_t startUs = 0;
  uint64_t endUs = 0;
};

struct Delivery {
  uint64_t atUs = 0;
  uint32_t sender = 0;
  uint32_t receiver = 0;
  VanguardRuntime::TxControl control;
};

struct Stats {
  uint64_t controlTx = 0;
  uint64_t delivered = 0;
  uint64_t collisions = 0;
  uint64_t captureWins = 0;
  uint64_t halfDuplexDrops = 0;
  uint64_t probabilisticDrops = 0;
  uint64_t blockedUnicastDrops = 0;
  uint64_t routeReadyEvents = 0;
  uint64_t g2ReadyEvents = 0;
  uint64_t promotedG2Events = 0;
  uint64_t routeLostEvents = 0;
  uint64_t rejectedLoopEvents = 0;
  uint64_t rejectedEpochEvents = 0;
};

bool overlaps(const AirTx& a, const AirTx& b) {
  return a.startUs < b.endUs && b.startUs < a.endUs;
}

class VirtualRadioLab {
 public:
  explicit VirtualRadioLab(uint32_t seed = 0x51A9C0DEu) : rng_(seed) {
    VanguardAirtime::RadioProfile profile;
    const auto recommendation = VanguardAirtime::deriveRoutingTiming(
      112, 112, 60, VanguardRuntime::DEFAULT_DISCOVERY_HOPS, profile);
    assert(recommendation.valid);
    timing_.discoveryTimeoutMs = recommendation.discoveryTimeoutMs;
    timing_.rreqSettleMs = recommendation.rreqSettleMs;
    timing_.retryExtraStepMs = recommendation.retryExtraStepMs;
    timing_.refreshMinIntervalMs = recommendation.refreshMinIntervalMs;

    for (size_t i = 0; i < NODE_COUNT; ++i) {
      nodes_[i].id = IDS[i];
      assert(nodes_[i].manifest.configure(77, IDS, NODE_COUNT, IDS[i]));
      nodes_[i].engine.setIdentity(
        IDS[i], nodes_[i].manifest.slotFor(IDS[i]), 77, true);
      nodes_[i].runtime.reset(IDS[i], 1);
      nodes_[i].runtime.configureTiming(timing_);
    }
  }

  Node& node(uint32_t id) { return nodes_[indexOf(id)]; }
  const Node& node(uint32_t id) const { return nodes_[indexOf(id)]; }
  Stats stats() const { return stats_; }
  uint64_t nowUs() const { return nowUs_; }
  uint32_t nowMs() const { return static_cast<uint32_t>(nowUs_ / 1000u); }
  const VanguardRuntime::TimingConfig& timing() const { return timing_; }

  void setDirected(
    uint32_t from,
    uint32_t to,
    bool enabled,
    uint16_t deliveryPermille,
    int16_t powerDbm,
    VanguardRuntime::LinkMetric metric
  ) {
    Link& l = links_[indexOf(from)][indexOf(to)];
    l.enabled = enabled;
    l.deliveryPermille = deliveryPermille;
    l.rxPowerDbm = powerDbm;
    l.metric = metric;
  }

  void setBidirectional(
    uint32_t x,
    uint32_t y,
    uint16_t deliveryPermille,
    int16_t powerDbm,
    VanguardRuntime::LinkMetric metric
  ) {
    setDirected(x, y, true, deliveryPermille, powerDbm, metric);
    setDirected(y, x, true, deliveryPermille, powerDbm, metric);
  }

  void disableBidirectional(uint32_t x, uint32_t y) {
    links_[indexOf(x)][indexOf(y)].enabled = false;
    links_[indexOf(y)][indexOf(x)].enabled = false;
  }

  void submit(
    uint32_t sender,
    const VanguardRuntime::TxControl& control,
    uint64_t requestedStartUs,
    bool exactStart = false
  ) {
    if (!control.valid || control.length == 0) return;
    const size_t si = indexOf(sender);
    uint64_t start = std::max(requestedStartUs, nextTxUs_[si]);
    if (!exactStart) start += macServiceJitterUs(si, control);

    VanguardAirtime::RadioProfile profile;
    const uint32_t airtime = VanguardAirtime::estimateLoRaAirtimeUs(
      static_cast<size_t>(control.length) + HOP_WIRE_OVERHEAD_BYTES, profile);
    assert(airtime != UINT32_MAX && airtime != 0);

    AirTx tx;
    tx.sequence = ++sequence_;
    tx.sender = sender;
    tx.control = control;
    tx.startUs = start;
    tx.endUs = start + airtime;
    pending_.push_back(tx);
    nextTxUs_[si] = tx.endUs + RADIO_TURNAROUND_US;
    stats_.controlTx++;
  }

  bool beginDiscovery(uint32_t origin, uint32_t destination, bool exactStart = true) {
    Node& n = node(origin);
    VanguardRuntime::TxControl tx;
    VanguardRuntime::Event event;
    const bool ok = n.runtime.beginDiscovery(
      destination, false, 0, nowMs(), n.manifest, tx, &event, true);
    if (!ok) return false;
    recordEvent(event);
    submit(origin, tx, nowUs_, exactStart);
    return true;
  }

  void hardFail(uint32_t local, uint32_t failedHop) {
    Node& n = node(local);
    VanguardRuntime::TxControl out[12]{};
    VanguardRuntime::Event events[12]{};
    const size_t count = n.runtime.onLocalHopFailure(
      failedHop, nowMs(), n.manifest, n.engine,
      out, 12, events, 12);
    recordEvents(events, 12);
    for (size_t i = 0; i < count; ++i) submit(local, out[i], nowUs_ + 1000u);
  }

  // This is the intended firmware adapter contract for a Fresh->Stale edge.
  // The actual .ino integration is separately materialized/gated by the P1
  // passive-stale candidate workflow; the virtual lab deliberately reuses the
  // same Runtime failure API rather than reimplementing route failure logic.
  void passiveStaleEdge(uint32_t local, uint32_t staleHop) {
    hardFail(local, staleHop);
  }

  void runForMs(uint32_t durationMs) {
    runUntilMs(nowMs() + durationMs);
  }

  void runUntilMs(uint32_t targetMs) {
    const uint64_t targetUs = static_cast<uint64_t>(targetMs) * 1000u;
    while (nowUs_ < targetUs) {
      const uint64_t next = std::min(targetUs, nowUs_ + TICK_STEP_US);
      processCompletedClusters(next);
      nowUs_ = next;
      tickAll();
    }
    processCompletedClusters(targetUs);
  }

 private:
  size_t indexOf(uint32_t id) const {
    for (size_t i = 0; i < NODE_COUNT; ++i) if (nodes_[i].id == id) return i;
    std::fprintf(stderr, "unknown node 0x%08x\n", static_cast<unsigned>(id));
    std::abort();
  }

  uint64_t macServiceJitterUs(
    size_t senderIndex,
    const VanguardRuntime::TxControl& control
  ) const {
    // This is not claimed to be the production MAC. It is a deterministic
    // service-delay abstraction that prevents every relay/retry from becoming
    // unrealistically phase-locked in a single-threaded virtual medium.
    if (control.nextHop == VanguardRuntime::BROADCAST) {
      return static_cast<uint64_t>(senderIndex) * 430000u;
    }
    return static_cast<uint64_t>(senderIndex) * 20000u;
  }

  bool intendedFor(const AirTx& tx, uint32_t receiver) const {
    return tx.control.nextHop == VanguardRuntime::BROADCAST ||
           tx.control.nextHop == receiver;
  }

  bool interferesAt(const AirTx& tx, uint32_t receiver) const {
    if (tx.sender == receiver) return true;
    const Link& l = links_[indexOf(tx.sender)][indexOf(receiver)];
    return l.enabled;
  }

  bool deliveryProbabilityPass(uint16_t permille) {
    if (permille >= 1000) return true;
    if (permille == 0) return false;
    std::uniform_int_distribution<int> dist(1, 1000);
    return dist(rng_) <= static_cast<int>(permille);
  }

  void processCompletedClusters(uint64_t upToUs) {
    for (;;) {
      if (pending_.empty()) return;
      std::sort(pending_.begin(), pending_.end(), [](const AirTx& x, const AirTx& y) {
        if (x.startUs != y.startUs) return x.startUs < y.startUs;
        return x.sequence < y.sequence;
      });

      uint64_t clusterEnd = pending_[0].endUs;
      size_t count = 1;
      bool extended = true;
      while (extended) {
        extended = false;
        while (count < pending_.size() && pending_[count].startUs < clusterEnd) {
          clusterEnd = std::max(clusterEnd, pending_[count].endUs);
          ++count;
          extended = true;
        }
      }
      if (clusterEnd > upToUs) return;

      std::vector<AirTx> cluster(pending_.begin(), pending_.begin() + static_cast<long>(count));
      pending_.erase(pending_.begin(), pending_.begin() + static_cast<long>(count));
      processCluster(cluster);
    }
  }

  void processCluster(const std::vector<AirTx>& cluster) {
    std::vector<Delivery> deliveries;

    for (size_t ci = 0; ci < cluster.size(); ++ci) {
      const AirTx& candidate = cluster[ci];
      for (size_t ri = 0; ri < NODE_COUNT; ++ri) {
        const uint32_t receiver = nodes_[ri].id;
        if (receiver == candidate.sender || !intendedFor(candidate, receiver)) continue;

        const Link& wanted = links_[indexOf(candidate.sender)][ri];
        if (!wanted.enabled) {
          if (candidate.control.nextHop != VanguardRuntime::BROADCAST) {
            stats_.blockedUnicastDrops++;
          }
          continue;
        }

        bool halfDuplex = false;
        int strongestOther = std::numeric_limits<int>::min();
        bool hasInterferer = false;
        for (size_t oi = 0; oi < cluster.size(); ++oi) {
          if (oi == ci) continue;
          const AirTx& other = cluster[oi];
          if (!overlaps(candidate, other)) continue;
          if (other.sender == receiver) {
            halfDuplex = true;
            break;
          }
          if (!interferesAt(other, receiver)) continue;
          hasInterferer = true;
          const Link& interfering = links_[indexOf(other.sender)][ri];
          strongestOther = std::max(strongestOther, static_cast<int>(interfering.rxPowerDbm));
        }

        if (halfDuplex) {
          stats_.halfDuplexDrops++;
          continue;
        }
        if (hasInterferer && static_cast<int>(wanted.rxPowerDbm) < strongestOther + CAPTURE_DB) {
          stats_.collisions++;
          continue;
        }
        if (hasInterferer) stats_.captureWins++;
        if (!deliveryProbabilityPass(wanted.deliveryPermille)) {
          stats_.probabilisticDrops++;
          continue;
        }

        deliveries.push_back(Delivery{
          candidate.endUs, candidate.sender, receiver, candidate.control});
      }
    }

    std::sort(deliveries.begin(), deliveries.end(), [](const Delivery& x, const Delivery& y) {
      if (x.atUs != y.atUs) return x.atUs < y.atUs;
      if (x.receiver != y.receiver) return x.receiver < y.receiver;
      return x.sender < y.sender;
    });

    for (const Delivery& d : deliveries) receive(d);
  }

  void receive(const Delivery& d) {
    Node& receiver = node(d.receiver);
    const Link& l = links_[indexOf(d.sender)][indexOf(d.receiver)];
    VanguardRuntime::TxControl out[12]{};
    VanguardRuntime::Event events[12]{};
    const uint32_t atMs = static_cast<uint32_t>(d.atUs / 1000u);
    const size_t count = receiver.runtime.onControl(
      d.sender,
      d.control.hopLimit,
      d.control.payload,
      d.control.length,
      l.metric,
      atMs,
      receiver.manifest,
      receiver.engine,
      out,
      12,
      events,
      12);
    stats_.delivered++;
    recordEvents(events, 12);
    for (size_t i = 0; i < count; ++i) {
      submit(d.receiver, out[i], d.atUs + 1000u);
    }
  }

  void tickAll() {
    const uint32_t atMs = nowMs();
    for (Node& n : nodes_) {
      VanguardRuntime::TxControl out[12]{};
      VanguardRuntime::Event events[12]{};
      const size_t count = n.runtime.tick(atMs, n.manifest, out, 12, events, 12);
      recordEvents(events, 12);
      for (size_t i = 0; i < count; ++i) submit(n.id, out[i], nowUs_ + 1000u);
    }
  }

  void recordEvent(const VanguardRuntime::Event& e) {
    using ET = VanguardRuntime::EventType;
    switch (e.type) {
      case ET::RouteReady: stats_.routeReadyEvents++; break;
      case ET::G2Ready: stats_.g2ReadyEvents++; break;
      case ET::RoutePromotedG2: stats_.promotedG2Events++; break;
      case ET::RouteLost: stats_.routeLostEvents++; break;
      case ET::ControlRejectedLoop: stats_.rejectedLoopEvents++; break;
      case ET::ControlRejectedEpoch: stats_.rejectedEpochEvents++; break;
      default: break;
    }
  }

  void recordEvents(const VanguardRuntime::Event* events, size_t capacity) {
    for (size_t i = 0; i < capacity; ++i) recordEvent(events[i]);
  }

  std::array<Node, NODE_COUNT> nodes_{};
  Link links_[NODE_COUNT][NODE_COUNT]{};
  std::array<uint64_t, NODE_COUNT> nextTxUs_{};
  std::vector<AirTx> pending_;
  std::mt19937 rng_;
  VanguardRuntime::TimingConfig timing_{};
  Stats stats_{};
  uint64_t nowUs_ = 0;
  uint64_t sequence_ = 0;
};

VanguardRuntime::LinkMetric strongMetric() {
  return {1u << 16, 32200};
}
VanguardRuntime::LinkMetric mediumMetric() {
  return {2u << 16, 30000};
}

void configureDiamond(VirtualRadioLab& sim) {
  sim.setBidirectional(A, B, 1000, -67, strongMetric());
  sim.setBidirectional(B, D, 1000, -68, strongMetric());
  sim.setBidirectional(A, C, 1000, -74, mediumMetric());
  sim.setBidirectional(C, D, 1000, -75, mediumMetric());
}

void assertDiamondReady(const VirtualRadioLab& sim) {
  const auto* route = sim.node(A).engine.find(D);
  assert(route != nullptr);
  assert(route->primary.valid);
  assert(route->primary.nextHop == B);
  assert(route->backup.valid);
  assert(route->backup.nextHop == C);
  assert(sim.node(A).engine.hasExactG2(D));
  assert((route->primary.internalPathMask & route->backup.internalPathMask) == 0);
  assert(route->primary.pathTag != 0);
  assert(route->backup.pathTag != 0);
  assert(route->primary.pathTag != route->backup.pathTag);
}

Stats scenarioDiamondAndHardFail() {
  VirtualRadioLab sim(0x1001u);
  configureDiamond(sim);
  assert(sim.beginDiscovery(A, D));
  sim.runForMs(30000);
  assertDiamondReady(sim);

  sim.disableBidirectional(A, B);
  sim.hardFail(A, B);
  const auto* route = sim.node(A).engine.find(D);
  assert(route && route->primary.valid && route->primary.nextHop == C);
  bool fromBackup = false;
  uint32_t nextHop = 0;
  uint32_t pathTag = 0;
  assert(sim.node(A).engine.resolve(D, sim.nowMs() + 1, nextHop, fromBackup, &pathTag));
  assert(nextHop == C && fromBackup && pathTag != 0);
  const Stats s = sim.stats();
  assert(s.promotedG2Events >= 1);
  return s;
}

Stats scenarioPassiveStaleUsesSameFailureLadder() {
  VirtualRadioLab sim(0x1002u);
  configureDiamond(sim);
  assert(sim.beginDiscovery(A, D));
  sim.runForMs(30000);
  assertDiamondReady(sim);

  sim.disableBidirectional(A, B);
  sim.passiveStaleEdge(A, B);
  const auto* route = sim.node(A).engine.find(D);
  assert(route && route->primary.valid && route->primary.nextHop == C);
  const Stats s = sim.stats();
  assert(s.promotedG2Events >= 1);
  return s;
}

Stats scenarioDirectionalAsymmetryAndHeal() {
  VirtualRadioLab sim(0x1003u);
  sim.setDirected(A, B, true, 1000, -65, strongMetric());
  sim.setDirected(B, A, true, 0, -65, strongMetric());
  sim.setBidirectional(B, D, 1000, -66, strongMetric());

  assert(sim.beginDiscovery(A, D));
  sim.runForMs(45000);
  const auto* broken = sim.node(A).engine.find(D);
  assert(broken == nullptr || !broken->primary.valid);
  const Stats beforeHeal = sim.stats();
  assert(beforeHeal.probabilisticDrops > 0);

  sim.setDirected(B, A, true, 1000, -65, strongMetric());
  assert(sim.beginDiscovery(A, D));
  sim.runForMs(30000);
  const auto* healed = sim.node(A).engine.find(D);
  assert(healed && healed->primary.valid && healed->primary.nextHop == B);
  return sim.stats();
}

Stats scenarioSynchronizedDiscoveryCollisionRecovery() {
  VirtualRadioLab sim(0x1004u);
  sim.setBidirectional(A, B, 1000, -70, strongMetric());
  sim.setBidirectional(E, B, 1000, -70, strongMetric());
  sim.setBidirectional(B, D, 1000, -68, strongMetric());

  assert(sim.beginDiscovery(A, D, true));
  assert(sim.beginDiscovery(E, D, true));
  sim.runForMs(50000);
  const Stats s = sim.stats();
  assert(s.collisions >= 2);
  const auto* aRoute = sim.node(A).engine.find(D);
  const auto* eRoute = sim.node(E).engine.find(D);
  assert((aRoute && aRoute->primary.valid) || (eRoute && eRoute->primary.valid));
  return s;
}

Stats scenarioPartitionThenHeal() {
  VirtualRadioLab sim(0x1005u);
  sim.setBidirectional(A, B, 1000, -67, strongMetric());
  sim.setBidirectional(B, D, 1000, -68, strongMetric());
  sim.disableBidirectional(A, B);
  sim.disableBidirectional(B, D);

  assert(sim.beginDiscovery(A, D));
  sim.runForMs(50000);
  const auto* absent = sim.node(A).engine.find(D);
  assert(absent == nullptr || !absent->primary.valid);
  assert(sim.node(A).runtime.discoveryFor(D, false) == nullptr);

  sim.setBidirectional(A, B, 1000, -67, strongMetric());
  sim.setBidirectional(B, D, 1000, -68, strongMetric());
  assert(sim.beginDiscovery(A, D));
  sim.runForMs(30000);
  const auto* healed = sim.node(A).engine.find(D);
  assert(healed && healed->primary.valid && healed->primary.nextHop == B);
  return sim.stats();
}

void printScenario(const char* name, const Stats& s) {
  std::printf(
    "%s control_tx=%llu delivered=%llu collisions=%llu capture=%llu half_duplex=%llu "
    "prob_drop=%llu blocked_unicast=%llu route_ready=%llu g2_ready=%llu promoted_g2=%llu\n",
    name,
    static_cast<unsigned long long>(s.controlTx),
    static_cast<unsigned long long>(s.delivered),
    static_cast<unsigned long long>(s.collisions),
    static_cast<unsigned long long>(s.captureWins),
    static_cast<unsigned long long>(s.halfDuplexDrops),
    static_cast<unsigned long long>(s.probabilisticDrops),
    static_cast<unsigned long long>(s.blockedUnicastDrops),
    static_cast<unsigned long long>(s.routeReadyEvents),
    static_cast<unsigned long long>(s.g2ReadyEvents),
    static_cast<unsigned long long>(s.promotedG2Events));
}

} // namespace

int main() {
  const Stats diamond = scenarioDiamondAndHardFail();
  printScenario("diamond_hard_fail", diamond);

  const Stats stale = scenarioPassiveStaleUsesSameFailureLadder();
  printScenario("passive_stale_contract", stale);

  const Stats asym = scenarioDirectionalAsymmetryAndHeal();
  printScenario("directional_asymmetry_heal", asym);

  const Stats collision = scenarioSynchronizedDiscoveryCollisionRecovery();
  printScenario("collision_recovery", collision);

  const Stats partition = scenarioPartitionThenHeal();
  printScenario("partition_heal", partition);

  std::puts("VANGUARD virtual radio lab: PASS");
  std::puts("EVIDENCE_BOUNDARY=NATIVE_VIRTUAL_MEDIUM_NOT_HARDWARE");
  return 0;
}
