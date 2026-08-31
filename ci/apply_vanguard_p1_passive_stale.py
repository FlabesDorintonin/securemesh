#!/usr/bin/env python3
"""Materialize the narrow VANGUARD P1 passive-stale integration candidate.

Fail-closed transformation from the exact v1.0.4 source shape. The script is
idempotent and intentionally changes only processNeighborLifecycleEvents().
"""
from pathlib import Path

PATH = Path("firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino")
OLD = '''void processNeighborLifecycleEvents() {
  const uint32_t now = millis();
  if (!timeReached(now, nextNeighborLifecycleAtMs)) return;
  nextNeighborLifecycleAtMs = now + 1000;
  for (size_t i = 0; i < MAX_NEIGHBORS; ++i) {
    const bool fresh = neighbors[i].used && now - neighbors[i].lastSeenAtMs <= NEIGHBOR_STALE_MS;
    if (fresh == neighborLifecycleFresh[i]) continue;
    neighborLifecycleFresh[i] = fresh;
    if (!neighbors[i].used) continue;
    uint8_t event[4];
    writeU32(event, 0, neighbors[i].nodeId);
    emitBleEvent(fresh ? EVT_NODE_DISCOVERED : EVT_NODE_STALE, event, sizeof(event));
  }
}
'''
NEW = '''void processNeighborLifecycleEvents() {
  const uint32_t now = millis();
  if (!timeReached(now, nextNeighborLifecycleAtMs)) return;
  nextNeighborLifecycleAtMs = now + 1000;
  for (size_t i = 0; i < MAX_NEIGHBORS; ++i) {
    const bool fresh = neighbors[i].used && now - neighbors[i].lastSeenAtMs <= NEIGHBOR_STALE_MS;
    const bool wasFresh = neighborLifecycleFresh[i];
    if (fresh == wasFresh) continue;
    neighborLifecycleFresh[i] = fresh;
    if (!neighbors[i].used) continue;
    uint8_t event[4];
    writeU32(event, 0, neighbors[i].nodeId);
    emitBleEvent(fresh ? EVT_NODE_DISCOVERED : EVT_NODE_STALE, event, sizeof(event));

    // P1 passive-stale integration: a real Fresh -> Stale edge must enter the
    // same bounded VANGUARD failure ladder as explicit hop-ACK exhaustion.
    // This is edge-triggered by neighborLifecycleFresh, so a stale neighbor
    // cannot generate one repair event on every lifecycle tick.
    if (wasFresh && !fresh) {
      notifyVanguardHopFailure(neighbors[i].nodeId);
    }
  }
}
'''

text = PATH.read_text(encoding="utf-8")
if NEW in text:
    print("passive-stale candidate already materialized")
    raise SystemExit(0)
if text.count(OLD) != 1:
    raise SystemExit(f"FAIL-CLOSED: expected exactly one lifecycle block, found {text.count(OLD)}")
PATH.write_text(text.replace(OLD, NEW), encoding="utf-8")
print("materialized VANGUARD P1 passive-stale candidate")
