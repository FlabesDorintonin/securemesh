#pragma once
#include <stdint.h>
#include <stddef.h>

namespace Vanguard {

constexpr uint32_t INVALID_NODE = 0u;
constexpr uint32_t INFINITE_RANK = 0xFFFFFFFFu;
constexpr uint32_t DEFAULT_BACKUP_HOT_MS = 12000u;
constexpr uint32_t DEFAULT_BACKUP_WARM_MS = 30000u;
constexpr uint32_t DEFAULT_ROUTE_EXPIRE_MS = 90000u;
constexpr uint16_t RELIABILITY_ONE_Q15 = 32767u;

enum class LeaseState : uint8_t {
  Empty = 0,
  Hot = 1,
  Warm = 2,
  Cold = 3,
  Expired = 4
};

enum class PathClass : uint8_t {
  Normal = 0,
  ExactG2 = 1,
  FeasibleAlternate = 2
};

enum class InstallResult : uint8_t {
  RejectedInvalid = 0,
  RejectedOldGeneration = 1,
  RejectedLoop = 2,
  RejectedInfeasible = 3,
  RejectedWorse = 4,
  RejectedSamePath = 5,
  InstalledPrimary = 6,
  UpdatedPrimary = 7,
  InstalledBackup = 8,
  UpdatedBackup = 9,
  InstalledAlternate = 10,
  UpdatedAlternate = 11
};

struct Generation {
  uint32_t bootEpoch = 0;
  uint32_t routeSeq = 0;
};

// RFC-1982-style half-range serial arithmetic. SecureMesh never intentionally
// jumps counters by >= 2^31, so signed-difference comparison is unambiguous
// for all valid transitions.
inline int compareSerial32(uint32_t a, uint32_t b) {
  if (a == b) return 0;
  return static_cast<int32_t>(a - b) > 0 ? 1 : -1;
}

inline int compareGeneration(const Generation& a, const Generation& b) {
  if (a.bootEpoch != b.bootEpoch) {
    return compareSerial32(a.bootEpoch, b.bootEpoch);
  }
  return compareSerial32(a.routeSeq, b.routeSeq);
}

inline uint32_t saturatingPlusOne(uint32_t value) {
  return value >= 0xFFFFFFFEu ? 0xFFFFFFFEu : value + 1u;
}

inline uint16_t mulQ15(uint16_t a, uint16_t b) {
  return static_cast<uint16_t>(
    (static_cast<uint32_t>(a) * static_cast<uint32_t>(b) + 16383u) / 32767u);
}

inline uint32_t satAdd32(uint32_t a, uint32_t b) {
  return UINT32_MAX - a < b ? UINT32_MAX : a + b;
}

struct Candidate {
  uint32_t destination = 0;
  uint32_t nextHop = 0;
  Generation generation {};
  uint32_t advertisedGuardRank = INFINITE_RANK;
  uint32_t internalPathMask = 0;
  bool exactMask = false;
  uint32_t ecaQ16 = 0;
  uint16_t reliabilityQ15 = 0;
  uint8_t hopCount = 0;
  uint32_t learnedAtMs = 0;
  // Non-zero for a discovered, path-pinned route. For VANGUARD discovery the
  // requestId is used as the pathTag. Zero means ordinary hop-by-hop route.
  uint32_t pathTag = 0;
};

struct PathState {
  bool valid = false;
  uint32_t nextHop = 0;
  uint32_t internalPathMask = 0;
  bool exactMask = false;
  uint32_t ecaQ16 = 0;
  uint16_t reliabilityQ15 = 0;
  uint8_t hopCount = 0;
  uint32_t advertisedGuardRank = INFINITE_RANK;
  uint32_t localGuardRank = INFINITE_RANK;
  uint32_t learnedAtMs = 0;
  uint32_t lastValidatedAtMs = 0;
  uint32_t pathTag = 0;
  LeaseState lease = LeaseState::Empty;
  PathClass pathClass = PathClass::Normal;
};

struct RouteEntry {
  bool used = false;
  uint32_t destination = 0;
  Generation generation {};

