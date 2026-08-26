#include "../VanguardRuntime.h"
#include <cassert>
#include <cstdio>

using RT = VanguardRuntime::State<>;
using ENG = Vanguard::Engine<16>;

static VanguardRuntime::LinkMetric link() {
  return {1u << 16, 32000};
}

static VanguardProto::RouteRequest makeReq(
  uint32_t reqId,
  uint32_t origin,
  uint32_t dest,
  const VanguardManifest::Manifest<>& m,
  uint32_t originBoot,
  uint32_t pathMask = 0,
  uint8_t flags = 0
) {
  VanguardProto::RouteRequest q;
  q.flags = flags;
  q.requestId = reqId;
  q.origin = origin;
  q.destination = dest;
  q.networkEpoch = m.networkEpoch;
  q.manifestDigest = m.digest;
  q.originBootEpoch = originBoot;
  q.pathMask = pathMask;
  return q;
}

static VanguardRuntime::TxControl encodeReq(const VanguardProto::RouteRequest& q) {
  VanguardRuntime::TxControl tx;
  tx.length = static_cast<uint16_t>(VanguardProto::encode(q, tx.payload, sizeof(tx.payload)));
  tx.valid = tx.length != 0;
  tx.nextHop = VanguardRuntime::BROADCAST;
  tx.hopLimit = VanguardRuntime::DEFAULT_DISCOVERY_HOPS;
  assert(tx.valid);
  return tx;
}

static void testScopeLoopAndDuplicateGuards() {
  uint32_t ids[3] = {0xA, 0xB, 0xD};
  VanguardManifest::Manifest<> m;
  assert(m.configure(77, ids, 3, 0xB));

  ENG eb;
  eb.setIdentity(0xB, m.slotFor(0xB), 77, true);
  RT rb;
  rb.reset(0xB, 9);

  VanguardRuntime::TxControl out[4]{};
  VanguardRuntime::Event ev[4]{};

  // Wrong epoch/digest is rejected before it can mutate routing state.
  auto wrong = makeReq(1, 0xA, 0xD, m, 1);
  wrong.networkEpoch++;
  auto wrongTx = encodeReq(wrong);
  size_t n = rb.onControl(0xA, wrongTx.hopLimit, wrongTx.payload, wrongTx.length,
                          link(), 100, m, eb, out, 4, ev, 4);
  assert(n == 0);
  assert(ev[0].type == VanguardRuntime::EventType::ControlRejectedEpoch);

  // A request whose exact path already contains this node is a hard loop drop.
  for (auto& x : ev) x = VanguardRuntime::Event{};
  auto loopReq = makeReq(2, 0xA, 0xD, m, 1, m.bitFor(0xB));
  auto loopTx = encodeReq(loopReq);
  n = rb.onControl(0xA, loopTx.hopLimit, loopTx.payload, loopTx.length,
                   link(), 200, m, eb, out, 4, ev, 4);
  assert(n == 0);
  assert(ev[0].type == VanguardRuntime::EventType::ControlRejectedLoop);

  // First fresh RREQ is forwarded; exact duplicate is suppressed.
  for (auto& x : ev) x = VanguardRuntime::Event{};
  auto freshReq = makeReq(3, 0xA, 0xD, m, 1);
  auto freshTx = encodeReq(freshReq);
  n = rb.onControl(0xA, freshTx.hopLimit, freshTx.payload, freshTx.length,
                   link(), 300, m, eb, out, 4, ev, 4);
  assert(n == 1);
  VanguardRuntime::TxControl dupOut[4]{};
  n = rb.onControl(0xA, freshTx.hopLimit, freshTx.payload, freshTx.length,
                   link(), 301, m, eb, dupOut, 4, ev, 4);
  assert(n == 0);
}

