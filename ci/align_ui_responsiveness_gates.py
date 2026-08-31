#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "tools/frontend_v1_0_4_gate.py"
text = GATE.read_text(encoding="utf-8")
old = '    ok("Exact framebuffer refresh is screen-scoped", "delay(450L)" in control and "refreshMirror" in control)'
new = '    ok("Exact framebuffer refresh is screen-scoped", "OLED_MIRROR_POLL_INTERVAL_MS = 800L" in control and "delay(OLED_MIRROR_POLL_INTERVAL_MS)" in control and "refreshMirror" in control)'
if text.count(old) == 1:
    GATE.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("frontend OLED polling gate aligned")
elif old not in text and new in text:
    print("frontend OLED polling gate already aligned")
else:
    raise SystemExit("fail-closed: cannot align frontend OLED polling gate")