  // Feasible Distance is the minimum LOCAL rank observed for this generation.
  // A same-generation candidate advertised by a neighbour is feasible only if
  // advertisedGuardRank < feasibleDistance. FD never increases inside a
  // generation; a newer generation resets it.
  uint32_t feasibleDistance = INFINITE_RANK;
  uint32_t guardRank = INFINITE_RANK;
  uint32_t version = 0;
  uint32_t lastTouchedAtMs = 0;
  bool selectedFromBackup = false;

  PathState primary {};
  PathState backup {};
  PathState alternate {};
};

struct Stats {
  uint32_t acceptedPrimary = 0;
  uint32_t acceptedBackup = 0;
  uint32_t acceptedAlternate = 0;
  uint32_t rejectedOldGeneration = 0;
  uint32_t rejectedLoop = 0;
  uint32_t rejectedInfeasible = 0;
  uint32_t rejectedWorse = 0;
  uint32_t rejectedSamePath = 0;
  uint32_t promotionsG2 = 0;
  uint32_t promotionsAlternate = 0;
  uint32_t expirations = 0;
  uint32_t routeErrors = 0;
};

inline bool candidateBetter(const Candidate& candidate, const PathState& current) {
  if (!current.valid) return true;

  // ~0.5 percentage-point reliability hysteresis. Inside the band prefer
  // lower expected channel airtime, then fewer hops. This prevents tiny noisy
  // reliability changes from causing path flaps.
  constexpr uint16_t REL_HYST_Q15 = 164u;
  const int32_t delta = static_cast<int32_t>(candidate.reliabilityQ15) -
                        static_cast<int32_t>(current.reliabilityQ15);
  if (delta > static_cast<int32_t>(REL_HYST_Q15)) return true;
  if (delta < -static_cast<int32_t>(REL_HYST_Q15)) return false;
  if (candidate.ecaQ16 != current.ecaQ16) {
    return candidate.ecaQ16 < current.ecaQ16;
  }
  return candidate.hopCount < current.hopCount;
}

template <size_t MAX_ROUTES>
class Engine {
 public:
  Engine() { reset(); }

  void reset() {
    clearRoutes();
    localNodeId_ = 0;
    localSlot_ = 0xFF;
    networkEpoch_ = 0;
    manifestValid_ = false;
    stats_ = Stats{};
  }

  void setIdentity(
    uint32_t nodeId,
    uint8_t slot,
    uint32_t epoch,
    bool manifestValid
  ) {
    const bool epochChanged = networkEpoch_ != 0 && epoch != networkEpoch_;
    localNodeId_ = nodeId;
    localSlot_ = slot;
    networkEpoch_ = epoch;
    manifestValid_ = manifestValid && slot < 32;
    if (epochChanged) clearRoutes();
  }

  void clearRoutes() {
    for (auto& route : routes_) route = RouteEntry{};
  }

  uint32_t networkEpoch() const { return networkEpoch_; }
  bool exactDiversityEnabled() const { return manifestValid_; }
  const Stats& stats() const { return stats_; }
  void clearStats() { stats_ = Stats{}; }
  size_t capacity() const { return MAX_ROUTES; }
  const RouteEntry* routes() const { return routes_; }

  RouteEntry* find(uint32_t destination) {
    for (auto& route : routes_) {
      if (route.used && route.destination == destination) return &route;
    }
    return nullptr;
  }

  const RouteEntry* find(uint32_t destination) const {
    for (const auto& route : routes_) {
      if (route.used && route.destination == destination) return &route;
    }
    return nullptr;
  }

  bool resolve(
    uint32_t destination,
    uint32_t now,
    uint32_t& nextHop,
    bool& fromBackup,
    uint32_t* pathTag = nullptr
  ) {
    expire(now);
    RouteEntry* route = find(destination);
    if (route == nullptr || !route->primary.valid) return false;
    nextHop = route->primary.nextHop;
    fromBackup = route->selectedFromBackup;
    if (pathTag != nullptr) *pathTag = route->primary.pathTag;
    return nextHop != 0;
  }