static void testBoundedDiscoveryAndFreshGenerationRateLimit() {
  uint32_t ids[2] = {0xA, 0xD};
  VanguardManifest::Manifest<> ma;
  assert(ma.configure(12, ids, 2, 0xA));
  VanguardManifest::Manifest<> md;
  assert(md.configure(12, ids, 2, 0xD));

  RT ra;
  ra.reset(0xA, 3);
  VanguardRuntime::TxControl first;
  VanguardRuntime::Event started;
  assert(ra.beginDiscovery(0xD, false, 0, 0, ma, first, &started));
  assert(started.type == VanguardRuntime::EventType::DiscoveryStarted);

  VanguardRuntime::TxControl out[4]{};
  VanguardRuntime::Event ev[4]{};
  size_t n = ra.tick(2200, ma, out, 4, ev, 4);
  assert(n == 1);
  assert(ev[0].type == VanguardRuntime::EventType::DiscoveryRetry);
  VanguardProto::RouteRequest retry2;
  assert(VanguardProto::decode(out[0].payload, out[0].length, retry2));
  assert((retry2.flags & VanguardProto::FLAG_FORCE_FRESH_GENERATION) == 0);

  for (auto& x : out) x = VanguardRuntime::TxControl{};
  for (auto& x : ev) x = VanguardRuntime::Event{};
  n = ra.tick(5000, ma, out, 4, ev, 4);
  assert(n == 1);
  VanguardProto::RouteRequest retry3;
  assert(VanguardProto::decode(out[0].payload, out[0].length, retry3));
  assert((retry3.flags & VanguardProto::FLAG_FORCE_FRESH_GENERATION) != 0);

  for (auto& x : out) x = VanguardRuntime::TxControl{};
  for (auto& x : ev) x = VanguardRuntime::Event{};
  n = ra.tick(8400, ma, out, 4, ev, 4);
  assert(n == 0);
  assert(ev[0].type == VanguardRuntime::EventType::DiscoveryFailed);
  assert(ra.discoveryFor(0xD, false) == nullptr);

  // Destination only bumps route sequence after the minimum refresh interval,
  // even when independently authenticated requests ask for a fresh generation.
  RT rd;
  ENG ed;
  rd.reset(0xD, 5);
  ed.setIdentity(0xD, md.slotFor(0xD), 12, true);
  const uint32_t before = rd.routeSeq();

  auto q1 = makeReq(100, 0xA, 0xD, md, 3, 0,
                    VanguardProto::FLAG_FORCE_FRESH_GENERATION);
  auto tx1 = encodeReq(q1);
  VanguardRuntime::TxControl reply[2]{};
  VanguardRuntime::Event de[2]{};
  n = rd.onControl(0xA, tx1.hopLimit, tx1.payload, tx1.length,
                   link(), 3000, md, ed, reply, 2, de, 2);
  assert(n == 0);
  n = rd.tick(3000 + VanguardRuntime::RREQ_SETTLE_MS, md, reply, 2, de, 2);
  assert(n == 1);
  assert(rd.routeSeq() != before);
  const uint32_t afterFirst = rd.routeSeq();

  auto q2 = makeReq(101, 0xA, 0xD, md, 3, 0,
                    VanguardProto::FLAG_FORCE_FRESH_GENERATION);
  auto tx2 = encodeReq(q2);
  for (auto& x : reply) x = VanguardRuntime::TxControl{};
  n = rd.onControl(0xA, tx2.hopLimit, tx2.payload, tx2.length,
                   link(), 3100, md, ed, reply, 2, de, 2);
  assert(n == 0);
  n = rd.tick(3100 + VanguardRuntime::RREQ_SETTLE_MS, md, reply, 2, de, 2);
  assert(n == 1);
  assert(rd.routeSeq() == afterFirst);
}

static void testDestinationSettleSelectsOneBetterPathLabel() {
  uint32_t ids[4] = {0xA, 0xB, 0xC, 0xD};
  VanguardManifest::Manifest<> md;
  assert(md.configure(44, ids, 4, 0xD));
  RT rd;
  ENG ed;
  rd.reset(0xD, 7);
  ed.setIdentity(0xD, md.slotFor(0xD), 44, true);

  auto viaB = makeReq(500, 0xA, 0xD, md, 3);
  viaB.hopCount = 1;
  viaB.pathSlotCount = 1;
  viaB.pathSlots[0] = md.slotFor(0xB);
  viaB.pathMask = md.bitFor(0xB);
  viaB.discoveryReliabilityQ15 = 25000;
  viaB.discoveryEcaQ16 = 3u << 16;
  auto txB = encodeReq(viaB);

  VanguardRuntime::TxControl out[4]{};
  VanguardRuntime::Event ev[4]{};
  VanguardRuntime::LinkMetric weak{2u << 16, 26000};
  size_t n = rd.onControl(0xB, txB.hopLimit, txB.payload, txB.length,
                          weak, 1000, md, ed, out, 4, ev, 4);
  assert(n == 0);

  auto viaC = makeReq(500, 0xA, 0xD, md, 3);
  viaC.hopCount = 1;
  viaC.pathSlotCount = 1;
  viaC.pathSlots[0] = md.slotFor(0xC);
  viaC.pathMask = md.bitFor(0xC);
  viaC.discoveryReliabilityQ15 = 32000;
  viaC.discoveryEcaQ16 = 1u << 16;
  auto txC = encodeReq(viaC);
  VanguardRuntime::LinkMetric strong{1u << 16, 32000};
  n = rd.onControl(0xC, txC.hopLimit, txC.payload, txC.length,
                   strong, 1050, md, ed, out, 4, ev, 4);
  assert(n == 0);

  n = rd.tick(1000 + VanguardRuntime::RREQ_SETTLE_MS, md, out, 4, ev, 4);
  assert(n == 1);
  assert(out[0].nextHop == 0xC);
  VanguardProto::RouteReply reply;
  assert(VanguardProto::decode(out[0].payload, out[0].length, reply));
  assert(reply.requestId == 500);
  assert(reply.pathSlotCount == 1);
  assert(md.nodeFor(reply.pathSlots[0]) == 0xC);

  for (auto& x : out) x = VanguardRuntime::TxControl{};
  n = rd.tick(2000, md, out, 4, ev, 4);
  assert(n == 0); // exactly one RREP/pathTag for requestId 500
}

int main() {
  testScopeLoopAndDuplicateGuards();
  testBoundedDiscoveryAndFreshGenerationRateLimit();
  testDestinationSettleSelectsOneBetterPathLabel();
  std::puts("VANGUARD runtime safety tests: PASS");
}
