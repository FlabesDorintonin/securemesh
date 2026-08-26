#include "../VanguardRuntime.h"
#include <cassert>
#include <cstdio>

using RT = VanguardRuntime::State<>;
using ENG = Vanguard::Engine<16>;

static VanguardRuntime::LinkMetric link() {
  return {1u << 16, 32000};
}

static VanguardProto::RouteRequest decodeReq(const VanguardRuntime::TxControl& tx) {
  VanguardProto::RouteRequest q;
  assert(VanguardProto::decode(tx.payload, tx.length, q));
  return q;
}

static VanguardProto::RouteReply decodeRep(const VanguardRuntime::TxControl& tx) {
  VanguardProto::RouteReply r;
  assert(VanguardProto::decode(tx.payload, tx.length, r));
  return r;
}

static int findType(
  const VanguardRuntime::TxControl* tx,
  size_t count,
  VanguardProto::ControlType type,
  uint32_t nextHop = 0
) {
  for (size_t i = 0; i < count; ++i) {
    if (!tx[i].valid) continue;
    if (VanguardProto::typeOf(tx[i].payload, tx[i].length) != type) continue;
    if (nextHop != 0 && tx[i].nextHop != nextHop) continue;
    return static_cast<int>(i);
  }
  return -1;
}

static void testDiamondPinnedG2AndRerr() {
  uint32_t ids[4] = {0xA,0xB,0xC,0xD};
  VanguardManifest::Manifest<> m;
  assert(m.configure(9, ids, 4, 0xA));

  ENG ea, eb, ec, ed;
  ea.setIdentity(0xA,m.slotFor(0xA),9,true);
  eb.setIdentity(0xB,m.slotFor(0xB),9,true);
  ec.setIdentity(0xC,m.slotFor(0xC),9,true);
  ed.setIdentity(0xD,m.slotFor(0xD),9,true);

  RT ra, rb, rc, rd;
  ra.reset(0xA,1); rb.reset(0xB,1); rc.reset(0xC,1); rd.reset(0xD,1);

  VanguardRuntime::TxControl initial;
  VanguardRuntime::Event oneEvent{};
  assert(ra.beginDiscovery(0xD,false,0,0,m,initial,&oneEvent));
  const auto primaryReq = decodeReq(initial);
  const uint32_t primaryTag = primaryReq.requestId;

  VanguardRuntime::Event ev[8]{};
  VanguardRuntime::TxControl bOut[8]{};
  size_t n=rb.onControl(0xA,initial.hopLimit,initial.payload,initial.length,
                        link(),10,m,eb,bOut,8,ev,8);
  assert(n==1);

  VanguardRuntime::TxControl dOut[8]{};
  n=rd.onControl(0xB,bOut[0].hopLimit,bOut[0].payload,bOut[0].length,
                 link(),20,m,ed,dOut,8,ev,8);
  assert(n==0);
  n=rd.tick(20 + VanguardRuntime::RREQ_SETTLE_MS,m,dOut,8,ev,8);
  assert(n==1 && dOut[0].nextHop==0xB);
  const auto primaryRepAtD = decodeRep(dOut[0]);
  assert(primaryRepAtD.requestId==primaryTag &&
         primaryRepAtD.originBootEpoch==1);

  VanguardRuntime::TxControl bRep[8]{};
  n=rb.onControl(0xD,dOut[0].hopLimit,dOut[0].payload,dOut[0].length,
                 link(),30,m,eb,bRep,8,ev,8);
  assert(n==1 && bRep[0].nextHop==0xA);

  // B has a pinned forwarding label for A's pathTag: A -> B -> D.
  uint32_t pinnedNext=0;
  assert(rb.resolveFlowLabel(0xA,1,0xD,primaryTag,0xA,31,pinnedNext));
  assert(pinnedNext==0xD);

  VanguardRuntime::TxControl aRep[8]{};
  n=ra.onControl(0xB,bRep[0].hopLimit,bRep[0].payload,bRep[0].length,
                 link(),40,m,ea,aRep,8,ev,8);
  assert(n==1); // automatic G2 discovery
  const auto g2Req = decodeReq(aRep[0]);
  assert((g2Req.flags & VanguardProto::FLAG_G2_PROBE)!=0);
  assert((g2Req.avoidMask & m.bitFor(0xB))!=0);
  assert(g2Req.excludedFirstHop==0xB);

  const auto* ar=ea.find(0xD);
  assert(ar && ar->primary.valid && ar->primary.nextHop==0xB);
  assert(ar->primary.pathTag==primaryTag);
  assert(ar->primary.internalPathMask==m.bitFor(0xB));

  // B is explicitly forbidden by the G2 avoid mask.
  VanguardRuntime::TxControl shouldDrop[8]{};
  n=rb.onControl(0xA,aRep[0].hopLimit,aRep[0].payload,aRep[0].length,
                 link(),45,m,eb,shouldDrop,8,ev,8);
  assert(n==0);

  VanguardRuntime::TxControl cOut[8]{};
  n=rc.onControl(0xA,aRep[0].hopLimit,aRep[0].payload,aRep[0].length,
                 link(),50,m,ec,cOut,8,ev,8);
  assert(n==1);

  VanguardRuntime::TxControl dOut2[8]{};
  n=rd.onControl(0xC,cOut[0].hopLimit,cOut[0].payload,cOut[0].length,
                 link(),60,m,ed,dOut2,8,ev,8);
  assert(n==0);
  n=rd.tick(60 + VanguardRuntime::RREQ_SETTLE_MS,m,dOut2,8,ev,8);
  assert(n==1 && dOut2[0].nextHop==0xC);

  VanguardRuntime::TxControl cRep[8]{};
  n=rc.onControl(0xD,dOut2[0].hopLimit,dOut2[0].payload,dOut2[0].length,
                 link(),70,m,ec,cRep,8,ev,8);
  assert(n==1 && cRep[0].nextHop==0xA);
  const uint32_t g2Tag = decodeRep(cRep[0]).requestId;
  assert(g2Tag==g2Req.requestId && g2Tag!=primaryTag);

  assert(rc.resolveFlowLabel(0xA,1,0xD,g2Tag,0xA,71,pinnedNext));
  assert(pinnedNext==0xD);

  VanguardRuntime::TxControl done[8]{};
  n=ra.onControl(0xC,cRep[0].hopLimit,cRep[0].payload,cRep[0].length,
                 link(),80,m,ea,done,8,ev,8);
  assert(n==0);

  ar=ea.find(0xD);
  assert(ar && ar->backup.valid && ar->backup.nextHop==0xC);
  assert(ar->backup.pathTag==g2Tag);
  assert(ea.hasExactG2(0xD));

  // Now physically break B->D. B must notify A about the exact primary tag
  // even if B could locally find another destination route.
  VanguardRuntime::TxControl failureOut[8]{};
  VanguardRuntime::Event failureEvents[8]{};
  n=rb.onLocalHopFailure(0xD,90,m,eb,failureOut,8,failureEvents,8);
  const int rerrIndex=findType(
    failureOut,n,VanguardProto::ControlType::RouteError,0xA);
  assert(rerrIndex>=0);
  VanguardProto::RouteError err;
  assert(VanguardProto::decode(
    failureOut[rerrIndex].payload,failureOut[rerrIndex].length,err));
  assert(err.origin==0xA && err.pathTag==primaryTag && err.destination==0xD);

  VanguardRuntime::TxControl sourceRecovery[8]{};
  VanguardRuntime::Event sourceEvents[8]{};
  n=ra.onControl(0xB,failureOut[rerrIndex].hopLimit,
                 failureOut[rerrIndex].payload,failureOut[rerrIndex].length,
                 link(),100,m,ea,sourceRecovery,8,sourceEvents,8);
  (void)n;
  ar=ea.find(0xD);
  assert(ar && ar->primary.valid && ar->primary.nextHop==0xC);
  assert(ar->primary.pathTag==g2Tag);
}

