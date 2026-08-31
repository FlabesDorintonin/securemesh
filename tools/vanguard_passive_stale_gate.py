#!/usr/bin/env python3
"""Fail-closed source gate for the VANGUARD passive-stale P1 candidate."""
from pathlib import Path
import re

path = Path("firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino")
text = path.read_text(encoding="utf-8")

required = [
    "const bool wasFresh = neighborLifecycleFresh[i];",
    "if (fresh == wasFresh) continue;",
    "if (wasFresh && !fresh) {",
    "notifyVanguardHopFailure(neighbors[i].nodeId);",
]
for token in required:
    if text.count(token) != 1:
        raise SystemExit(f"FAIL: expected exactly one `{token}`, found {text.count(token)}")

# The VANGUARD notification must be inside the explicit Fresh->Stale guard,
# never on every stale lifecycle tick and never on Fresh recovery.
pattern = re.compile(
    r"if \(wasFresh && !fresh\) \{\s*"
    r"notifyVanguardHopFailure\(neighbors\[i\]\.nodeId\);\s*\}",
    re.S,
)
if not pattern.search(text):
    raise SystemExit("FAIL: stale notification is not structurally guarded by Fresh->Stale edge")

lifecycle_start = text.find("void processNeighborLifecycleEvents()")
if lifecycle_start < 0:
    raise SystemExit("FAIL: lifecycle function missing")
lifecycle_end = text.find("\n}\n", lifecycle_start)
if lifecycle_end < 0:
    raise SystemExit("FAIL: lifecycle function end missing")
block = text[lifecycle_start:lifecycle_end + 3]
if block.count("notifyVanguardHopFailure(") != 1:
    raise SystemExit("FAIL: lifecycle block must contain exactly one VANGUARD failure notification")

# Existing hard-TX paths must still exist. This candidate unifies detection by
# feeding the same runtime API; it must not replace/remove hard-failure handling.
if text.count("notifyVanguardHopFailure(failedNeighbor);") < 1:
    raise SystemExit("FAIL: hard TX failure notification path disappeared")
if "vanguardRuntime.onLocalHopFailure(" not in text:
    raise SystemExit("FAIL: bounded VANGUARD failure ladder API missing")

print("VANGUARD passive-stale integration gate: PASS")