  bool resolvePath(uint32_t destination, uint32_t now, PathState& path) {
    expire(now);
    const RouteEntry* route = find(destination);
    if (route == nullptr || !route->primary.valid) return false;
    path = route->primary;
    return true;
  }

  bool resolveGeneric(
    uint32_t destination,
    uint32_t now,
    uint32_t& nextHop
  ) {
    expire(now);
    const RouteEntry* route = find(destination);
    if (route == nullptr || !route->primary.valid) return false;
    // A source-pinned path that is not feasible under the destination's FD is
    // safe only when packets carry its path label. It must not leak into
    // ordinary hop-by-hop forwarding.
    if (route->primary.pathTag != 0 &&
        route->primary.advertisedGuardRank >= route->feasibleDistance) {
      return false;
    }
    nextHop = route->primary.nextHop;
    return nextHop != 0;
  }

  bool hasExactG2(uint32_t destination) const {
    const RouteEntry* route = find(destination);
    return route != nullptr && route->primary.valid && route->backup.valid &&
      manifestValid_ && route->primary.exactMask && route->backup.exactMask &&
      route->primary.nextHop != route->backup.nextHop &&
      ((route->primary.internalPathMask & route->backup.internalPathMask) == 0);
  }

  uint32_t guardRank(uint32_t destination) const {
    const RouteEntry* route = find(destination);
    return route != nullptr && route->primary.valid
      ? route->guardRank : INFINITE_RANK;
  }

  uint32_t primaryMask(uint32_t destination) const {
    const RouteEntry* route = find(destination);
    return route != nullptr && route->primary.valid
      ? route->primary.internalPathMask : 0;
  }

  uint32_t primaryNextHop(uint32_t destination) const {
    const RouteEntry* route = find(destination);
    return route != nullptr && route->primary.valid ? route->primary.nextHop : 0;
  }

