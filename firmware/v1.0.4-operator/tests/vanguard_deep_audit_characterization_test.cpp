#include "../VanguardCore.h"
#include "../VanguardRuntime.h"
#include "../VanguardManifest.h"
#include <cassert>
#include <cstdio>

using Engine = Vanguard::Engine<8>;
using Runtime = VanguardRuntime::State<>;

static Vanguard::Candidate candidate(
    uint32_t destination,
    uint32_t nextHop,
    uint32_t bootEpoch,
    uint32_t routeSeq,
    uint32_t advertisedRank,
    uint32_t internalMask,
    uint32_t pathTag,
    uint16_t reliability = 32000,
    uint32_t eca = (1u << 16),
    uint8_t hops = 2) {
  Vanguard::Candidate c;
  c.destination = destination;
  c.nextHop = nextHop;
  c.generation = {bootEpoch, routeSeq};
  c.advertisedGuardRank = advertisedRank;
  c.internalPathMask = internalMask;
  c.exactMask = true;
  c.ecaQ16 = eca;
  c.reliabilityQ15 = reliability;
  c.hopCount = hops;
  c.learnedAtMs = 100;
  c.pathTag = pathTag;
  return c;
}

static void characterizeIdleG2Aging() {
  // Current behavior: an unused exact G2 is not kept hot merely because the
  // primary next hop continues to validate. After DEFAULT_ROUTE_EXPIRE_MS the
  // standby ages out independently. This is not asserted as a desired design;
  // the test makes the present lease semantics explicit for the routing audit.
  Engine e;
  e.setIdentity(0xA, 0, 9, true);

  const auto primary = candidate(0xD, 0xB, 7, 1, 1, 1u << 1, 101);
  assert(e.install(primary, 100, false) == Vanguard::InstallResult::InstalledPrimary);

  const auto backup = candidate(0xD, 0xC, 7, 1, 2, 1u << 2, 102, 31500, 2u << 16, 3);
  assert(e.install(backup, 110, true) == Vanguard::InstallResult::InstalledBackup);
  assert(e.hasExactG2(0xD));

  // Refresh only the primary immediately before the backup crosses its 90 s lease.
  e.validateNextHop(0xB, 90110);
  e.expire(90111);

  const auto* route = e.find(0xD);
  assert(route != nullptr);
  assert(route->primary.valid && route->primary.nextHop == 0xB);
  assert(!route->backup.valid);
  assert(!e.hasExactG2(0xD));
}

static void characterizeSameEpochIdentityRemap() {
  // Current Engine::setIdentity() clears routes only when networkEpoch changes.
  // If a caller were to change slot mapping while incorrectly reusing the same
  // epoch, Core has no ManifestDigest input with which to detect the mismatch.
  // This test deliberately characterizes that defensive-contract dependency.
  Engine e;
  e.setIdentity(0xA, 0, 55, true);

  const auto primary = candidate(0xD, 0xB, 5, 1, 1, 1u << 1, 201);
  assert(e.install(primary, 100, false) == Vanguard::InstallResult::InstalledPrimary);
  assert(e.find(0xD) != nullptr);

  // Reuse epoch 55 but remap the local node from slot 0 to slot 1. The stored
  // exact mask now contains the local slot, yet existing Core route state stays.
  e.setIdentity(0xA, 1, 55, true);
  const auto* route = e.find(0xD);
  assert(route != nullptr && route->primary.valid);
  assert((route->primary.internalPathMask & (1u << 1)) != 0);

  uint32_t nextHop = 0;
  bool fromBackup = false;
  uint32_t pathTag = 0;
  assert(e.resolve(0xD, 101, nextHop, fromBackup, &pathTag));
  assert(nextHop == 0xB && pathTag == 201);
}

static void characterizeDiscoveryDeadlineWithoutEmissionFeedback() {
  // Runtime starts the attempt deadline in beginDiscovery(). It has no callback
  // that confirms when the outer firmware airtime/TX queue actually emits the
  // returned RREQ. Therefore a caller that holds/deferes this first TxControl
  // can still cause Runtime to advance to retry #2 when the deadline arrives.
  uint32_t nodes[2] = {0xA, 0xD};
  VanguardManifest::Manifest<> manifest;
  assert(manifest.configure(77, nodes, 2, 0xA));

  Runtime runtime;
  runtime.reset(0xA, 3);

  VanguardRuntime::TxControl first;
  VanguardRuntime::Event startEvent;
  assert(runtime.beginDiscovery(0xD, false, 0, 100, manifest, first, &startEvent));
  assert(first.valid);
  assert(startEvent.type == VanguardRuntime::EventType::DiscoveryStarted);

  // Intentionally do not feed any "sent" acknowledgement back; no such API exists.
  VanguardRuntime::TxControl out[2]{};
  VanguardRuntime::Event events[2]{};
  const uint32_t deadline = 100 + runtime.timing().discoveryTimeoutMs;
  const size_t n = runtime.tick(deadline, manifest, out, 2, events, 2);
  assert(n == 1);
  assert(events[0].type == VanguardRuntime::EventType::DiscoveryRetry);
}

int main() {
  characterizeIdleG2Aging();
  characterizeSameEpochIdentityRemap();
  characterizeDiscoveryDeadlineWithoutEmissionFeedback();
  std::puts("VANGUARD deep-audit characterization tests: PASS");
  return 0;
}
