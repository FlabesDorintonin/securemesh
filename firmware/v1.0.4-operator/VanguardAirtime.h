#pragma once
#include <stdint.h>
#include <stddef.h>

namespace VanguardAirtime {

struct RadioProfile {
  uint32_t bandwidthHz = 125000;
  uint8_t spreadingFactor = 9;
  uint8_t codingRateDenominator = 5; // 5 => LoRa CR 4/5
  uint16_t preambleSymbols = 12;
  bool crcEnabled = true;
  bool explicitHeader = true;
};

struct RoutingTimingRecommendation {
  bool valid = false;
  uint32_t rreqHopMs = 0;
  uint32_t reliableRrepHopMs = 0;
  uint32_t discoveryTimeoutMs = 0;
  uint32_t rreqSettleMs = 0;
  uint32_t retryExtraStepMs = 0;
  uint32_t refreshMinIntervalMs = 0;
  uint32_t ackTimeoutMs = 0;
};

inline uint32_t estimateLoRaAirtimeUs(size_t payloadBytes, const RadioProfile& p) {
  if (p.bandwidthHz == 0 || p.spreadingFactor < 5 || p.spreadingFactor > 12 ||
      p.codingRateDenominator < 5 || p.codingRateDenominator > 8) {
    return 0xFFFFFFFFUL;
  }

  const double symbolUs =
    (static_cast<double>(1UL << p.spreadingFactor) /
     static_cast<double>(p.bandwidthHz)) * 1000000.0;
  const int lowDataRateOptimize =
    (p.spreadingFactor >= 11 && p.bandwidthHz <= 125000UL) ? 1 : 0;
  const int numerator =
    8 * static_cast<int>(payloadBytes) -
    4 * static_cast<int>(p.spreadingFactor) + 28 +
    (p.crcEnabled ? 16 : 0) - (p.explicitHeader ? 0 : 20);
  const int denominator =
    4 * (static_cast<int>(p.spreadingFactor) - 2 * lowDataRateOptimize);

  int payloadBlocks = 0;
  if (numerator > 0 && denominator > 0) {
    payloadBlocks = (numerator + denominator - 1) / denominator;
  }
  const double payloadSymbols =
    8.0 + static_cast<double>(payloadBlocks * p.codingRateDenominator);
  const double totalSymbols =
    static_cast<double>(p.preambleSymbols) + 4.25 + payloadSymbols;
  const double airtimeUs = totalSymbols * symbolUs;

  if (airtimeUs <= 1.0) return 1;
  if (airtimeUs >= 4294967295.0) return 0xFFFFFFFFUL;
  return static_cast<uint32_t>(airtimeUs + 0.999);
}

inline RoutingTimingRecommendation deriveRoutingTiming(
  size_t rreqWireBytes,
  size_t rrepWireBytes,
  size_t ackWireBytes,
  uint8_t maxDiscoveryHops,
  const RadioProfile& profile,
  uint32_t ackTimeoutFloorMs = 1800u
) {
  RoutingTimingRecommendation out;
  if (maxDiscoveryHops == 0) return out;

  const uint32_t rreqUs = estimateLoRaAirtimeUs(rreqWireBytes, profile);
  const uint32_t rrepUs = estimateLoRaAirtimeUs(rrepWireBytes, profile);
  const uint32_t ackUs = estimateLoRaAirtimeUs(ackWireBytes, profile);
  if (rreqUs == UINT32_MAX || rrepUs == UINT32_MAX || ackUs == UINT32_MAX) return out;

  const auto ceilMs = [](uint32_t us) -> uint32_t { return (us + 999u) / 1000u; };
  // These guards are deliberately small compared with LoRa airtime. They
  // account for queue jitter, RX/TX switching and scheduler latency without
  // pretending that a multi-hop control exchange is faster than the PHY.
  constexpr uint32_t RREQ_SERVICE_GUARD_MS = 100u;
  constexpr uint32_t RREP_ACK_SERVICE_GUARD_MS = 180u;
  constexpr uint32_t SETTLE_EXTRA_MS = 250u;
  constexpr uint32_t DISCOVERY_FINAL_GUARD_MS = 1200u;
  constexpr uint32_t ACK_PROCESS_GUARD_MS = 700u;

  out.rreqHopMs = ceilMs(rreqUs) + RREQ_SERVICE_GUARD_MS;
  out.reliableRrepHopMs =
    ceilMs(rrepUs) + ceilMs(ackUs) + RREP_ACK_SERVICE_GUARD_MS;

  out.rreqSettleMs = out.rreqHopMs + SETTLE_EXTRA_MS;
  if (out.rreqSettleMs < 500u) out.rreqSettleMs = 500u;
  if (out.rreqSettleMs > 2200u) out.rreqSettleMs = 2200u;

  const uint64_t worstRoundTrip =
    static_cast<uint64_t>(maxDiscoveryHops) *
    static_cast<uint64_t>(out.rreqHopMs + out.reliableRrepHopMs);
  uint64_t timeout = worstRoundTrip + out.rreqSettleMs + DISCOVERY_FINAL_GUARD_MS;
  if (timeout < 3000u) timeout = 3000u;
  if (timeout > 60000u) timeout = 60000u;
  out.discoveryTimeoutMs = static_cast<uint32_t>(timeout);

  out.retryExtraStepMs = out.rreqHopMs / 2u;
  if (out.retryExtraStepMs < 250u) out.retryExtraStepMs = 250u;
  out.refreshMinIntervalMs = out.discoveryTimeoutMs;

  out.ackTimeoutMs = ceilMs(ackUs) + ACK_PROCESS_GUARD_MS;
  if (out.ackTimeoutMs < ackTimeoutFloorMs) out.ackTimeoutMs = ackTimeoutFloorMs;
  out.valid = true;
  return out;
}

class Bucket {
 public:
  Bucket(
    uint32_t capacityUs,
    uint32_t protectedReserveUs,
    uint32_t refillUsPerMs
  ) : capacityUs_(capacityUs),
      reserveUs_(protectedReserveUs > capacityUs ? capacityUs : protectedReserveUs),
      refillUsPerMs_(refillUsPerMs),
      tokensUs_(capacityUs) {}

