#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"

checks = []

def require(name: str, cond: bool):
    checks.append((name, cond))

vm = VM.read_text(encoding="utf-8")
fw = FW.read_text(encoding="utf-8")

require("Android remote UI mutex exists", "private val remoteUiMutex = Mutex()" in vm)
require("Mirror poll yields while action busy", "busy.value ||" in vm and "mirrorBusy.value ||" in vm)
require("Mirror snapshot transaction is serialized", "remoteUiMutex.withLock { repository.refreshOledFramebuffer() }" in vm)
require("UI action transaction is serialized", "remoteUiMutex.withLock {" in vm and "repository.sendDeviceUiAction(action)" in vm)
require("UI action waits for firmware redraw", "delay(75L)" in vm)
require("UI action refreshes exact framebuffer after ACK", "repository.refreshOledFramebuffer()" in vm)

require("Firmware remote action commit helper exists", "void uiCommitRemoteAction()" in fw)
require("Firmware cancels stale connected banner", "bleConnectedBannerUntilMs = 0;" in fw)
require("Firmware invalidates OLED snapshot after remote action", "bleOledSnapshotValid = false;" in fw)
require("Firmware schedules immediate redraw", "uiState.nextFrameAtMs = millis();" in fw)

handler_match = re.search(
    r"bool uiHandleRemoteAction\(uint8_t rawAction\) \{(?P<body>.*?)\n\}\nuint16_t buildUiStatePayload",
    fw,
    flags=re.S,
)
require("Firmware UI handler found", handler_match is not None)
if handler_match:
    body = handler_match.group("body")
    require("All accepted UI scene paths commit remote action", body.count("uiCommitRemoteAction();") >= 4)
    require("Remote handler no longer emits stale state directly", "uiEmitStateChanged();" not in body)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")

if failed:
    print(f"\n{len(checks) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1)
print(f"\n{len(checks)} passed, 0 failed")
