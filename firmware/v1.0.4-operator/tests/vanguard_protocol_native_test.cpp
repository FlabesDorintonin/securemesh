#include <cassert>
#include <cstdio>
#include <cstring>
#include "../VanguardProtocol.h"
using namespace VanguardProto;

int main() {
  uint8_t buf[64]{};

  RouteRequest q;
  q.flags=FLAG_G2_PROBE; q.hopCount=3; q.requestId=11; q.origin=0xA;
  q.destination=0xD; q.networkEpoch=9; q.manifestDigest=0x12345678;
  q.originBootEpoch=77; q.avoidMask=0x12; q.pathMask=0x24;
  q.excludedFirstHop=0xB; q.pathSlotCount=2; q.pathSlots[0]=2; q.pathSlots[1]=5;
  q.discoveryEcaQ16=0x00123456; q.discoveryReliabilityQ15=31000;
  assert(encode(q,buf,sizeof(buf))==RREQ_LEN);
  RouteRequest q2; assert(decode(buf,RREQ_LEN,q2));
  assert(q2.requestId==11 && q2.excludedFirstHop==0xB && q2.pathMask==0x24);
  assert(q2.pathSlotCount==2 && q2.pathSlots[0]==2 && q2.pathSlots[1]==5);
  assert(q2.discoveryEcaQ16==0x00123456 && q2.discoveryReliabilityQ15==31000);

  RouteReply p;
  p.flags=FLAG_G2_PROBE|FLAG_PATH_PINNED; p.hopCount=2; p.requestId=11;
  p.origin=0xA; p.destination=0xD; p.networkEpoch=9;
  p.manifestDigest=0x12345678; p.originBootEpoch=77;
  p.destinationBootEpoch=88; p.destinationRouteSeq=4; p.pathMask=0x44;
  p.ecaQ16=123456; p.reliabilityQ15=32000; p.advertisedGuardRank=3;
  p.pathSlotCount=2; p.pathSlots[0]=1; p.pathSlots[1]=3;
  assert(encode(p,buf,sizeof(buf))==RREP_LEN);
  RouteReply p2; assert(decode(buf,RREP_LEN,p2));
  assert(p2.originBootEpoch==77 && p2.destinationBootEpoch==88 &&
         p2.reliabilityQ15==32000 && p2.advertisedGuardRank==3);
  assert(p2.pathSlotCount==2 && p2.pathSlots[0]==1 && p2.pathSlots[1]==3);

  RouteError e;
  e.flags=FLAG_PATH_PINNED; e.hopCount=1; e.failureEventId=99;
  e.origin=0xA; e.originBootEpoch=77; e.destination=0xD;
  e.pathTag=11; e.reporter=0xB; e.networkEpoch=9;
  e.manifestDigest=0x12345678; e.destinationBootEpoch=88;
  e.destinationRouteSeq=4; e.routeVersion=6;
  assert(encode(e,buf,sizeof(buf))==RERR_LEN);
  RouteError e2; assert(decode(buf,RERR_LEN,e2));
  assert(e2.origin==0xA && e2.originBootEpoch==77 && e2.pathTag==11 &&
         e2.reporter==0xB && e2.routeVersion==6);

  buf[1]=CONTROL_VERSION-1;
  assert(typeOf(buf,RERR_LEN)==ControlType::Invalid);
  std::puts("VANGUARD protocol native tests: PASS");
}
