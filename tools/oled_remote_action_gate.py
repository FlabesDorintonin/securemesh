#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"
SCREEN = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"

checks = []

def require(name: str, cond: bool):
    checks.append((name, cond))

vm = VM.read_text(encoding="utf-8")
screen = SCREEN.read_text(encoding="utf-8")
fw = FW.read_text(encoding="utf-8")

# The v2 interaction model intentionally removes the old global remoteUiMutex:
# immutable framebuffer snapshots can interleave with UI_ACTION, so mirror
# traffic must never head-of-line block the remote control.
require("Android bounded UI action queue exists", "private val actionQueue = Channel<DeviceUiAction>(capacity = 16)" in vm)
require("Queued UI actions have one sequential consumer", "for (action in actionQueue) processQueuedAction(action)" in vm)
require("Mirror poll yields while action busy", "busy.value ||" in vm and "mirrorBusy.value ||" in vm)
require("Mirror no longer holds the old global action mutex", "remoteUiMutex" not in vm)
require("UI action is not followed synchronously by four framebuffer commands", "repository.refreshOledFramebuffer()" not in vm[vm.find("fun action(action"):vm.find("fun clearError()")])
require("UI action waits for firmware redraw before coalesced mirror", "delay(75L)" in vm)
require("UI action coalesces exact mirror refresh", "actionMirrorJob?.cancel()" in vm and "refreshMirror()" in vm)
require("Remote buttons stay enabled while queue drains", "enabled = true" in screen[screen.find("private fun RemoteButton("):screen.find("private fun LiveTelemetryCard")])

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
