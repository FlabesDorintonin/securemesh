#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "tools/frontend_v1_0_4_gate.py"
text = GATE.read_text(encoding="utf-8")
legacy_450 = '    ok("Exact framebuffer refresh is screen-scoped", "delay(450L)" in control and "refreshMirror" in control)'
legacy_800 = '    ok("Exact framebuffer refresh is screen-scoped", "OLED_MIRROR_POLL_INTERVAL_MS = 800L" in control and "delay(OLED_MIRROR_POLL_INTERVAL_MS)" in control and "refreshMirror" in control)'
target = '    ok("Exact framebuffer refresh is screen-scoped", "OLED_MIRROR_POLL_INTERVAL_MS = 1500L" in control and "delay(OLED_MIRROR_POLL_INTERVAL_MS)" in control and "refreshMirror" in control)'

if target in text:
    print("frontend OLED polling gate already aligned to 1500 ms")
elif text.count(legacy_800) == 1:
    GATE.write_text(text.replace(legacy_800, target, 1), encoding="utf-8")
    print("frontend OLED polling gate aligned 800 -> 1500 ms")
elif text.count(legacy_450) == 1:
    GATE.write_text(text.replace(legacy_450, target, 1), encoding="utf-8")
    print("frontend OLED polling gate aligned 450 -> 1500 ms")
else:
    raise SystemExit("fail-closed: cannot align frontend OLED polling gate")
