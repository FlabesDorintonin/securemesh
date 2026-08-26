#include <assert.h>
#include <stdio.h>
#include "../VanguardAirtime.h"

int main() {
  VanguardAirtime::RadioProfile p;
  p.bandwidthHz = 125000;
  p.spreadingFactor = 9;
  p.codingRateDenominator = 5;
  p.preambleSymbols = 12;

  const uint32_t shortUs = VanguardAirtime::estimateLoRaAirtimeUs(20, p);
  const uint32_t longUs = VanguardAirtime::estimateLoRaAirtimeUs(120, p);
  assert(shortUs > 0 && shortUs < 1000000UL);
  assert(longUs > shortUs);

  const auto timing = VanguardAirtime::deriveRoutingTiming(
    103, 107, 60, 8, p, 1800);
  assert(timing.valid);
  assert(timing.rreqHopMs == 691);
  assert(timing.reliableRrepHopMs == 1158);
  assert(timing.rreqSettleMs == 941);
  assert(timing.discoveryTimeoutMs == 16933);
  assert(timing.retryExtraStepMs == 345);
  assert(timing.refreshMinIntervalMs == 16933);
  assert(timing.ackTimeoutMs == 1800);

  VanguardAirtime::Bucket bucket(1500000UL, 350000UL, 150UL);
  assert(bucket.tokensUs() == 1500000UL);
  assert(bucket.consume(1100000UL, false, 1000));
  // Only 400 ms remain: normal traffic cannot eat the 350 ms repair reserve.
  assert(!bucket.consume(100000UL, false, 1000));
  // Repair may consume the protected reserve.
  assert(bucket.consume(100000UL, true, 1000));
  assert(bucket.drops() == 1);
  // 1 second refills 150 ms airtime.
  bucket.refill(2000);
  assert(bucket.tokensUs() == 450000UL);
  bucket.refund(50000UL);
  assert(bucket.tokensUs() == 500000UL);

  printf("VANGUARD airtime native tests: PASS\n");
  return 0;
}
