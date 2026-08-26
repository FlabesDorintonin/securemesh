#pragma once
#include <stdint.h>
#include <stddef.h>
#include "VanguardCore.h"
#include "VanguardProtocol.h"
#include "VanguardManifest.h"

namespace VanguardRuntime {

constexpr uint32_t BROADCAST = 0xFFFFFFFFu;
constexpr uint8_t DEFAULT_DISCOVERY_HOPS = 4;
constexpr uint32_t DISCOVERY_TIMEOUT_MS = 2200u;
constexpr uint32_t RREQ_CACHE_MS = 12000u;
constexpr uint32_t RERR_CACHE_MS = 12000u;
constexpr uint32_t REVERSE_CACHE_MS = 12000u;
constexpr uint32_t REFRESH_MIN_INTERVAL_MS = 3000u;
constexpr uint32_t RREQ_SETTLE_MS = 180u;
constexpr uint32_t FLOW_LABEL_MS = Vanguard::DEFAULT_ROUTE_EXPIRE_MS;
constexpr uint8_t MAX_DISCOVERY_ATTEMPTS = 3;
constexpr uint8_t CONTROL_PRIORITY = 1;

struct LinkMetric {
  uint32_t ecaQ16 = 1u << 16;
  uint16_t reliabilityQ15 = 24575; // conservative unknown-link prior ~= 75%
};

struct TimingConfig {
  // Portable defaults keep old unit tests deterministic.  The firmware should
  // overwrite these from the actual LoRa time-on-air profile at boot.
  uint32_t discoveryTimeoutMs = DISCOVERY_TIMEOUT_MS;
  uint32_t rreqSettleMs = RREQ_SETTLE_MS;
  uint32_t retryExtraStepMs = 600u;
  uint32_t refreshMinIntervalMs = REFRESH_MIN_INTERVAL_MS;
};

struct TxControl {
  bool valid = false;
  uint32_t nextHop = 0;
  bool requiresAck = false;
  uint8_t hopLimit = 0;
  uint8_t priority = CONTROL_PRIORITY;
  uint16_t length = 0;
  uint8_t payload[64] {};
};

enum class EventType : uint8_t {
  None = 0,
  DiscoveryStarted = 1,
  DiscoveryRetry = 2,
  DiscoveryFailed = 3,
  RouteReady = 4,
  G2Ready = 5,
  G2Unavailable = 6,
  RoutePromotedG2 = 7,
  RoutePromotedAlternate = 8,
  RouteLost = 9,
  ControlRejectedEpoch = 10,
  ControlRejectedLoop = 11,
  ControlRejectedInfeasible = 12
};

struct Event {
  EventType type = EventType::None;
  uint32_t destination = 0;
  uint32_t nextHop = 0;
  uint32_t requestId = 0;
  uint32_t routeVersion = 0;
};

template <
  size_t MAX_DISC = 8,
  size_t MAX_REVERSE = 24,
  size_t MAX_RREQ = 32,
  size_t MAX_RERR = 24,
  size_t MAX_FLOW_LABELS = 32,
  size_t MAX_PRECURSORS = 16>
class State {
 public:
  struct Discovery {
    bool used = false;
    uint32_t destination = 0;
    uint32_t requestId = 0;
    uint32_t avoidMask = 0;
    uint32_t deadlineAtMs = 0;
    uint8_t attempts = 0;
    uint8_t flags = 0;
    uint32_t excludedFirstHop = 0;
  };

  struct ReversePath {
    bool used = false;
    uint32_t origin = 0;
    uint32_t originBoot = 0;
    uint32_t requestId = 0;
    uint32_t previousHop = 0;
    uint8_t hopCount = 0;
    uint32_t expiresAtMs = 0;
  };

  struct SeenRreq {
    bool used = false;
    uint32_t origin = 0;
    uint32_t originBoot = 0;
    uint32_t requestId = 0;
    uint8_t bestHopCount = 0xFF;
    uint32_t bestEcaQ16 = 0xFFFFFFFFu;
    uint16_t bestReliabilityQ15 = 0;
    uint32_t expiresAtMs = 0;
  };

  struct PendingDestinationReply {
    bool used = false;
    VanguardProto::RouteRequest request {};
    uint32_t previousHop = 0;
    uint8_t replyHopLimit = DEFAULT_DISCOVERY_HOPS;
    uint32_t dueAtMs = 0;
  };

  struct SeenRerr {
    bool used = false;
    uint32_t reporter = 0;
    uint32_t eventId = 0;
    uint32_t expiresAtMs = 0;
  };

  struct PrecursorSet {
    bool used = false;
    uint32_t destination = 0;
    uint32_t nodes[4] {};
    uint8_t count = 0;
  };

  struct FlowLabel {
    bool used = false;
    uint32_t origin = 0;
    uint32_t originBoot = 0;
    uint32_t destination = 0;
    uint32_t pathTag = 0;
    uint32_t upstream = 0;
    uint32_t nextHop = 0;
    uint32_t expiresAtMs = 0;
  };

  void reset(uint32_t localNode, uint32_t bootEpoch) {
    localNode_ = localNode;
    bootEpoch_ = bootEpoch;
    routeSeq_ = 1;
    nextRequestId_ = (localNode ^ bootEpoch) | 1u;
    nextFailureEventId_ = nextRequestId_ ^ 0xA5A55A5Au;
    lastRefreshAtMs_ = 0;
    for (auto& x : disc_) x = Discovery{};
    for (auto& x : reverse_) x = ReversePath{};
    for (auto& x : rreq_) x = SeenRreq{};
    for (auto& x : pendingReply_) x = PendingDestinationReply{};
    for (auto& x : rerr_) x = SeenRerr{};
    for (auto& x : flow_) x = FlowLabel{};
    for (auto& x : prec_) x = PrecursorSet{};
  }

  uint32_t routeSeq() const { return routeSeq_; }