  InstallResult install(
    const Candidate& candidate,
    uint32_t now,
    bool g2Probe = false
  ) {
    if (!candidateValid(candidate)) return InstallResult::RejectedInvalid;

    if (manifestValid_ && candidate.exactMask && localSlot_ < 32 &&
        (candidate.internalPathMask & (1u << localSlot_)) != 0) {
      ++stats_.rejectedLoop;
      return InstallResult::RejectedLoop;
    }

    RouteEntry* route = find(candidate.destination);
    if (route == nullptr) {
      route = allocate(candidate.destination);
      if (route == nullptr) {
        ++stats_.rejectedWorse;
        return InstallResult::RejectedWorse;
      }
    }

    const bool routeHasGeneration = route->generation.bootEpoch != 0;
    const int generationCmp = routeHasGeneration
      ? compareGeneration(candidate.generation, route->generation) : 1;

    if (routeHasGeneration && generationCmp < 0) {
      ++stats_.rejectedOldGeneration;
      return InstallResult::RejectedOldGeneration;
    }

    if (!routeHasGeneration || generationCmp > 0) {
      const uint32_t nextVersion = route->version + 1u;
      *route = RouteEntry{};
      route->used = true;
      route->destination = candidate.destination;
      route->generation = candidate.generation;
      route->version = nextVersion;
      route->lastTouchedAtMs = now;
    }

    const uint32_t localRank = saturatingPlusOne(candidate.advertisedGuardRank);
    const bool samePrimary = route->primary.valid &&
      route->primary.nextHop == candidate.nextHop;
    const bool sameBackup = route->backup.valid &&
      route->backup.nextHop == candidate.nextHop;
    const bool sameAlternate = route->alternate.valid &&
      route->alternate.nextHop == candidate.nextHop;

    // A G2 response is never allowed to silently mutate the primary through
    // the same first hop. This closes the direct-link mask==0 false-G2 case.
    if (g2Probe && samePrimary && route->primary.valid) {
      ++stats_.rejectedSamePath;
      return InstallResult::RejectedSamePath;
    }

    const bool pinnedExactG2 = g2Probe && route->primary.valid &&
      candidate.pathTag != 0 && isExactDisjoint(candidate, *route);

    // Ordinary distance-vector successors remain under strict feasibility.
    // A source-private exact G2 path is different: its DATA packets are pinned
    // to the RREP-installed flow-label chain, so it cannot form a forwarding
    // loop by being locally re-selected at intermediate routers. This lets a
    // longer standby coexist with a very short primary (e.g. direct A->D plus
    // backup A->C->D) without weakening generic hop-by-hop loop safety.
    if (!pinnedExactG2 &&
        candidate.advertisedGuardRank >= route->feasibleDistance) {
      ++stats_.rejectedInfeasible;
      return InstallResult::RejectedInfeasible;
    }

    if (pinnedExactG2) {
      if (!route->backup.valid || candidateBetter(candidate, route->backup)) {
        updatePath(route->backup, candidate, now, PathClass::ExactG2);
        ++route->version;
        route->lastTouchedAtMs = now;
        ++stats_.acceptedBackup;
        sanitizeStandbys(*route);
        return InstallResult::InstalledBackup;
      }
      ++stats_.rejectedWorse;
      return InstallResult::RejectedWorse;
    }

    if (samePrimary) {
      updatePath(route->primary, candidate, now, PathClass::Normal);
      route->guardRank = localRank;
      lowerFd(*route, localRank);
      route->selectedFromBackup = false;
      ++route->version;
      route->lastTouchedAtMs = now;
      sanitizeStandbys(*route);
      return InstallResult::UpdatedPrimary;
    }

    if (sameBackup) {
      updatePath(route->backup, candidate, now, PathClass::ExactG2);
      ++route->version;
      route->lastTouchedAtMs = now;
      sanitizeStandbys(*route);
      return route->backup.valid && route->backup.nextHop == candidate.nextHop
        ? InstallResult::UpdatedBackup : InstallResult::UpdatedAlternate;
    }

    if (sameAlternate) {
      updatePath(route->alternate, candidate, now, PathClass::FeasibleAlternate);
      ++route->version;
      route->lastTouchedAtMs = now;
      sanitizeStandbys(*route);
      return route->backup.valid && route->backup.nextHop == candidate.nextHop
        ? InstallResult::UpdatedBackup : InstallResult::UpdatedAlternate;
    }

    const bool exactDisjoint = isExactDisjoint(candidate, *route);

    // A G2 probe has one job: create/refresh an exact, different-first-hop
    // standby. It must never replace the primary merely because it scores
    // better, otherwise a discovery for redundancy can destabilise routing.
    if (g2Probe && route->primary.valid) {
      if (!exactDisjoint) {
        ++stats_.rejectedSamePath;
        return InstallResult::RejectedSamePath;
      }
      if (!route->backup.valid || candidateBetter(candidate, route->backup)) {
        updatePath(route->backup, candidate, now, PathClass::ExactG2);
        ++route->version;
        route->lastTouchedAtMs = now;
        ++stats_.acceptedBackup;
        sanitizeStandbys(*route);
        return InstallResult::InstalledBackup;
      }
      ++stats_.rejectedWorse;
      return InstallResult::RejectedWorse;
    }

    if (!route->primary.valid || candidateBetter(candidate, route->primary)) {
      const PathState oldPrimary = route->primary;
      updatePath(route->primary, candidate, now, PathClass::Normal);
      route->guardRank = localRank;
      lowerFd(*route, localRank);
      route->selectedFromBackup = false;

      if (oldPrimary.valid && pathFeasible(oldPrimary, *route) &&
          oldPrimary.nextHop != route->primary.nextHop) {
        if (pathExactDisjoint(oldPrimary, route->primary)) {
          route->backup = oldPrimary;
          route->backup.pathClass = PathClass::ExactG2;
        } else {
          route->alternate = oldPrimary;
          route->alternate.pathClass = PathClass::FeasibleAlternate;
        }
      }

      ++route->version;
      route->lastTouchedAtMs = now;
      ++stats_.acceptedPrimary;
      sanitizeStandbys(*route);
      return InstallResult::InstalledPrimary;
    }

    if (exactDisjoint &&
        (!route->backup.valid || candidateBetter(candidate, route->backup))) {
      updatePath(route->backup, candidate, now, PathClass::ExactG2);
      ++route->version;
      route->lastTouchedAtMs = now;
      ++stats_.acceptedBackup;
      sanitizeStandbys(*route);
      return InstallResult::InstalledBackup;
    }

    if (!route->alternate.valid || candidateBetter(candidate, route->alternate)) {
      updatePath(route->alternate, candidate, now, PathClass::FeasibleAlternate);
      ++route->version;
      route->lastTouchedAtMs = now;
      ++stats_.acceptedAlternate;
      sanitizeStandbys(*route);
      return InstallResult::InstalledAlternate;
    }

    ++stats_.rejectedWorse;
    return InstallResult::RejectedWorse;
  }