  bool consume(uint32_t costUs, bool mayUseReserve, uint32_t nowMs) {
    refill(nowMs);
    if (costUs == 0 || costUs > capacityUs_) {
      drops_++;
      return false;
    }
    if (mayUseReserve) {
      if (tokensUs_ < costUs) {
        drops_++;
        return false;
      }
    } else {
      const uint64_t required = static_cast<uint64_t>(costUs) + reserveUs_;
      if (static_cast<uint64_t>(tokensUs_) < required) {
        drops_++;
        return false;
      }
    }
    tokensUs_ -= costUs;
    return true;
  }

  void refund(uint32_t costUs) {
    const uint64_t next = static_cast<uint64_t>(tokensUs_) + costUs;
    tokensUs_ = static_cast<uint32_t>(next > capacityUs_ ? capacityUs_ : next);
  }

  void refill(uint32_t nowMs) {
    if (!started_) {
      started_ = true;
      updatedAtMs_ = nowMs;
      return;
    }
    const uint32_t elapsedMs = nowMs - updatedAtMs_; // wrap-safe unsigned delta
    if (elapsedMs == 0) return;
    updatedAtMs_ = nowMs;
    const uint64_t refillAmount =
      static_cast<uint64_t>(elapsedMs) * refillUsPerMs_;
    const uint64_t next = static_cast<uint64_t>(tokensUs_) + refillAmount;
    tokensUs_ = static_cast<uint32_t>(next > capacityUs_ ? capacityUs_ : next);
  }

  void reset(uint32_t nowMs = 0) {
    tokensUs_ = capacityUs_;
    drops_ = 0;
    updatedAtMs_ = nowMs;
    started_ = nowMs != 0;
  }

  uint32_t tokensUs() const { return tokensUs_; }
  uint32_t capacityUs() const { return capacityUs_; }
  uint32_t reserveUs() const { return reserveUs_; }
  uint32_t drops() const { return drops_; }

 private:
  uint32_t capacityUs_ = 0;
  uint32_t reserveUs_ = 0;
  uint32_t refillUsPerMs_ = 0;
  uint32_t tokensUs_ = 0;
  uint32_t updatedAtMs_ = 0;
  uint32_t drops_ = 0;
  bool started_ = false;
};

} // namespace VanguardAirtime