  void configureTiming(const TimingConfig& requested) {
    timing_.discoveryTimeoutMs = requested.discoveryTimeoutMs < 1000u
      ? 1000u : requested.discoveryTimeoutMs;
    timing_.rreqSettleMs = requested.rreqSettleMs < 50u
      ? 50u : requested.rreqSettleMs;
    timing_.retryExtraStepMs = requested.retryExtraStepMs;
    timing_.refreshMinIntervalMs = requested.refreshMinIntervalMs < 1000u
      ? 1000u : requested.refreshMinIntervalMs;
  }

  const TimingConfig& timing() const { return timing_; }

  const Discovery* discoveryFor(uint32_t destination, bool g2) const {
    for (const auto& x : disc_) {
      if (x.used && x.destination == destination &&
          (((x.flags & VanguardProto::FLAG_G2_PROBE) != 0) == g2)) {
        return &x;
      }
    }
    return nullptr;
  }

  bool beginDiscovery(
    uint32_t destination,
    bool g2,
    uint32_t avoidMask,
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    TxControl& out,
    Event* event = nullptr,
    bool forceFresh = false,
    uint32_t excludedFirstHop = 0
  ) {
    if (destination == 0 || destination == BROADCAST || destination == localNode_) {
      return false;
    }

    Discovery* d = findDiscovery(destination, g2);
    if (d == nullptr) d = allocDiscovery();
    if (d == nullptr) return false;

    *d = Discovery{};
    d->used = true;
    d->destination = destination;
    d->requestId = nextNonzero(nextRequestId_);
    d->avoidMask = avoidMask;
    d->attempts = 1;
    d->flags = (g2 ? VanguardProto::FLAG_G2_PROBE : 0) |
               (forceFresh ? VanguardProto::FLAG_FORCE_FRESH_GENERATION : 0);
    d->excludedFirstHop = excludedFirstHop;
    d->deadlineAtMs = now + timing_.discoveryTimeoutMs;
    makeRreq(*d, manifest, out);

    if (event != nullptr) {
      *event = Event{EventType::DiscoveryStarted, destination, 0, d->requestId, 0};
    }
    return true;
  }

