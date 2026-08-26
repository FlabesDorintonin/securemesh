#include <cassert>
#include <cstdio>
#include "../VanguardManifest.h"

int main() {
  using namespace VanguardManifest;
  uint32_t nodes[4]={0x10,0x20,0x30,0x40};
  Manifest<> a,b;
  assert(a.configure(7,nodes,4,0x20));
  assert(b.configure(7,nodes,4,0x40));
  assert(a.digest==b.digest);
  assert(a.slotFor(0x10)==0 && a.slotFor(0x40)==3);
  assert(a.bitFor(0x30)==(1u<<2));
  assert(a.sameNetwork(7,b.digest));

  uint32_t duplicate[3]={1,2,1};
  Manifest<> bad;
  assert(!bad.configure(1,duplicate,3,1));
  assert(!bad.valid);
  assert(!bad.configure(1,nodes,4,0x99));

  KnownRegistry<> reg;
  assert(reg.add(1));
  assert(reg.add(2));
  assert(!reg.add(1));
  for(uint32_t i=3;i<=5;++i) assert(reg.add(i));
  assert(reg.count==5);
  assert(!reg.add(6)); // no eviction: authenticated identities are not forgotten
  assert(reg.contains(1) && reg.contains(5));

  std::puts("VANGUARD manifest native tests: PASS");
}