static void testDirectPrimaryStillFindsDifferentG2() {
  uint32_t ids[3] = {0xA,0xC,0xD};
  VanguardManifest::Manifest<> m;
  assert(m.configure(21,ids,3,0xA));

  ENG ea,ec,ed;
  ea.setIdentity(0xA,m.slotFor(0xA),21,true);
  ec.setIdentity(0xC,m.slotFor(0xC),21,true);
  ed.setIdentity(0xD,m.slotFor(0xD),21,true);
  RT ra,rc,rd; ra.reset(0xA,5); rc.reset(0xC,5); rd.reset(0xD,5);

  VanguardRuntime::TxControl q;
  VanguardRuntime::Event ev[8]{};
  assert(ra.beginDiscovery(0xD,false,0,0,m,q,nullptr));

  // Destination receives primary request directly and answers A.
  VanguardRuntime::TxControl directReply[8]{};
  size_t n=rd.onControl(0xA,q.hopLimit,q.payload,q.length,link(),10,m,ed,
                        directReply,8,ev,8);
  assert(n==0);
  n=rd.tick(10 + VanguardRuntime::RREQ_SETTLE_MS,m,directReply,8,ev,8);
  assert(n==1 && directReply[0].nextHop==0xA);

  VanguardRuntime::TxControl g2Start[8]{};
  n=ra.onControl(0xD,directReply[0].hopLimit,directReply[0].payload,
                 directReply[0].length,link(),20,m,ea,g2Start,8,ev,8);
  assert(n==1);
  const auto g2=decodeReq(g2Start[0]);
  assert(g2.excludedFirstHop==0xD);
  assert(g2.avoidMask==0); // direct route has no internal nodes

  // Direct copy is deliberately ignored by D for this G2 request.
  VanguardRuntime::TxControl ignored[8]{};
  n=rd.onControl(0xA,g2Start[0].hopLimit,g2Start[0].payload,g2Start[0].length,
                 link(),30,m,ed,ignored,8,ev,8);
  assert(n==0);

  // The same request through C is valid and D replies through C.
  VanguardRuntime::TxControl cFwd[8]{};
  n=rc.onControl(0xA,g2Start[0].hopLimit,g2Start[0].payload,g2Start[0].length,
                 link(),40,m,ec,cFwd,8,ev,8);
  assert(n==1);
  VanguardRuntime::TxControl dAlt[8]{};
  n=rd.onControl(0xC,cFwd[0].hopLimit,cFwd[0].payload,cFwd[0].length,
                 link(),50,m,ed,dAlt,8,ev,8);
  assert(n==0);
  n=rd.tick(50 + VanguardRuntime::RREQ_SETTLE_MS,m,dAlt,8,ev,8);
  assert(n==1 && dAlt[0].nextHop==0xC);
  VanguardRuntime::TxControl cBack[8]{};
  n=rc.onControl(0xD,dAlt[0].hopLimit,dAlt[0].payload,dAlt[0].length,
                 link(),60,m,ec,cBack,8,ev,8);
  assert(n==1 && cBack[0].nextHop==0xA);
  VanguardRuntime::TxControl done[8]{};
  n=ra.onControl(0xC,cBack[0].hopLimit,cBack[0].payload,cBack[0].length,
                 link(),70,m,ea,done,8,ev,8);
  assert(n==0);

  const auto* route=ea.find(0xD);
  assert(route && route->primary.nextHop==0xD && route->backup.nextHop==0xC);
  assert(ea.hasExactG2(0xD));
}

int main() {
  testDiamondPinnedG2AndRerr();
  testDirectPrimaryStillFindsDifferentG2();
  std::puts("VANGUARD runtime tests: PASS");
}