  size_t tick(
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    TxControl* out,
    size_t capacity,
    Event* events,
    size_t eventCapacity
  ) {
    expireCaches(now);
    size_t outputCount = 0;
    size_t eventCount = 0;

    // Destination replies are intentionally delayed for a short bounded settle
    // window. During that window a better duplicate RREQ may replace the first
    // arrival. Exactly one RREP/pathTag is then emitted for this requestId, so
    // the path label can never ambiguously identify two different paths.
    for (auto& pending : pendingReply_) {
      if (!pending.used || !reached(now, pending.dueAtMs)) continue;
      if (outputCount >= capacity) break;
      if (makeDestinationReply(pending, now, manifest, out[outputCount])) {
        ++outputCount;
      }
      pending = PendingDestinationReply{};
    }

    for (auto& d : disc_) {
      if (!d.used || !reached(now, d.deadlineAtMs)) continue;

      if (d.attempts >= MAX_DISCOVERY_ATTEMPTS) {
        if (eventCount < eventCapacity) {
          const EventType type = (d.flags & VanguardProto::FLAG_G2_PROBE)
            ? EventType::G2Unavailable : EventType::DiscoveryFailed;
          events[eventCount++] = Event{type, d.destination, 0, d.requestId, 0};
        }
        d = Discovery{};
        continue;
      }

      // A full control-output batch must not consume a retry attempt that was
      // never actually emitted. Leave the deadline due and try next loop.
      if (outputCount >= capacity) continue;

      ++d.attempts;
      d.requestId = nextNonzero(nextRequestId_);
      if (d.attempts == MAX_DISCOVERY_ATTEMPTS &&
          !(d.flags & VanguardProto::FLAG_G2_PROBE)) {
        d.flags |= VanguardProto::FLAG_FORCE_FRESH_GENERATION;
      }
      d.deadlineAtMs = now + timing_.discoveryTimeoutMs +
        (d.attempts - 1u) * timing_.retryExtraStepMs;

      if (outputCount < capacity) {
        makeRreq(d, manifest, out[outputCount++]);
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            EventType::DiscoveryRetry, d.destination, 0, d.requestId, 0};
        }
      }
    }
    return outputCount;
  }

  void notePrecursor(uint32_t destination, uint32_t node) {
    if (destination == 0 || node == 0 || node == localNode_ || node == BROADCAST) {
      return;
    }

    PrecursorSet* p = nullptr;
    for (auto& x : prec_) {
      if (x.used && x.destination == destination) {
        p = &x;
        break;
      }
    }
    if (p == nullptr) {
      for (auto& x : prec_) {
        if (!x.used) {
          x = PrecursorSet{};
          x.used = true;
          x.destination = destination;
          p = &x;
          break;
        }
      }
    }
    if (p == nullptr) return;

    for (uint8_t i = 0; i < p->count; ++i) {
      if (p->nodes[i] == node) return;
    }
    if (p->count < 4) p->nodes[p->count++] = node;
  }

  bool resolveFlowLabel(
    uint32_t origin,
    uint32_t originBoot,
    uint32_t destination,
    uint32_t pathTag,
    uint32_t previousHop,
    uint32_t now,
    uint32_t& nextHop
  ) {
    if (origin == 0 || originBoot == 0 || destination == 0 || pathTag == 0 ||
        previousHop == 0) return false;
    expireCaches(now);
    FlowLabel* label = findFlowLabel(origin, originBoot, destination, pathTag);
    if (label == nullptr || label->upstream != previousHop || label->nextHop == 0) {
      return false;
    }
    label->expiresAtMs = now + FLOW_LABEL_MS;
    nextHop = label->nextHop;
    return true;
  }

  template <size_t R>
  bool makePathErrorToUpstream(
    uint32_t origin,
    uint32_t originBoot,
    uint32_t destination,
    uint32_t pathTag,
    uint32_t upstream,
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    const Vanguard::Engine<R>& engine,
    TxControl& out
  ) {
    if (origin == 0 || originBoot == 0 || destination == 0 || pathTag == 0 ||
        upstream == 0 || upstream == localNode_ || upstream == BROADCAST) {
      return false;
    }
    VanguardProto::RouteError error;
    error.flags = VanguardProto::FLAG_PATH_PINNED;
    error.failureEventId = nextNonzero(nextFailureEventId_);
    error.origin = origin;
    error.originBootEpoch = originBoot;
    error.destination = destination;
    error.pathTag = pathTag;
    error.reporter = localNode_;
    error.networkEpoch = manifest.valid ? manifest.networkEpoch : 0;
    error.manifestDigest = manifest.valid ? manifest.digest : 0;
    const auto* route = engine.find(destination);
    if (route != nullptr) {
      error.destinationBootEpoch = route->generation.bootEpoch;
      error.destinationRouteSeq = route->generation.routeSeq;
      error.routeVersion = route->version;
    }
    makeRerr(error, upstream, DEFAULT_DISCOVERY_HOPS, out);
    return out.valid;
  }

  template <size_t R>
  size_t onControl(
    uint32_t previousHop,
    uint8_t outerHopLimit,
    const uint8_t* payload,
    size_t length,
    const LinkMetric& link,
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    Vanguard::Engine<R>& engine,
    TxControl* out,
    size_t capacity,
    Event* events,
    size_t eventCapacity
  ) {
    if (payload == nullptr || length < 2) return 0;
    expireCaches(now);

    const auto type = VanguardProto::typeOf(payload, length);
    size_t outputCount = 0;
    size_t eventCount = 0;

    if (type == VanguardProto::ControlType::RouteRequest) {
      VanguardProto::RouteRequest request;
      if (!VanguardProto::decode(payload, length, request)) return 0;
      if (!scopeOk(request.networkEpoch, request.manifestDigest, manifest)) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            EventType::ControlRejectedEpoch,
            request.destination,
            previousHop,
            request.requestId,
            0};
        }
        return 0;
      }

      // The origin never forwards its own flooded request when a copy comes
      // back around a cycle. This closes a loop class that pathMask alone
      // cannot detect because endpoints are intentionally excluded from it.
      if (localNode_ == request.origin) return 0;

      const bool exact = manifest.valid &&
        manifest.sameNetwork(request.networkEpoch, request.manifestDigest);
      const uint32_t localBit = exact ? manifest.bitFor(localNode_) : 0;

      if (exact && !validateExactRreqPath(request, manifest)) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            EventType::ControlRejectedLoop,
            request.destination,
            previousHop,
            request.requestId,
            0};
        }
        return 0;
      }

      if (localBit != 0 && (request.pathMask & localBit) != 0) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            EventType::ControlRejectedLoop,
            request.destination,
            previousHop,
            request.requestId,
            0};
        }
        return 0;
      }

      if (localNode_ != request.destination &&
          localBit != 0 && (request.avoidMask & localBit) != 0) {
        return 0;
      }

      // A G2 probe must not simply rediscover the exact same first hop.
      // For a direct primary, the destination is also the primary first hop;
      // it ignores only the direct copy from the origin, but may still reply
      // when the request reaches it through a different relay.
      if (request.excludedFirstHop == localNode_ &&
          previousHop == request.origin) {
        return 0;
      }

      // Accumulate discovery evidence in the source->destination direction.
      // This evidence is used only to choose which RREQ survives the bounded
      // settle window; the RREP independently measures the return/suffix path.
      request.hopCount = request.hopCount == 0xFF
        ? 0xFF : static_cast<uint8_t>(request.hopCount + 1u);
      request.discoveryEcaQ16 = Vanguard::satAdd32(
        request.discoveryEcaQ16, link.ecaQ16);
      request.discoveryReliabilityQ15 = Vanguard::mulQ15(
        request.discoveryReliabilityQ15, link.reliabilityQ15);

      if (!acceptRreq(request, previousHop, now)) return 0;

      if (localNode_ == request.destination) {
        // Do not reply to the first radio race winner immediately. Hold one
        // bounded best candidate and emit exactly one RREP/pathTag in tick().
        (void)scheduleDestinationReply(
          request,
          previousHop,
          outerHopLimit ? outerHopLimit : DEFAULT_DISCOVERY_HOPS,
          now);
        return 0;
      }

      if (outerHopLimit <= 1) return 0;
      if (exact) {
        const uint8_t slot = manifest.slotFor(localNode_);
        if (slot >= 32 || request.pathSlotCount >= VanguardProto::MAX_PATH_SLOTS) {
          return 0;
        }
        request.pathMask |= localBit;
        request.pathSlots[request.pathSlotCount++] = slot;
      }
      if (outputCount < capacity) {
        makeRreqForward(
          request,
          static_cast<uint8_t>(outerHopLimit - 1u),
          out[outputCount++]);
      }
      return outputCount;
    }

    if (type == VanguardProto::ControlType::RouteReply) {
      VanguardProto::RouteReply reply;
      if (!VanguardProto::decode(payload, length, reply)) return 0;
      if (!scopeOk(reply.networkEpoch, reply.manifestDigest, manifest)) return 0;

      const bool exactReply = manifest.valid &&
        manifest.sameNetwork(reply.networkEpoch, reply.manifestDigest);
      uint32_t exactUpstream = 0;
      if (exactReply && !validateExactRrepPathForNode(
            reply, manifest, localNode_, previousHop, exactUpstream)) {
        return 0;
      }

      Vanguard::Candidate candidate;
      candidate.destination = reply.destination;
      candidate.nextHop = previousHop;
      candidate.generation = {
        reply.destinationBootEpoch, reply.destinationRouteSeq};
      candidate.advertisedGuardRank = reply.advertisedGuardRank;
      candidate.internalPathMask = reply.pathMask;
      candidate.exactMask = manifest.valid &&
        manifest.sameNetwork(reply.networkEpoch, reply.manifestDigest);
      candidate.ecaQ16 = Vanguard::satAdd32(link.ecaQ16, reply.ecaQ16);
      candidate.reliabilityQ15 = Vanguard::mulQ15(
        link.reliabilityQ15, reply.reliabilityQ15);
      candidate.hopCount = static_cast<uint8_t>(reply.hopCount + 1u);
      candidate.learnedAtMs = now;
      candidate.pathTag = reply.requestId;

      const bool g2 = (reply.flags & VanguardProto::FLAG_G2_PROBE) != 0;
      const bool sourceOfDiscovery = localNode_ == reply.origin;

      // G2 is a property of the source's pair of paths, not of every relay's
      // local route table. Intermediate relays therefore learn this suffix as
      // an ordinary feasible route while the source installs it as exact G2.
      const auto installResult = engine.install(
        candidate, now, g2 && sourceOfDiscovery);
      if (installResult == Vanguard::InstallResult::RejectedInfeasible) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            EventType::ControlRejectedInfeasible,
            reply.destination,
            previousHop,
            reply.requestId,
            0};
        }
        return 0;
      }
      if (installResult == Vanguard::InstallResult::RejectedInvalid ||
          installResult == Vanguard::InstallResult::RejectedOldGeneration ||
          installResult == Vanguard::InstallResult::RejectedLoop ||
          (sourceOfDiscovery && g2 &&
           installResult == Vanguard::InstallResult::RejectedSamePath)) {
        return 0;
      }

      const auto* route = engine.find(reply.destination);
      if (route == nullptr || !route->primary.valid) return 0;

      if (sourceOfDiscovery) {
        // A G2 discovery only completes if it actually produced exact
        // diversity. A same/overlapping reply is ignored and the discovery
        // remains live for another RREP or retry.
        if (g2 && !engine.hasExactG2(reply.destination)) return 0;

        completeDiscovery(reply.destination, g2);
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            g2 ? EventType::G2Ready : EventType::RouteReady,
            reply.destination,
            route->primary.nextHop,
            reply.requestId,
            route->version};
        }

        // Once a primary is established, immediately search for a second path.
        // excludedFirstHop is essential when the primary is direct (mask==0):
        // the destination ignores only the direct RREQ and can still answer a
        // copy that arrives through a different relay.
        if (!g2 && manifest.valid && !engine.hasExactG2(reply.destination) &&
            route->primary.exactMask && outputCount < capacity) {
          Event ignored;
          beginDiscovery(
            reply.destination,
            true,
            route->primary.internalPathMask,
            now,
            manifest,
            out[outputCount++],
            &ignored,
            false,
            route->primary.nextHop);
        }
        return outputCount;
      }

      uint32_t upstream = 0;
      if (exactReply) {
        // The ordered NodeSlot vector is authoritative for exact paths. This
        // makes flow-label installation immune to reverse-cache races.
        upstream = exactUpstream;
      } else {
        const ReversePath* reverse = findReverse(reply.origin, reply.requestId);
        if (reverse == nullptr || reverse->originBoot != reply.originBootEpoch) {
          return 0;
        }
        upstream = reverse->previousHop;
      }

      // Install a flow label for the exact path represented by this RREP. DATA
      // carrying pathTag=requestId will follow this suffix instead of being
      // silently redirected by a relay's unrelated destination route.
      if (!installFlowLabel(
            reply.origin,
            reply.originBootEpoch,
            reply.destination,
            reply.requestId,
            upstream,
            previousHop,
            now)) {
        return 0;
      }

      reply.hopCount = static_cast<uint8_t>(reply.hopCount + 1u);
      if (exactReply) {
        const uint32_t bit = manifest.bitFor(localNode_);
        if (bit == 0) return 0;
        reply.pathMask |= bit;
      }
      reply.ecaQ16 = candidate.ecaQ16;
      reply.reliabilityQ15 = candidate.reliabilityQ15;
      // Advertise the rank of this exact returned suffix, not whichever
      // unrelated route happens to be selected in the relay's route table.
      reply.advertisedGuardRank =
        Vanguard::saturatingPlusOne(candidate.advertisedGuardRank);

      if (outputCount < capacity) {
        makeRrep(
          reply,
          upstream,
          outerHopLimit > 1 ? static_cast<uint8_t>(outerHopLimit - 1u)
                            : DEFAULT_DISCOVERY_HOPS,
          out[outputCount++]);
      }

      return outputCount;
    }

    if (type == VanguardProto::ControlType::RouteError) {
      VanguardProto::RouteError error;
      if (!VanguardProto::decode(payload, length, error)) return 0;
      if (!scopeOk(error.networkEpoch, error.manifestDigest, manifest) ||
          !acceptRerr(error, now)) {
        return 0;
      }

      if (error.pathTag != 0) {
        // Path-pinned RERRs travel strictly upstream along the same flow-label
        // chain that was installed by the RREP. A relay's unrelated local
        // alternate must not hide failure of the source's exact path.
        if (localNode_ == error.origin) {
          bool promotedG2 = false;
          const bool survives = engine.onPathTagFailure(
            error.destination, error.pathTag, now, &promotedG2);
          const auto* after = engine.find(error.destination);

          if (survives && after != nullptr && after->primary.valid) {
            if (eventCount < eventCapacity) {
              events[eventCount++] = Event{
                promotedG2 ? EventType::RoutePromotedG2
                           : EventType::RoutePromotedAlternate,
                error.destination,
                after->primary.nextHop,
                error.failureEventId,
                after->version};
            }

            // Replenish redundancy after consuming a standby.
            if (manifest.valid && !engine.hasExactG2(error.destination) &&
                after->primary.exactMask && outputCount < capacity &&
                discoveryFor(error.destination, true) == nullptr) {
              Event ignored;
              beginDiscovery(
                error.destination,
                true,
                after->primary.internalPathMask,
                now,
                manifest,
                out[outputCount++],
                &ignored,
                false,
                after->primary.nextHop);
            }
            return outputCount;
          }

          if (eventCount < eventCapacity) {
            events[eventCount++] = Event{
              EventType::RouteLost,
              error.destination,
              0,
              error.failureEventId,
              after ? after->version : 0};
          }

          if (outputCount < capacity &&
              discoveryFor(error.destination, false) == nullptr) {
            Event ignored;
            beginDiscovery(
              error.destination,
              false,
              0,
              now,
              manifest,
              out[outputCount++],
              &ignored,
              false,
              0);
          }
          return outputCount;
        }

        FlowLabel* label = findFlowLabel(
          error.origin,
          error.originBootEpoch,
          error.destination,
          error.pathTag);
        if (label == nullptr || label->nextHop != previousHop ||
            label->upstream == 0 || label->upstream == localNode_) {
          return 0;
        }

        const uint32_t upstream = label->upstream;
        *label = FlowLabel{};
        (void)engine.onPathTagFailure(
          error.destination, error.pathTag, now, nullptr);

        if (outputCount < capacity) {
          error.hopCount = static_cast<uint8_t>(error.hopCount + 1u);
          makeRerr(
            error,
            upstream,
            outerHopLimit > 1 ? static_cast<uint8_t>(outerHopLimit - 1u) : 1,
            out[outputCount++]);
        }
        return outputCount;
      }

      // Compatibility/fallback RERR for non-labelled hop-by-hop traffic.
      const auto* before = engine.find(error.destination);
      if (before == nullptr || !before->primary.valid ||
          before->primary.nextHop != previousHop) {
        return 0;
      }

      bool promotedG2 = false;
      const bool survives = engine.onRouteFailure(
        error.destination, previousHop, now, &promotedG2);
      const auto* after = engine.find(error.destination);

      if (survives && after != nullptr && after->primary.valid) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            promotedG2 ? EventType::RoutePromotedG2
                       : EventType::RoutePromotedAlternate,
            error.destination,
            after->primary.nextHop,
            error.failureEventId,
            after->version};
        }
        return 0;
      }

      if (eventCount < eventCapacity) {
        events[eventCount++] = Event{
          EventType::RouteLost,
          error.destination,
          0,
          error.failureEventId,
          after ? after->version : 0};
      }
      outputCount += emitRerrToPrecursors(
        error.destination,
        error,
        outerHopLimit,
        out + outputCount,
        capacity - outputCount);
      return outputCount;
    }

    return 0;
  }

  template <size_t R>
  size_t onLocalHopFailure(
    uint32_t failedHop,
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    Vanguard::Engine<R>& engine,
    TxControl* out,
    size_t capacity,
    Event* events,
    size_t eventCapacity
  ) {
    size_t outputCount = 0;
    size_t eventCount = 0;

    // First invalidate every source-pinned flow that actually traversed the
    // failed physical neighbour. Each source receives a path-specific RERR;
    // local alternate routing at this relay must not hide that exact-path
    // failure from the source.
    for (auto& label : flow_) {
      if (!label.used || label.nextHop != failedHop) continue;

      if (outputCount < capacity && label.upstream != 0 &&
          label.upstream != localNode_ && label.upstream != BROADCAST) {
        VanguardProto::RouteError error;
        error.flags = VanguardProto::FLAG_PATH_PINNED;
        error.failureEventId = nextNonzero(nextFailureEventId_);
        error.origin = label.origin;
        error.originBootEpoch = label.originBoot;
        error.destination = label.destination;
        error.pathTag = label.pathTag;
        error.reporter = localNode_;
        error.networkEpoch = manifest.valid ? manifest.networkEpoch : 0;
        error.manifestDigest = manifest.valid ? manifest.digest : 0;
        const auto* route = engine.find(label.destination);
        if (route != nullptr) {
          error.destinationBootEpoch = route->generation.bootEpoch;
          error.destinationRouteSeq = route->generation.routeSeq;
          error.routeVersion = route->version;
        }
        makeRerr(error, label.upstream, DEFAULT_DISCOVERY_HOPS,
                 out[outputCount++]);
      }
      label = FlowLabel{};
    }

    uint32_t destinations[R] {};
    const size_t destinationCount = engine.destinationsUsingNextHop(
      failedHop, destinations, R);

    for (size_t i = 0; i < destinationCount && i < R; ++i) {
      const uint32_t destination = destinations[i];
      const auto* before = engine.find(destination);

      VanguardProto::RouteError genericError;
      genericError.failureEventId = nextNonzero(nextFailureEventId_);
      genericError.origin = localNode_;
      genericError.originBootEpoch = bootEpoch_;
      genericError.destination = destination;
      genericError.pathTag = 0;
      genericError.reporter = localNode_;
      genericError.networkEpoch = manifest.valid ? manifest.networkEpoch : 0;
      genericError.manifestDigest = manifest.valid ? manifest.digest : 0;
      if (before != nullptr) {
        genericError.destinationBootEpoch = before->generation.bootEpoch;
        genericError.destinationRouteSeq = before->generation.routeSeq;
        genericError.routeVersion = before->version;
      }

      bool promotedG2 = false;
      const bool survives = engine.onRouteFailure(
        destination, failedHop, now, &promotedG2);
      const auto* after = engine.find(destination);

      if (survives && after != nullptr && after->primary.valid) {
        if (eventCount < eventCapacity) {
          events[eventCount++] = Event{
            promotedG2 ? EventType::RoutePromotedG2
                       : EventType::RoutePromotedAlternate,
            destination,
            after->primary.nextHop,
            genericError.failureEventId,
            after->version};
        }

        // If this is our own source route, immediately replenish exact G2.
        if (manifest.valid && !engine.hasExactG2(destination) &&
            after->primary.exactMask && outputCount < capacity &&
            discoveryFor(destination, true) == nullptr) {
          Event ignored;
          beginDiscovery(
            destination,
            true,
            after->primary.internalPathMask,
            now,
            manifest,
            out[outputCount++],
            &ignored,
            false,
            after->primary.nextHop);
        }
        continue;
      }

      if (eventCount < eventCapacity) {
        events[eventCount++] = Event{
          EventType::RouteLost,
          destination,
          0,
          genericError.failureEventId,
          after ? after->version : 0};
      }

      // Only unlabeled hop-by-hop users are present in precursor sets. Exact
      // path users were already notified through flow-label RERRs above.
      outputCount += emitRerrToPrecursors(
        destination,
        genericError,
        DEFAULT_DISCOVERY_HOPS,
        out + outputCount,
        capacity - outputCount);

      if (outputCount < capacity &&
          discoveryFor(destination, false) == nullptr) {
        Event ignored;
        beginDiscovery(
          destination,
          false,
          0,
          now,
          manifest,
          out[outputCount++],
          &ignored,
          false,
          0);
      }
      if (outputCount >= capacity) break;
    }
    return outputCount;
  }

 private:
  uint32_t localNode_ = 0;
  uint32_t bootEpoch_ = 0;
  uint32_t routeSeq_ = 1;
  TimingConfig timing_ {};
  uint32_t nextRequestId_ = 1;
  uint32_t nextFailureEventId_ = 1;
  uint32_t lastRefreshAtMs_ = 0;
  Discovery disc_[MAX_DISC] {};
  ReversePath reverse_[MAX_REVERSE] {};
  SeenRreq rreq_[MAX_RREQ] {};
  PendingDestinationReply pendingReply_[MAX_DISC] {};
  SeenRerr rerr_[MAX_RERR] {};
  FlowLabel flow_[MAX_FLOW_LABELS] {};
  PrecursorSet prec_[MAX_PRECURSORS] {};

  static bool reached(uint32_t now, uint32_t target) {
    return static_cast<int32_t>(now - target) >= 0;
  }

  static uint32_t nextNonzero(uint32_t& value) {
    uint32_t result = ++value;
    if (result == 0) result = ++value;
    return result;
  }

  bool scopeOk(
    uint32_t epoch,
    uint32_t digest,
    const VanguardManifest::Manifest<>& manifest
  ) const {
    return manifest.valid
      ? manifest.sameNetwork(epoch, digest)
      : (epoch == 0 && digest == 0);
  }

  Discovery* findDiscovery(uint32_t destination, bool g2) {
    for (auto& x : disc_) {
      if (x.used && x.destination == destination &&
          (((x.flags & VanguardProto::FLAG_G2_PROBE) != 0) == g2)) {
        return &x;
      }
    }
    return nullptr;
  }

  Discovery* allocDiscovery() {
    for (auto& x : disc_) if (!x.used) return &x;
    return nullptr;
  }

  void completeDiscovery(uint32_t destination, bool g2) {
    Discovery* d = findDiscovery(destination, g2);
    if (d != nullptr) *d = Discovery{};
  }

  void makeRreq(
    const Discovery& d,
    const VanguardManifest::Manifest<>& manifest,
    TxControl& out
  ) {
    VanguardProto::RouteRequest request;
    request.flags = d.flags;
    request.requestId = d.requestId;
    request.origin = localNode_;
    request.destination = d.destination;
    request.networkEpoch = manifest.valid ? manifest.networkEpoch : 0;
    request.manifestDigest = manifest.valid ? manifest.digest : 0;
    request.originBootEpoch = bootEpoch_;
    request.avoidMask = d.avoidMask;
    request.pathMask = 0;
    request.excludedFirstHop = d.excludedFirstHop;
    request.pathSlotCount = 0;
    request.discoveryEcaQ16 = 0;
    request.discoveryReliabilityQ15 = Vanguard::RELIABILITY_ONE_Q15;

    out = TxControl{};
    out.valid = true;
    out.nextHop = BROADCAST;
    out.requiresAck = false;
    out.hopLimit = DEFAULT_DISCOVERY_HOPS;
    out.length = static_cast<uint16_t>(VanguardProto::encode(
      request, out.payload, sizeof(out.payload)));
  }

  static void makeRreqForward(
    const VanguardProto::RouteRequest& request,
    uint8_t hopLimit,
    TxControl& out
  ) {
    out = TxControl{};
    out.valid = true;
    out.nextHop = BROADCAST;
    out.hopLimit = hopLimit;
    out.length = static_cast<uint16_t>(VanguardProto::encode(
      request, out.payload, sizeof(out.payload)));
  }

  static void makeRrep(
    const VanguardProto::RouteReply& reply,
    uint32_t nextHop,
    uint8_t hopLimit,
    TxControl& out
  ) {
    out = TxControl{};
    out.valid = true;
    out.nextHop = nextHop;
    out.requiresAck = true;
    out.hopLimit = hopLimit ? hopLimit : 1;
    out.length = static_cast<uint16_t>(VanguardProto::encode(
      reply, out.payload, sizeof(out.payload)));
  }

  static bool rreqEvidenceBetter(
    const VanguardProto::RouteRequest& request,
    uint16_t bestReliabilityQ15,
    uint32_t bestEcaQ16,
    uint8_t bestHopCount
  ) {
    // No opaque weighted score: reliability has a small hysteresis band; inside
    // that band expected channel airtime wins, then hop count. This prevents
    // tiny noisy PDR changes from flapping discovery while still preferring a
    // materially more reliable path.
    constexpr uint16_t REL_HYST_Q15 = 164u; // about 0.5 percentage point
    const int32_t delta = static_cast<int32_t>(request.discoveryReliabilityQ15) -
                          static_cast<int32_t>(bestReliabilityQ15);
    if (delta > static_cast<int32_t>(REL_HYST_Q15)) return true;
    if (delta < -static_cast<int32_t>(REL_HYST_Q15)) return false;
    if (request.discoveryEcaQ16 != bestEcaQ16) {
      return request.discoveryEcaQ16 < bestEcaQ16;
    }
    return request.hopCount < bestHopCount;
  }

  static bool validateExactRreqPath(
    const VanguardProto::RouteRequest& request,
    const VanguardManifest::Manifest<>& manifest
  ) {
    if (!manifest.valid || request.pathSlotCount > VanguardProto::MAX_PATH_SLOTS) {
      return false;
    }
    uint32_t mask = 0;
    for (uint8_t i = 0; i < request.pathSlotCount; ++i) {
      const uint8_t slot = request.pathSlots[i];
      if (slot >= manifest.count) return false;
      const uint32_t bit = 1u << slot;
      if (mask & bit) return false;
      const uint32_t node = manifest.nodeFor(slot);
      if (node == 0 || node == request.origin || node == request.destination) {
        return false;
      }
      mask |= bit;
    }
    return mask == request.pathMask;
  }

  static bool validateExactRrepPathForNode(
    const VanguardProto::RouteReply& reply,
    const VanguardManifest::Manifest<>& manifest,
    uint32_t localNode,
    uint32_t previousHop,
    uint32_t& upstream
  ) {
    upstream = 0;
    if (!manifest.valid || reply.pathSlotCount > VanguardProto::MAX_PATH_SLOTS) {
      return false;
    }
    uint32_t fullMask = 0;
    int localIndex = -1;
    for (uint8_t i = 0; i < reply.pathSlotCount; ++i) {
      const uint8_t slot = reply.pathSlots[i];
      if (slot >= manifest.count) return false;
      const uint32_t bit = 1u << slot;
      if (fullMask & bit) return false;
      const uint32_t node = manifest.nodeFor(slot);
      if (node == 0 || node == reply.origin || node == reply.destination) return false;
      if (node == localNode) localIndex = static_cast<int>(i);
      fullMask |= bit;
    }

    if (localNode == reply.origin) {
      const uint32_t expectedDownstream = reply.pathSlotCount > 0
        ? manifest.nodeFor(reply.pathSlots[0]) : reply.destination;
      return previousHop == expectedDownstream && reply.pathMask == fullMask;
    }

    if (localIndex < 0) return false;
    const uint32_t expectedDownstream =
      (static_cast<uint8_t>(localIndex + 1) < reply.pathSlotCount)
        ? manifest.nodeFor(reply.pathSlots[localIndex + 1])
        : reply.destination;
    if (previousHop != expectedDownstream) return false;

    uint32_t expectedSuffixMask = 0;
    for (uint8_t i = static_cast<uint8_t>(localIndex + 1);
         i < reply.pathSlotCount; ++i) {
      expectedSuffixMask |= 1u << reply.pathSlots[i];
    }
    if (reply.pathMask != expectedSuffixMask) return false;

    upstream = localIndex > 0
      ? manifest.nodeFor(reply.pathSlots[localIndex - 1])
      : reply.origin;
    return upstream != 0 && upstream != localNode && upstream != previousHop;
  }

  bool scheduleDestinationReply(
    const VanguardProto::RouteRequest& request,
    uint32_t previousHop,
    uint8_t replyHopLimit,
    uint32_t now
  ) {
    PendingDestinationReply* pending = nullptr;
    for (auto& item : pendingReply_) {
      if (item.used && item.request.origin == request.origin &&
          item.request.originBootEpoch == request.originBootEpoch &&
          item.request.requestId == request.requestId) {
        pending = &item;
        break;
      }
    }
    if (pending == nullptr) {
      for (auto& item : pendingReply_) {
        if (!item.used || reached(now, item.dueAtMs + RREQ_CACHE_MS)) {
          pending = &item;
          break;
        }
      }
      if (pending == nullptr) return false;
      *pending = PendingDestinationReply{};
      pending->used = true;
      pending->request = request;
      pending->previousHop = previousHop;
      pending->replyHopLimit = replyHopLimit;
      pending->dueAtMs = now + timing_.rreqSettleMs;
      return true;
    }

    if (rreqEvidenceBetter(
          request,
          pending->request.discoveryReliabilityQ15,
          pending->request.discoveryEcaQ16,
          pending->request.hopCount)) {
      // Keep the original dueAtMs: the settle window is bounded and cannot be
      // extended indefinitely by a stream of small improvements.
      const uint32_t due = pending->dueAtMs;
      pending->request = request;
      pending->previousHop = previousHop;
      pending->replyHopLimit = replyHopLimit;
      pending->dueAtMs = due;
    }
    return true;
  }

  bool makeDestinationReply(
    const PendingDestinationReply& pending,
    uint32_t now,
    const VanguardManifest::Manifest<>& manifest,
    TxControl& out
  ) {
    const auto& request = pending.request;
    if ((request.flags & VanguardProto::FLAG_FORCE_FRESH_GENERATION) &&
        reached(now, lastRefreshAtMs_ + timing_.refreshMinIntervalMs)) {
      routeSeq_ = nextNonzero(routeSeq_);
      lastRefreshAtMs_ = now;
    }

    VanguardProto::RouteReply reply;
    reply.flags = static_cast<uint8_t>(request.flags | VanguardProto::FLAG_PATH_PINNED);
    reply.requestId = request.requestId;
    reply.origin = request.origin;
    reply.destination = request.destination;
    reply.networkEpoch = request.networkEpoch;
    reply.manifestDigest = request.manifestDigest;
    reply.originBootEpoch = request.originBootEpoch;
    reply.destinationBootEpoch = bootEpoch_;
    reply.destinationRouteSeq = routeSeq_;
    reply.pathMask = 0; // suffix mask is built as RREP travels toward source
    reply.ecaQ16 = 0;
    reply.reliabilityQ15 = Vanguard::RELIABILITY_ONE_Q15;
    reply.advertisedGuardRank = 0;
    reply.pathSlotCount = request.pathSlotCount;
    for (uint8_t i = 0; i < VanguardProto::MAX_PATH_SLOTS; ++i) {
      reply.pathSlots[i] = request.pathSlots[i];
    }
    makeRrep(reply, pending.previousHop, pending.replyHopLimit, out);
    return out.valid && out.length != 0 && scopeOk(
      request.networkEpoch, request.manifestDigest, manifest);
  }

  bool acceptRreq(
    const VanguardProto::RouteRequest& request,
    uint32_t previousHop,
    uint32_t now
  ) {
    SeenRreq* seen = nullptr;
    for (auto& x : rreq_) {
      if (x.used && x.origin == request.origin &&
          x.originBoot == request.originBootEpoch &&
          x.requestId == request.requestId) {
        seen = &x;
        break;
      }
    }
    if (seen != nullptr && !rreqEvidenceBetter(
          request,
          seen->bestReliabilityQ15,
          seen->bestEcaQ16,
          seen->bestHopCount)) {
      return false;
    }

    if (seen == nullptr) {
      for (auto& x : rreq_) {
        if (!x.used || reached(now, x.expiresAtMs)) {
          seen = &x;
          break;
        }
      }
      if (seen == nullptr) return false;
      *seen = SeenRreq{};
      seen->used = true;
      seen->origin = request.origin;
      seen->originBoot = request.originBootEpoch;
      seen->requestId = request.requestId;
    }
    seen->bestHopCount = request.hopCount;
    seen->bestEcaQ16 = request.discoveryEcaQ16;
    seen->bestReliabilityQ15 = request.discoveryReliabilityQ15;
    seen->expiresAtMs = now + RREQ_CACHE_MS;

    ReversePath* reverse = nullptr;
    for (auto& x : reverse_) {
      if (x.used && x.origin == request.origin &&
          x.originBoot == request.originBootEpoch &&
          x.requestId == request.requestId) {
        reverse = &x;
        break;
      }
    }
    if (reverse == nullptr) {
      for (auto& x : reverse_) {
        if (!x.used || reached(now, x.expiresAtMs)) {
          reverse = &x;
          break;
        }
      }
    }
    if (reverse != nullptr) {
      *reverse = ReversePath{};
      reverse->used = true;
      reverse->origin = request.origin;
      reverse->originBoot = request.originBootEpoch;
      reverse->requestId = request.requestId;
      reverse->previousHop = previousHop;
      reverse->hopCount = request.hopCount;
      reverse->expiresAtMs = now + REVERSE_CACHE_MS;
    }
    return true;
  }

  const ReversePath* findReverse(uint32_t origin, uint32_t requestId) const {
    for (const auto& x : reverse_) {
      if (x.used && x.origin == origin && x.requestId == requestId) return &x;
    }
    return nullptr;
  }

  FlowLabel* findFlowLabel(
    uint32_t origin,
    uint32_t originBoot,
    uint32_t destination,
    uint32_t pathTag
  ) {
    for (auto& label : flow_) {
      if (label.used && label.origin == origin &&
          label.originBoot == originBoot &&
          label.destination == destination && label.pathTag == pathTag) {
        return &label;
      }
    }
    return nullptr;
  }

  bool installFlowLabel(
    uint32_t origin,
    uint32_t originBoot,
    uint32_t destination,
    uint32_t pathTag,
    uint32_t upstream,
    uint32_t nextHop,
    uint32_t now
  ) {
    if (origin == 0 || originBoot == 0 || destination == 0 || pathTag == 0 ||
        upstream == 0 || nextHop == 0 || upstream == nextHop ||
        nextHop == localNode_) {
      return false;
    }

    FlowLabel* label = findFlowLabel(origin, originBoot, destination, pathTag);
    if (label == nullptr) {
      for (auto& item : flow_) {
        if (!item.used || reached(now, item.expiresAtMs)) {
          label = &item;
          break;
        }
      }
    }
    if (label == nullptr) return false;

    *label = FlowLabel{};
    label->used = true;
    label->origin = origin;
    label->originBoot = originBoot;
    label->destination = destination;
    label->pathTag = pathTag;
    label->upstream = upstream;
    label->nextHop = nextHop;
    label->expiresAtMs = now + FLOW_LABEL_MS;
    return true;
  }

  static void makeRerr(
    const VanguardProto::RouteError& error,
    uint32_t nextHop,
    uint8_t hopLimit,
    TxControl& out
  ) {
    out = TxControl{};
    if (nextHop == 0 || nextHop == BROADCAST) return;
    out.valid = true;
    out.nextHop = nextHop;
    out.requiresAck = true;
    out.hopLimit = hopLimit ? hopLimit : 1;
    out.length = static_cast<uint16_t>(VanguardProto::encode(
      error, out.payload, sizeof(out.payload)));
    if (out.length == 0) out = TxControl{};
  }

  bool acceptRerr(const VanguardProto::RouteError& error, uint32_t now) {
    for (const auto& x : rerr_) {
      if (x.used && x.reporter == error.reporter &&
          x.eventId == error.failureEventId) {
        return false;
      }
    }
    for (auto& x : rerr_) {
      if (!x.used || reached(now, x.expiresAtMs)) {
        x = SeenRerr{};
        x.used = true;
        x.reporter = error.reporter;
        x.eventId = error.failureEventId;
        x.expiresAtMs = now + RERR_CACHE_MS;
        return true;
      }
    }
    return false;
  }

  size_t emitRerrToPrecursors(
    uint32_t destination,
    VanguardProto::RouteError error,
    uint8_t hopLimit,
    TxControl* out,
    size_t capacity
  ) {
    PrecursorSet* precursors = nullptr;
    for (auto& x : prec_) {
      if (x.used && x.destination == destination) {
        precursors = &x;
        break;
      }
    }
    if (precursors == nullptr) return 0;

    size_t outputCount = 0;
    error.hopCount = static_cast<uint8_t>(error.hopCount + 1u);
    for (uint8_t i = 0; i < precursors->count && outputCount < capacity; ++i) {
      if (precursors->nodes[i] == 0) continue;
      TxControl& tx = out[outputCount++];
      tx = TxControl{};
      tx.valid = true;
      tx.nextHop = precursors->nodes[i];
      tx.requiresAck = true;
      tx.hopLimit = hopLimit > 1 ? static_cast<uint8_t>(hopLimit - 1u) : 1;
      tx.length = static_cast<uint16_t>(VanguardProto::encode(
        error, tx.payload, sizeof(tx.payload)));
    }
    return outputCount;
  }

  void expireCaches(uint32_t now) {
    for (auto& x : reverse_) {
      if (x.used && reached(now, x.expiresAtMs)) x = ReversePath{};
    }
    for (auto& x : rreq_) {
      if (x.used && reached(now, x.expiresAtMs)) x = SeenRreq{};
    }
    for (auto& x : rerr_) {
      if (x.used && reached(now, x.expiresAtMs)) x = SeenRerr{};
    }
    for (auto& x : flow_) {
      if (x.used && reached(now, x.expiresAtMs)) x = FlowLabel{};
    }
  }
};

} // namespace VanguardRuntime
