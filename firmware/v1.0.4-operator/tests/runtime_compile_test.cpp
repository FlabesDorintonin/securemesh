#include "../VanguardRuntime.h"
int main(){
  uint32_t ids[3]={1,2,3};
  VanguardManifest::Manifest<> m; if(!m.configure(7,ids,3,1)) return 1;
  Vanguard::Engine<16> e; e.setIdentity(1,m.slotFor(1),m.networkEpoch,m.valid);
  VanguardRuntime::State<> rt; rt.reset(1,11);
  VanguardRuntime::TxControl tx; VanguardRuntime::Event ev;
  if(!rt.beginDiscovery(3,false,0,100,m,tx,&ev,false) || !tx.valid) return 2;
  return 0;
}
