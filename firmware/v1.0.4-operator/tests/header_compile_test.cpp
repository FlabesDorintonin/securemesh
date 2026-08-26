#include "../VanguardManifest.h"
#include "../VanguardCore.h"
#include "../VanguardProtocol.h"
int main(){
  VanguardManifest::Manifest<> m; uint32_t ids[3]={1,2,3}; if(!m.configure(7,ids,3,1)) return 1;
  Vanguard::Engine<16> e; e.setIdentity(1,m.slotFor(1),m.networkEpoch,m.valid);
  Vanguard::Candidate c; c.destination=3;c.nextHop=2;c.generation={1,1};c.advertisedGuardRank=1;c.internalPathMask=m.bitFor(2);c.exactMask=true;c.ecaQ16=65536;c.reliabilityQ15=32000;c.hopCount=2;
  auto r=e.install(c,1); return r==Vanguard::InstallResult::InstalledPrimary?0:2;
}
