#pragma once
#include <stdint.h>
#include <stddef.h>

namespace VanguardManifest {

constexpr size_t MAX_SLOTS = 5;
constexpr uint8_t INVALID_SLOT = 0xFF;

inline uint32_t fnv1a32(const uint8_t* data, size_t n, uint32_t h = 2166136261u) {
  if (!data && n) return 0;
  for (size_t i = 0; i < n; ++i) { h ^= data[i]; h *= 16777619u; }
  return h;
}

inline uint32_t mixU32(uint32_t h, uint32_t v) {
  uint8_t b[4] = {uint8_t(v), uint8_t(v >> 8), uint8_t(v >> 16), uint8_t(v >> 24)};
  return fnv1a32(b, sizeof(b), h);
}

template <size_t N = MAX_SLOTS>
struct Manifest {
  static_assert(N <= 32, "path mask supports at most 32 slots");
  uint32_t networkEpoch = 0;
  uint32_t nodeBySlot[N] {};
  uint8_t count = 0;
  uint32_t digest = 0;
  bool valid = false;

  void clear() { *this = Manifest{}; }

  bool configure(uint32_t epoch, const uint32_t* nodes, size_t nodeCount, uint32_t localNode = 0) {
    clear();
    if (epoch == 0 || !nodes || nodeCount == 0 || nodeCount > N) return false;
    bool localPresent = (localNode == 0);
    for (size_t i = 0; i < nodeCount; ++i) {
      const uint32_t id = nodes[i];
      if (id == 0 || id == 0xFFFFFFFFu) return false;
      for (size_t j = 0; j < i; ++j) if (nodes[j] == id) return false;
      nodeBySlot[i] = id;
      if (id == localNode) localPresent = true;
    }
    if (!localPresent) { clear(); return false; }
    networkEpoch = epoch;
    count = static_cast<uint8_t>(nodeCount);
    uint32_t h = 2166136261u;
    h = mixU32(h, networkEpoch);
    h = mixU32(h, count);
    for (size_t i = 0; i < N; ++i) h = mixU32(h, nodeBySlot[i]);
    digest = h ? h : 1u;
    valid = true;
    return true;
  }

  uint8_t slotFor(uint32_t nodeId) const {
    if (!valid || nodeId == 0) return INVALID_SLOT;
    for (size_t i = 0; i < count; ++i) if (nodeBySlot[i] == nodeId) return static_cast<uint8_t>(i);
    return INVALID_SLOT;
  }

  uint32_t nodeFor(uint8_t slot) const {
    return valid && slot < count ? nodeBySlot[slot] : 0u;
  }

  uint32_t bitFor(uint32_t nodeId) const {
    const uint8_t s = slotFor(nodeId);
    return s < 32 ? (1u << s) : 0u;
  }

  bool sameNetwork(uint32_t epoch, uint32_t remoteDigest) const {
    return valid && epoch == networkEpoch && remoteDigest == digest;
  }
};

template <size_t N = MAX_SLOTS>
struct KnownRegistry {
  uint32_t nodes[N] {};
  uint8_t count = 0;

  bool contains(uint32_t id) const {
    for (size_t i = 0; i < count; ++i) if (nodes[i] == id) return true;
    return false;
  }

  bool add(uint32_t id) {
    if (id == 0 || id == 0xFFFFFFFFu || contains(id) || count >= N) return false;
    nodes[count++] = id;
    return true;
  }

  uint32_t digest() const {
    uint32_t h = mixU32(2166136261u, count);
    for (size_t i = 0; i < count; ++i) h = mixU32(h, nodes[i]);
    return h ? h : 1u;
  }
};

} // namespace VanguardManifest
