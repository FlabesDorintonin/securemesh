#pragma once
#include <stdint.h>
#include <stddef.h>

namespace VanguardProto {

enum class ControlType : uint8_t {
  Invalid = 0,
  RouteRequest = 1,
  RouteReply = 2,
  RouteError = 3
};

// v4 strengthens exact-path discovery:
// - RREQ carries an ordered NodeSlot vector plus accumulated discovery evidence.
// - RREP returns the selected ordered vector, so every relay can validate its
//   exact upstream/downstream for pathTag instead of trusting mutable reverse cache.
// - RREQ.excludedFirstHop still allows G2 to avoid a direct primary first hop.
// - RERR identifies the exact source path by (origin, originBootEpoch, pathTag).
constexpr uint8_t CONTROL_VERSION = 4;
constexpr uint8_t MAX_PATH_SLOTS = 8;
constexpr uint8_t FLAG_G2_PROBE = 0x01;
constexpr uint8_t FLAG_FORCE_FRESH_GENERATION = 0x02;
constexpr uint8_t FLAG_PATH_PINNED = 0x04;

constexpr size_t RREQ_LEN = 55;
constexpr size_t RREP_LEN = 59;
constexpr size_t RERR_LEN = 48;

inline void put16(uint8_t* b, size_t o, uint16_t v) {
  b[o] = static_cast<uint8_t>(v);
  b[o + 1] = static_cast<uint8_t>(v >> 8);
}
inline void put32(uint8_t* b, size_t o, uint32_t v) {
  b[o] = static_cast<uint8_t>(v);
  b[o + 1] = static_cast<uint8_t>(v >> 8);
  b[o + 2] = static_cast<uint8_t>(v >> 16);
  b[o + 3] = static_cast<uint8_t>(v >> 24);
}
inline uint16_t get16(const uint8_t* b, size_t o) {
  return static_cast<uint16_t>(b[o]) |
         (static_cast<uint16_t>(b[o + 1]) << 8);
}
inline uint32_t get32(const uint8_t* b, size_t o) {
  return static_cast<uint32_t>(b[o]) |
         (static_cast<uint32_t>(b[o + 1]) << 8) |
         (static_cast<uint32_t>(b[o + 2]) << 16) |
         (static_cast<uint32_t>(b[o + 3]) << 24);
}

struct RouteRequest {
  uint8_t flags = 0;
  uint8_t hopCount = 0;
  uint32_t requestId = 0;
  uint32_t origin = 0;
  uint32_t destination = 0;
  uint32_t networkEpoch = 0;
  uint32_t manifestDigest = 0;
  uint32_t originBootEpoch = 0;
  uint32_t avoidMask = 0;
  uint32_t pathMask = 0;
  uint32_t excludedFirstHop = 0;
  uint8_t pathSlotCount = 0;
  uint8_t pathSlots[MAX_PATH_SLOTS] {};
  uint32_t discoveryEcaQ16 = 0;
  uint16_t discoveryReliabilityQ15 = 32767;
};

struct RouteReply {
  uint8_t flags = 0;
  uint8_t hopCount = 0;
  uint32_t requestId = 0;          // also the pathTag for this discovered path
  uint32_t origin = 0;
  uint32_t destination = 0;
  uint32_t networkEpoch = 0;
  uint32_t manifestDigest = 0;
  uint32_t originBootEpoch = 0;
  uint32_t destinationBootEpoch = 0;
  uint32_t destinationRouteSeq = 0;
  uint32_t pathMask = 0;
  uint32_t ecaQ16 = 0;
  uint16_t reliabilityQ15 = 32767;
  uint32_t advertisedGuardRank = 0;
  uint8_t pathSlotCount = 0;
  uint8_t pathSlots[MAX_PATH_SLOTS] {};
};

struct RouteError {
  uint8_t flags = 0;
  uint8_t hopCount = 0;
  uint32_t failureEventId = 0;
  uint32_t origin = 0;
  uint32_t originBootEpoch = 0;
  uint32_t destination = 0;
  uint32_t pathTag = 0;
  uint32_t reporter = 0;
  uint32_t networkEpoch = 0;
  uint32_t manifestDigest = 0;
  uint32_t destinationBootEpoch = 0;
  uint32_t destinationRouteSeq = 0;
  uint32_t routeVersion = 0;
};

inline size_t encode(const RouteRequest& x, uint8_t* b, size_t capacity) {
  if (b == nullptr || capacity < RREQ_LEN) return 0;
  b[0] = static_cast<uint8_t>(ControlType::RouteRequest);
  b[1] = CONTROL_VERSION;
  b[2] = x.flags;
  b[3] = x.hopCount;
  put32(b, 4, x.requestId);
  put32(b, 8, x.origin);
  put32(b, 12, x.destination);
  put32(b, 16, x.networkEpoch);
  put32(b, 20, x.manifestDigest);
  put32(b, 24, x.originBootEpoch);
  put32(b, 28, x.avoidMask);
  put32(b, 32, x.pathMask);
  put32(b, 36, x.excludedFirstHop);
  if (x.pathSlotCount > MAX_PATH_SLOTS) return 0;
  b[40] = x.pathSlotCount;
  for (uint8_t i = 0; i < MAX_PATH_SLOTS; ++i) b[41 + i] = x.pathSlots[i];
  put32(b, 49, x.discoveryEcaQ16);
  put16(b, 53, x.discoveryReliabilityQ15);
  return RREQ_LEN;
}

inline bool decode(const uint8_t* b, size_t n, RouteRequest& x) {
  if (b == nullptr || n != RREQ_LEN ||
      b[0] != static_cast<uint8_t>(ControlType::RouteRequest) ||
      b[1] != CONTROL_VERSION) return false;
  x.flags = b[2];
  x.hopCount = b[3];
  x.requestId = get32(b, 4);
  x.origin = get32(b, 8);
  x.destination = get32(b, 12);
  x.networkEpoch = get32(b, 16);
  x.manifestDigest = get32(b, 20);
  x.originBootEpoch = get32(b, 24);
  x.avoidMask = get32(b, 28);
  x.pathMask = get32(b, 32);
  x.excludedFirstHop = get32(b, 36);
  x.pathSlotCount = b[40];
  if (x.pathSlotCount > MAX_PATH_SLOTS) return false;
  for (uint8_t i = 0; i < MAX_PATH_SLOTS; ++i) x.pathSlots[i] = b[41 + i];
  x.discoveryEcaQ16 = get32(b, 49);
  x.discoveryReliabilityQ15 = get16(b, 53);
  return x.requestId != 0 && x.origin != 0 && x.destination != 0 &&
         x.originBootEpoch != 0 && x.discoveryReliabilityQ15 != 0;
}

inline size_t encode(const RouteReply& x, uint8_t* b, size_t capacity) {
  if (b == nullptr || capacity < RREP_LEN) return 0;
  b[0] = static_cast<uint8_t>(ControlType::RouteReply);
  b[1] = CONTROL_VERSION;
  b[2] = x.flags;
  b[3] = x.hopCount;
  put32(b, 4, x.requestId);
  put32(b, 8, x.origin);
  put32(b, 12, x.destination);
  put32(b, 16, x.networkEpoch);
  put32(b, 20, x.manifestDigest);
  put32(b, 24, x.originBootEpoch);
  put32(b, 28, x.destinationBootEpoch);
  put32(b, 32, x.destinationRouteSeq);
  put32(b, 36, x.pathMask);
  put32(b, 40, x.ecaQ16);
  put16(b, 44, x.reliabilityQ15);
  put32(b, 46, x.advertisedGuardRank);
  if (x.pathSlotCount > MAX_PATH_SLOTS) return 0;
  b[50] = x.pathSlotCount;
  for (uint8_t i = 0; i < MAX_PATH_SLOTS; ++i) b[51 + i] = x.pathSlots[i];
  return RREP_LEN;
}

inline bool decode(const uint8_t* b, size_t n, RouteReply& x) {
  if (b == nullptr || n != RREP_LEN ||
      b[0] != static_cast<uint8_t>(ControlType::RouteReply) ||
      b[1] != CONTROL_VERSION) return false;
  x.flags = b[2];
  x.hopCount = b[3];
  x.requestId = get32(b, 4);
  x.origin = get32(b, 8);
  x.destination = get32(b, 12);
  x.networkEpoch = get32(b, 16);
  x.manifestDigest = get32(b, 20);
  x.originBootEpoch = get32(b, 24);
  x.destinationBootEpoch = get32(b, 28);
  x.destinationRouteSeq = get32(b, 32);
  x.pathMask = get32(b, 36);
  x.ecaQ16 = get32(b, 40);
  x.reliabilityQ15 = get16(b, 44);
  x.advertisedGuardRank = get32(b, 46);
  x.pathSlotCount = b[50];
  if (x.pathSlotCount > MAX_PATH_SLOTS) return false;
  for (uint8_t i = 0; i < MAX_PATH_SLOTS; ++i) x.pathSlots[i] = b[51 + i];
  return x.requestId != 0 && x.origin != 0 && x.destination != 0 &&
         x.originBootEpoch != 0 && x.destinationBootEpoch != 0 &&
         x.destinationRouteSeq != 0;
}

inline size_t encode(const RouteError& x, uint8_t* b, size_t capacity) {
  if (b == nullptr || capacity < RERR_LEN) return 0;
  b[0] = static_cast<uint8_t>(ControlType::RouteError);
  b[1] = CONTROL_VERSION;
  b[2] = x.flags;
  b[3] = x.hopCount;
  put32(b, 4, x.failureEventId);
  put32(b, 8, x.origin);
  put32(b, 12, x.originBootEpoch);
  put32(b, 16, x.destination);
  put32(b, 20, x.pathTag);
  put32(b, 24, x.reporter);
  put32(b, 28, x.networkEpoch);
  put32(b, 32, x.manifestDigest);
  put32(b, 36, x.destinationBootEpoch);
  put32(b, 40, x.destinationRouteSeq);
  put32(b, 44, x.routeVersion);
  return RERR_LEN;
}

inline bool decode(const uint8_t* b, size_t n, RouteError& x) {
  if (b == nullptr || n != RERR_LEN ||
      b[0] != static_cast<uint8_t>(ControlType::RouteError) ||
      b[1] != CONTROL_VERSION) return false;
  x.flags = b[2];
  x.hopCount = b[3];
  x.failureEventId = get32(b, 4);
  x.origin = get32(b, 8);
  x.originBootEpoch = get32(b, 12);
  x.destination = get32(b, 16);
  x.pathTag = get32(b, 20);
  x.reporter = get32(b, 24);
  x.networkEpoch = get32(b, 28);
  x.manifestDigest = get32(b, 32);
  x.destinationBootEpoch = get32(b, 36);
  x.destinationRouteSeq = get32(b, 40);
  x.routeVersion = get32(b, 44);
  return x.failureEventId != 0 && x.origin != 0 && x.originBootEpoch != 0 &&
         x.destination != 0 && x.reporter != 0;
}

inline ControlType typeOf(const uint8_t* b, size_t n) {
  if (b == nullptr || n < 2 || b[1] != CONTROL_VERSION) {
    return ControlType::Invalid;
  }
  const uint8_t raw = b[0];
  if (raw < static_cast<uint8_t>(ControlType::RouteRequest) ||
      raw > static_cast<uint8_t>(ControlType::RouteError)) {
    return ControlType::Invalid;
  }
  return static_cast<ControlType>(raw);
}

}  // namespace VanguardProto