  void validateNextHop(uint32_t hop, uint32_t now) {
    for (auto& route : routes_) {
      if (!route.used) continue;
      for (PathState* path : {&route.primary, &route.backup, &route.alternate}) {
        if (path->valid && path->nextHop == hop) {
          path->lastValidatedAtMs = now;
          path->lease = LeaseState::Hot;
        }
      }
    }
  }

  bool onPathTagFailure(
    uint32_t destination,
    uint32_t pathTag,
    uint32_t now,
    bool* promotedG2 = nullptr
  ) {
    RouteEntry* route = find(destination);
    if (route == nullptr || pathTag == 0) return false;

    if (route->backup.valid && route->backup.pathTag == pathTag) {
      route->backup = PathState{};
      ++route->version;
      return route->primary.valid;
    }
    if (route->alternate.valid && route->alternate.pathTag == pathTag) {
      route->alternate = PathState{};
      ++route->version;
      return route->primary.valid;
    }
    if (!route->primary.valid || route->primary.pathTag != pathTag) {
      return route->primary.valid;
    }
    return failPrimary(*route, now, promotedG2);
  }

  bool onRouteFailure(
    uint32_t destination,
    uint32_t failedNextHop,
    uint32_t now,
    bool* promotedG2 = nullptr
  ) {
    RouteEntry* route = find(destination);
    if (route == nullptr) return false;

    const bool primaryFailed = route->primary.valid &&
      route->primary.nextHop == failedNextHop;
    if (route->backup.valid && route->backup.nextHop == failedNextHop) {
      route->backup = PathState{};
    }
    if (route->alternate.valid && route->alternate.nextHop == failedNextHop) {
      route->alternate = PathState{};
    }
    if (!primaryFailed) return route->primary.valid;
    return failPrimary(*route, now, promotedG2);
  }

  size_t destinationsUsingNextHop(
    uint32_t hop,
    uint32_t* out,
    size_t capacity
  ) const {
    size_t count = 0;
    for (const auto& route : routes_) {
      if (!route.used || !route.primary.valid || route.primary.nextHop != hop) {
        continue;
      }
      if (count < capacity) out[count] = route.destination;
      ++count;
    }
    return count;
  }

  bool onHardLinkFailure(
    uint32_t hop,
    uint32_t now,
    uint32_t* promotedDestination = nullptr
  ) {
    uint32_t destinations[MAX_ROUTES] {};
    const size_t count = destinationsUsingNextHop(hop, destinations, MAX_ROUTES);
    bool anySurvived = false;
    for (size_t i = 0; i < count && i < MAX_ROUTES; ++i) {
      bool promotedG2 = false;
      if (onRouteFailure(destinations[i], hop, now, &promotedG2)) {
        anySurvived = true;
        if (promotedDestination != nullptr) {
          *promotedDestination = destinations[i];
        }
      }
    }
    return anySurvived;
  }

