#include "../VanguardCore.h"
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>

static uint32_t rngState = 0xC001D00Du;
static uint32_t rnd() {
  uint32_t x = rngState;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  rngState = x;
  return x;
}

static void assertRouteInvariants(const Vanguard::Engine<16>& e, uint32_t localBit) {
  for (size_t i = 0; i < e.capacity(); ++i) {
    const auto& r = e.routes()[i];
    if (!r.used) continue;
    if (r.primary.valid) {
      assert(r.primary.nextHop != 0 && r.primary.nextHop != 0xA);
      if (r.primary.exactMask) assert((r.primary.internalPathMask & localBit) == 0);
      // Ordinary hop-by-hop primary must remain feasible. A source-private
      // pathTag may be an intentionally longer pinned G2 after promotion.
      if (r.primary.pathTag == 0) {
        assert(r.primary.advertisedGuardRank < r.feasibleDistance);
      }
    }
    if (r.backup.valid) {
      assert(r.primary.valid);
      assert(r.backup.nextHop != r.primary.nextHop);
      assert(r.backup.exactMask && r.primary.exactMask);
      assert((r.backup.internalPathMask & r.primary.internalPathMask) == 0);
      if (r.backup.pathTag == 0) {
        assert(r.backup.advertisedGuardRank < r.feasibleDistance);
      }
    }
    if (r.alternate.valid) {
      assert(r.primary.valid);
      assert(r.alternate.nextHop != r.primary.nextHop);
      assert(r.alternate.advertisedGuardRank < r.feasibleDistance);
    }
    if (e.hasExactG2(r.destination)) {
      assert(r.backup.valid);
      assert((r.primary.internalPathMask & r.backup.internalPathMask) == 0);
    }
  }
}

int main() {
  const char* seedEnv = std::getenv("VANGUARD_FUZZ_SEED");
  const char* opsEnv = std::getenv("VANGUARD_FUZZ_OPS");
  if (seedEnv && *seedEnv) rngState = static_cast<uint32_t>(std::strtoul(seedEnv, nullptr, 0));
  const uint32_t operations = (opsEnv && *opsEnv) ? static_cast<uint32_t>(std::strtoul(opsEnv, nullptr, 0)) : 200000u;
  const uint32_t initialSeed = rngState;
  assert(operations > 0);
  Vanguard::Engine<16> e;
  // Local node A is slot 0. Other mask bits simulate exact path membership.
  e.setIdentity(0xA, 0, 55, true);
  const uint32_t localBit = 1u;
  uint32_t now = 1;

  for (uint32_t step = 0; step < operations; ++step) {
    now += 1 + (rnd() % 7);
    const uint32_t op = rnd() % 10;
    const uint32_t dest = 0x100 + (rnd() % 8);

    if (op < 7) {
      Vanguard::Candidate c;
      c.destination = dest;
      c.nextHop = 0xB + (rnd() % 6);
      c.generation.bootEpoch = 1 + (rnd() % 3);
      c.generation.routeSeq = 1 + (rnd() % 12);
      c.advertisedGuardRank = rnd() % 9;
      c.exactMask = true;
      c.internalPathMask = ((rnd() >> 1) & 0x7Eu); // never includes local slot0
      c.ecaQ16 = (1 + (rnd() % 20)) << 16;
      c.reliabilityQ15 = static_cast<uint16_t>(18000 + (rnd() % 14768));
      c.hopCount = static_cast<uint8_t>(1 + (rnd() % 8));
      c.pathTag = (rnd() & 1u) ? (1 + rnd()) : 0;
      c.learnedAtMs = now;
      const bool g2 = (rnd() % 5) == 0;
      (void)e.install(c, now, g2);
    } else if (op == 7) {
      const auto* r = e.find(dest);
      if (r && r->primary.valid) {
        (void)e.onRouteFailure(dest, r->primary.nextHop, now, nullptr);
      }
    } else if (op == 8) {
      const auto* r = e.find(dest);
      if (r && r->primary.valid && r->primary.pathTag != 0) {
        (void)e.onPathTagFailure(dest, r->primary.pathTag, now, nullptr);
      }
    } else {
      e.expire(now + Vanguard::DEFAULT_ROUTE_EXPIRE_MS + 1);
      now += Vanguard::DEFAULT_ROUTE_EXPIRE_MS + 1;
    }
    assertRouteInvariants(e, localBit);
  }

  std::printf("VANGUARD core invariant fuzz tests: PASS (%u ops, seed=0x%08X)\n", operations, initialSeed);
  return 0;
}
