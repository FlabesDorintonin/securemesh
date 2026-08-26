#include <cassert>
#include <cstdio>
#include "../VanguardCore.h"
using namespace Vanguard;

static Candidate c(
  uint32_t d, uint32_t nh, uint32_t boot, uint32_t seq, uint32_t rank,
  uint32_t mask, bool exact, uint32_t cost, uint16_t rel, uint8_t hops,
  uint32_t tag = 0
) {
  Candidate x;
  x.destination=d; x.nextHop=nh; x.generation={boot,seq};
  x.advertisedGuardRank=rank; x.internalPathMask=mask; x.exactMask=exact;
  x.ecaQ16=cost; x.reliabilityQ15=rel; x.hopCount=hops;
  x.learnedAtMs=100; x.pathTag=tag;
  return x;
}

int main() {
  Engine<8> e;
  e.setIdentity(1, 0, 7, true);

  // First path: advertised=4 -> local rank and FD become 5.
  assert(e.install(c(9,2,10,1,4,1u<<1,true,1000,32000,2,101),100)
         == InstallResult::InstalledPrimary);
  const auto* r=e.find(9);
  assert(r && r->feasibleDistance==5 && r->guardRank==5);

  // Equal advertised rank via another neighbour is feasible because 4 < FD 5.
  assert(e.install(c(9,3,10,1,4,1u<<2,true,1100,31900,2,102),110,true)
         == InstallResult::InstalledBackup);
  assert(e.hasExactG2(9));

  // Same-generation candidate at/above FD is rejected.
  assert(e.install(c(9,4,10,1,5,1u<<3,true,800,32600,2,103),120)
         == InstallResult::RejectedInfeasible);

  // A G2 probe may never mutate the exact same first hop as primary.
  assert(e.install(c(9,2,10,1,4,1u<<1,true,700,32700,2,104),130,true)
         == InstallResult::RejectedSamePath);

  // Older generation cannot roll routing state back.
  assert(e.install(c(9,4,9,99,1,1u<<3,true,500,32700,1,105),140)
         == InstallResult::RejectedOldGeneration);

  // New generation resets FD. Then a better primary lowers FD and any standby
  // that is no longer strictly feasible must be removed.
  assert(e.install(c(9,4,10,2,7,1u<<3,true,1200,31000,3,201),150)
         == InstallResult::InstalledPrimary);
  assert(e.install(c(9,5,10,2,6,1u<<4,true,1300,30900,3,0),160,true)
         == InstallResult::InstalledBackup);
  assert(e.install(c(9,6,10,2,5,1u<<5,true,500,32700,2,203),170)
         == InstallResult::InstalledPrimary);
  r=e.find(9);
  assert(r && r->feasibleDistance==6 && r->primary.nextHop==6);
  assert(!r->backup.valid || r->backup.advertisedGuardRank < r->feasibleDistance);
  assert(!r->alternate.valid || r->alternate.advertisedGuardRank < r->feasibleDistance);

  // Install a fresh exact G2 and fail primary by path label.
  assert(e.install(c(9,7,10,2,5,1u<<6,true,700,32500,2,204),180,true)
         == InstallResult::InstalledBackup);
  assert(e.hasExactG2(9));
  bool promoted=false;
  assert(e.onPathTagFailure(9,203,190,&promoted));
  assert(promoted);
  uint32_t nh=0, tag=0; bool fromBackup=false;
  assert(e.resolve(9,191,nh,fromBackup,&tag));
  assert(nh==7 && tag==204 && fromBackup);

  // Local slot inside an exact internal path mask is an explicit loop reject.
  assert(e.install(c(8,5,1,1,1,1u<<0,true,10,32700,1,301),200)
         == InstallResult::RejectedLoop);

  // Serial arithmetic handles wrap in the allowed half-range model.
  assert(compareGeneration({5,0},{5,0xFFFFFFFFu})>0);

  std::puts("VANGUARD core native tests: PASS");
}