  void expire(uint32_t now) {
    for (auto& route : routes_) {
      if (!route.used) continue;
      agePath(route.primary, now, true);
      agePath(route.backup, now, false);
      agePath(route.alternate, now, false);
      sanitizeStandbys(route);

      if (!route.primary.valid) {
        bool promotedG2 = false;
        (void)promoteStandby(route, now, &promotedG2);
      }
    }
  }

 private:
  RouteEntry routes_[MAX_ROUTES] {};
  uint32_t localNodeId_ = 0;
  uint8_t localSlot_ = 0xFF;
  uint32_t networkEpoch_ = 0;
  bool manifestValid_ = false;
  Stats stats_ {};

  bool candidateValid(const Candidate& candidate) const {
    return candidate.destination != 0 &&
      candidate.destination != localNodeId_ &&
      candidate.nextHop != 0 &&
      candidate.nextHop != localNodeId_ &&
      candidate.generation.bootEpoch != 0 &&
      candidate.generation.routeSeq != 0 &&
      candidate.advertisedGuardRank != INFINITE_RANK;
  }

  RouteEntry* allocate(uint32_t destination) {
    for (auto& route : routes_) {
      if (!route.used) {
        route = RouteEntry{};
        route.used = true;
        route.destination = destination;
        return &route;
      }
    }
    return nullptr;
  }

  static void lowerFd(RouteEntry& route, uint32_t localRank) {
    if (localRank < route.feasibleDistance) route.feasibleDistance = localRank;
  }

  static bool pathFeasible(const PathState& path, const RouteEntry& route) {
    return path.valid && path.advertisedGuardRank < route.feasibleDistance;
  }

  bool pathExactDisjoint(const PathState& a, const PathState& b) const {
    return manifestValid_ && a.valid && b.valid && a.exactMask && b.exactMask &&
      a.nextHop != b.nextHop &&
      ((a.internalPathMask & b.internalPathMask) == 0);
  }

  bool isExactDisjoint(const Candidate& candidate, const RouteEntry& route) const {
    return manifestValid_ && route.primary.valid && route.primary.exactMask &&
      candidate.exactMask && candidate.nextHop != route.primary.nextHop &&
      ((candidate.internalPathMask & route.primary.internalPathMask) == 0);
  }

  static void updatePath(
    PathState& path,
    const Candidate& candidate,
    uint32_t now,
    PathClass pathClass
  ) {
    path.valid = true;
    path.nextHop = candidate.nextHop;
    path.internalPathMask = candidate.internalPathMask;
    path.exactMask = candidate.exactMask;
    path.ecaQ16 = candidate.ecaQ16;
    path.reliabilityQ15 = candidate.reliabilityQ15;
    path.hopCount = candidate.hopCount;
    path.advertisedGuardRank = candidate.advertisedGuardRank;
    path.localGuardRank = saturatingPlusOne(candidate.advertisedGuardRank);
    path.learnedAtMs = candidate.learnedAtMs != 0 ? candidate.learnedAtMs : now;
    path.lastValidatedAtMs = now;
    path.pathTag = candidate.pathTag;
    path.lease = LeaseState::Hot;
    path.pathClass = pathClass;
  }

