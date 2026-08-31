#define main vanguard_virtual_radio_lab_embedded_main
#include "vanguard_virtual_radio_lab.cpp"
#undef main

#include <cstdio>
#include <random>

namespace {

struct SweepRow {
  uint16_t deliveryPermille = 0;
  uint32_t trials = 0;
  uint32_t successes = 0;
  uint64_t controlTx = 0;
  uint64_t probabilityDrops = 0;
};

SweepRow runLossRow(uint16_t deliveryPermille, uint32_t trials) {
  SweepRow row;
  row.deliveryPermille = deliveryPermille;
  row.trials = trials;
  for (uint32_t i = 0; i < trials; ++i) {
    VirtualRadioLab sim(0xA5000000u ^ (static_cast<uint32_t>(deliveryPermille) << 8) ^ i);
    sim.setBidirectional(A, B, deliveryPermille, -68, strongMetric());
    sim.setBidirectional(B, D, deliveryPermille, -69, strongMetric());
    assert(sim.beginDiscovery(A, D));
    sim.runForMs(50000);
    const auto* route = sim.node(A).engine.find(D);
    if (route != nullptr && route->primary.valid) row.successes++;
    const Stats s = sim.stats();
    row.controlTx += s.controlTx;
    row.probabilityDrops += s.probabilisticDrops;
    assert(sim.node(A).runtime.discoveryFor(D, false) == nullptr);
  }
  return row;
}

void lossSweep() {
  constexpr uint16_t levels[] = {1000, 950, 900, 850, 800, 700, 600, 500};
  constexpr uint32_t trials = 250;
  for (uint16_t p : levels) {
    const SweepRow row = runLossRow(p, trials);
    const double successPct = 100.0 * static_cast<double>(row.successes) /
      static_cast<double>(row.trials);
    const double meanControl = static_cast<double>(row.controlTx) /
      static_cast<double>(row.trials);
    std::printf(
      "loss_sweep delivery_permille=%u trials=%u route_success=%u success_pct=%.2f "
      "mean_control_tx=%.2f probability_drops=%llu\n",
      static_cast<unsigned>(row.deliveryPermille),
      static_cast<unsigned>(row.trials),
      static_cast<unsigned>(row.successes),
      successPct,
      meanControl,
      static_cast<unsigned long long>(row.probabilityDrops));
  }
}

void contentionOffsetSweep() {
  constexpr uint32_t offsetsMs[] = {0, 50, 100, 200, 400, 800, 1200, 2000};
  for (uint32_t offsetMs : offsetsMs) {
    VirtualRadioLab sim(0xC0111D00u ^ offsetMs);
    sim.setBidirectional(A, B, 1000, -70, strongMetric());
    sim.setBidirectional(E, B, 1000, -70, strongMetric());
    sim.setBidirectional(B, D, 1000, -68, strongMetric());

    assert(sim.beginDiscovery(A, D, true));
    if (offsetMs != 0) sim.runForMs(offsetMs);
    assert(sim.beginDiscovery(E, D, true));
    sim.runForMs(50000);

    const auto* aRoute = sim.node(A).engine.find(D);
    const auto* eRoute = sim.node(E).engine.find(D);
    const bool aReady = aRoute != nullptr && aRoute->primary.valid;
    const bool eReady = eRoute != nullptr && eRoute->primary.valid;
    const Stats s = sim.stats();
    assert(sim.node(A).runtime.discoveryFor(D, false) == nullptr);
    assert(sim.node(E).runtime.discoveryFor(D, false) == nullptr);
    std::printf(
      "contention_offset offset_ms=%u a_route=%u e_route=%u collisions=%llu "
      "half_duplex=%llu control_tx=%llu\n",
      static_cast<unsigned>(offsetMs),
      aReady ? 1u : 0u,
      eReady ? 1u : 0u,
      static_cast<unsigned long long>(s.collisions),
      static_cast<unsigned long long>(s.halfDuplexDrops),
      static_cast<unsigned long long>(s.controlTx));
  }
}

void randomizedContentionWindowSweep() {
  constexpr uint32_t windowsMs[] = {0, 250, 500, 750, 1000, 1500, 2500};
  constexpr uint32_t trials = 250;
  for (uint32_t windowMs : windowsMs) {
    std::mt19937 rng(0xBACC0FFu ^ windowMs);
    std::uniform_int_distribution<uint32_t> offsetDist(0, windowMs);
    uint32_t neither = 0;
    uint32_t any = 0;
    uint32_t both = 0;
    uint64_t collisions = 0;
    uint64_t halfDuplex = 0;
    for (uint32_t i = 0; i < trials; ++i) {
      const uint32_t offsetMs = windowMs == 0 ? 0 : offsetDist(rng);
      VirtualRadioLab sim(0xCC000000u ^ (windowMs << 8) ^ i);
      sim.setBidirectional(A, B, 1000, -70, strongMetric());
      sim.setBidirectional(E, B, 1000, -70, strongMetric());
      sim.setBidirectional(B, D, 1000, -68, strongMetric());

      assert(sim.beginDiscovery(A, D, true));
      if (offsetMs != 0) sim.runForMs(offsetMs);
      assert(sim.beginDiscovery(E, D, true));
      sim.runForMs(50000);

      const auto* aRoute = sim.node(A).engine.find(D);
      const auto* eRoute = sim.node(E).engine.find(D);
      const bool aReady = aRoute != nullptr && aRoute->primary.valid;
      const bool eReady = eRoute != nullptr && eRoute->primary.valid;
      if (!aReady && !eReady) neither++;
      if (aReady || eReady) any++;
      if (aReady && eReady) both++;
      const Stats s = sim.stats();
      collisions += s.collisions;
      halfDuplex += s.halfDuplexDrops;
      assert(sim.node(A).runtime.discoveryFor(D, false) == nullptr);
      assert(sim.node(E).runtime.discoveryFor(D, false) == nullptr);
    }
    std::printf(
      "contention_window window_ms=%u trials=%u neither=%u any=%u both=%u "
      "any_pct=%.2f both_pct=%.2f mean_collisions=%.2f mean_half_duplex=%.2f\n",
      static_cast<unsigned>(windowMs),
      static_cast<unsigned>(trials),
      static_cast<unsigned>(neither),
      static_cast<unsigned>(any),
      static_cast<unsigned>(both),
      100.0 * static_cast<double>(any) / static_cast<double>(trials),
      100.0 * static_cast<double>(both) / static_cast<double>(trials),
      static_cast<double>(collisions) / static_cast<double>(trials),
      static_cast<double>(halfDuplex) / static_cast<double>(trials));
  }
}

} // namespace

int main() {
  lossSweep();
  contentionOffsetSweep();
  randomizedContentionWindowSweep();
  std::puts("VANGUARD virtual radio sweeps: PASS");
  std::puts("EVIDENCE_BOUNDARY=NATIVE_VIRTUAL_MEDIUM_NOT_HARDWARE");
  return 0;
}
