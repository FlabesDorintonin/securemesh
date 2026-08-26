#include "../VanguardRuntime.h"
#include <cassert>
#include <cstdio>

using RT = VanguardRuntime::State<>;
using ENG = Vanguard::Engine<8>;

static VanguardRuntime::LinkMetric strongLink() {
  return {1u << 16, 32200};
}
static VanguardRuntime::LinkMetric weakLink() {
  return {4u << 16, 15000};
}
static VanguardProto::RouteRequest req(const VanguardRuntime::TxControl& tx) {
  VanguardProto::RouteRequest x; assert(VanguardProto::decode(tx.payload,tx.length,x)); return x;
}

int main() {
  // Three physical radios on one desk:
  // A-B and B-C normal; A-C remains physically usable but has a lab metric
  // override. The primary must be A-B-C and the direct A-C path must become G2.
  uint32_t ids[3] = {0xA,0xB,0xC};
  VanguardManifest::Manifest<> m;
  assert(m.configure(31, ids, 3, 0xA));

  ENG ea,eb,ec;
  ea.setIdentity(0xA,m.slotFor(0xA),31,true);
  eb.setIdentity(0xB,m.slotFor(0xB),31,true);
  ec.setIdentity(0xC,m.slotFor(0xC),31,true);
  RT ra,rb,rc; ra.reset(0xA,7); rb.reset(0xB,7); rc.reset(0xC,7);

  VanguardRuntime::TxControl q;
  VanguardRuntime::Event ev[8]{};
  assert(ra.beginDiscovery(0xC,false,0,0,m,q,nullptr,true));

  // Direct copy arrives first but is deliberately weak.
  VanguardRuntime::TxControl cDirect[8]{};
  size_t n=rc.onControl(0xA,q.hopLimit,q.payload,q.length,weakLink(),10,m,ec,cDirect,8,ev,8);
  assert(n==0);

  // B hears the same RREQ over a strong link and forwards it.
  VanguardRuntime::TxControl bFwd[8]{};
  n=rb.onControl(0xA,q.hopLimit,q.payload,q.length,strongLink(),12,m,eb,bFwd,8,ev,8);
  assert(n==1 && bFwd[0].valid);

  // C sees the strong two-hop candidate inside the same bounded settle window.
  VanguardRuntime::TxControl cViaB[8]{};
  n=rc.onControl(0xB,bFwd[0].hopLimit,bFwd[0].payload,bFwd[0].length,strongLink(),20,m,ec,cViaB,8,ev,8);
  assert(n==0);
  n=rc.tick(10 + VanguardRuntime::RREQ_SETTLE_MS,m,cViaB,8,ev,8);
  assert(n==1 && cViaB[0].nextHop==0xB);

  // RREP C->B->A installs the indirect primary.
  VanguardRuntime::TxControl bBack[8]{};
  n=rb.onControl(0xC,cViaB[0].hopLimit,cViaB[0].payload,cViaB[0].length,strongLink(),300,m,eb,bBack,8,ev,8);
  assert(n==1 && bBack[0].nextHop==0xA);
  VanguardRuntime::TxControl g2Start[8]{};
  n=ra.onControl(0xB,bBack[0].hopLimit,bBack[0].payload,bBack[0].length,strongLink(),400,m,ea,g2Start,8,ev,8);
  assert(n==1); // automatic G2 probe
  const auto g2=req(g2Start[0]);
  const auto* route=ea.find(0xC);
  assert(route && route->primary.valid && route->primary.nextHop==0xB);
  assert(route->primary.internalPathMask==m.bitFor(0xB));
  assert((g2.flags & VanguardProto::FLAG_G2_PROBE)!=0);
  assert(g2.excludedFirstHop==0xB);
  assert((g2.avoidMask & m.bitFor(0xB))!=0);

  // The direct A-C RF link is still alive, so it can answer the G2 probe.
  VanguardRuntime::TxControl directG2Reply[8]{};
  n=rc.onControl(0xA,g2Start[0].hopLimit,g2Start[0].payload,g2Start[0].length,weakLink(),500,m,ec,directG2Reply,8,ev,8);
  assert(n==0);
  n=rc.tick(500 + VanguardRuntime::RREQ_SETTLE_MS,m,directG2Reply,8,ev,8);
  assert(n==1 && directG2Reply[0].nextHop==0xA);
  VanguardRuntime::TxControl done[8]{};
  n=ra.onControl(0xC,directG2Reply[0].hopLimit,directG2Reply[0].payload,directG2Reply[0].length,weakLink(),800,m,ea,done,8,ev,8);
  assert(n==0);

  route=ea.find(0xC);
  assert(route && route->primary.nextHop==0xB);
  assert(route->backup.valid && route->backup.nextHop==0xC);
  assert((route->primary.internalPathMask & route->backup.internalPathMask)==0);
  assert(ea.hasExactG2(0xC));

  // Hard failure of A->B promotes the direct standby.
  bool promoted=false;
  assert(ea.onRouteFailure(0xC,0xB,900,&promoted));
  assert(promoted);
  uint32_t next=0,tag=0; bool fromBackup=false;
  assert(ea.resolve(0xC,901,next,fromBackup,&tag));
  assert(next==0xC && fromBackup);

  std::puts("VANGUARD three-radio lab scenario: PASS");
}