  void sanitizeStandbys(RouteEntry& route) {
    if (!route.primary.valid) return;

    if (route.backup.valid) {
      const bool pinnedExact = route.backup.pathTag != 0 &&
        pathExactDisjoint(route.backup, route.primary);
      if ((!pinnedExact && !pathFeasible(route.backup, route)) ||
          route.backup.nextHop == route.primary.nextHop) {
        route.backup = PathState{};
      }
    }
    if (route.alternate.valid &&
        (!pathFeasible(route.alternate, route) ||
         route.alternate.nextHop == route.primary.nextHop)) {
      route.alternate = PathState{};
    }

    if (route.backup.valid && !pathExactDisjoint(route.backup, route.primary)) {
      PathState demoted = route.backup;
      route.backup = PathState{};
      demoted.pathClass = PathClass::FeasibleAlternate;
      if (!route.alternate.valid || pathStateBetter(demoted, route.alternate)) {
        route.alternate = demoted;
      }
    }

    if (route.alternate.valid && pathExactDisjoint(route.alternate, route.primary)) {
      PathState promoted = route.alternate;
      route.alternate = PathState{};
      promoted.pathClass = PathClass::ExactG2;
      if (!route.backup.valid || pathStateBetter(promoted, route.backup)) {
        if (route.backup.valid) {
          PathState oldBackup = route.backup;
          oldBackup.pathClass = PathClass::FeasibleAlternate;
          route.alternate = oldBackup;
        }
        route.backup = promoted;
      } else {
        promoted.pathClass = PathClass::FeasibleAlternate;
        route.alternate = promoted;
      }
    }
  }

  static bool pathStateBetter(const PathState& a, const PathState& b) {
    if (!b.valid) return true;
    constexpr uint16_t REL_HYST_Q15 = 164u;
    const int32_t delta = static_cast<int32_t>(a.reliabilityQ15) -
                          static_cast<int32_t>(b.reliabilityQ15);
    if (delta > static_cast<int32_t>(REL_HYST_Q15)) return true;
    if (delta < -static_cast<int32_t>(REL_HYST_Q15)) return false;
    if (a.ecaQ16 != b.ecaQ16) return a.ecaQ16 < b.ecaQ16;
    return a.hopCount < b.hopCount;
  }

  bool failPrimary(RouteEntry& route, uint32_t now, bool* promotedG2) {
    route.primary = PathState{};
    route.selectedFromBackup = false;
    ++route.version;
    ++stats_.routeErrors;
    if (promotedG2 != nullptr) *promotedG2 = false;
    return promoteStandby(route, now, promotedG2);
  }

  bool promoteStandby(RouteEntry& route, uint32_t now, bool* promotedG2) {
    sanitizeStandbys(route);
    if (route.backup.valid && route.backup.lease != LeaseState::Expired) {
      route.primary = route.backup;
      route.backup = PathState{};
      route.primary.pathClass = PathClass::Normal;
      route.selectedFromBackup = true;
      route.guardRank = route.primary.localGuardRank;
      route.primary.lease = LeaseState::Hot;
      route.primary.lastValidatedAtMs = now;
      ++route.version;
      ++stats_.promotionsG2;
      if (promotedG2 != nullptr) *promotedG2 = true;
      sanitizeStandbys(route);
      return true;
    }

    if (route.alternate.valid && route.alternate.lease != LeaseState::Expired) {
      route.primary = route.alternate;
      route.alternate = PathState{};
      route.primary.pathClass = PathClass::Normal;
      route.selectedFromBackup = false;
      route.guardRank = route.primary.localGuardRank;
      route.primary.lease = LeaseState::Hot;
      route.primary.lastValidatedAtMs = now;
      ++route.version;
      ++stats_.promotionsAlternate;
      if (promotedG2 != nullptr) *promotedG2 = false;
      sanitizeStandbys(route);
      return true;
    }

    route.guardRank = INFINITE_RANK;
    return false;
  }

  void agePath(PathState& path, uint32_t now, bool primary) {
    if (!path.valid) return;
    const uint32_t age = now - path.lastValidatedAtMs;
    if (primary) {
      if (age > DEFAULT_ROUTE_EXPIRE_MS) {
        path = PathState{};
        ++stats_.expirations;
      }
      return;
    }

    if (age <= DEFAULT_BACKUP_HOT_MS) {
      path.lease = LeaseState::Hot;
    } else if (age <= DEFAULT_BACKUP_WARM_MS) {
      path.lease = LeaseState::Warm;
    } else if (age <= DEFAULT_ROUTE_EXPIRE_MS) {
      path.lease = LeaseState::Cold;
    } else {
      path = PathState{};
      ++stats_.expirations;
    }
  }
};

}  // namespace Vanguard
