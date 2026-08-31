#!/usr/bin/env python3
from pathlib import Path

# Revision 2: paired with queued-action OLED remote regression checks.
ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"
SCREEN = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
TRANSPORT = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/data/ble/BleTransport.kt"
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"

fw = FW.read_text(encoding="utf-8")
screen = SCREEN.read_text(encoding="utf-8")
transport = TRANSPORT.read_text(encoding="utf-8")
vm = VM.read_text(encoding="utf-8")

required_fw = [
    "constexpr uint32_t RADIO_RETRY_MAX_MS = 30000;",
    "uint32_t radioRetryDelayMs = RADIO_RETRY_MS;",
    "if (now - lastRadioRetryAtMs < radioRetryDelayMs) return;",
    "radioRetryDelayMs = RADIO_RETRY_MS;",
    "radioRetryDelayMs = min(doubled, RADIO_RETRY_MAX_MS);",
    "detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));",
    "constexpr uint32_t OLED_REFRESH_MS = 650;",
    "constexpr uint32_t UI_CRITICAL_FRAME_MS = 250;",
    "constexpr uint32_t UI_CONNECTED_ANIMATION_MS = 600;",
    "const bool toastVisible =",
    "const bool toastAnimating = toastVisible &&",
    "const bool connectedBannerAnimating = connectedBanner &&",
]
for marker in required_fw:
    assert marker in fw, f"missing firmware responsiveness marker: {marker}"

assert "if (millis() - lastRadioRetryAtMs < RADIO_RETRY_MS) return;" not in fw, \
    "fixed 3-second radio recovery retry still present"
assert "const bool toastAnimating = uiState.toastUntilMs != 0" not in fw, \
    "full-toast high-rate redraw still present"
assert "!uiState.bootFinished || pairing || connectedBanner || cursorAnimating" not in fw, \
    "full connected-banner high-rate redraw still present"

required_android = [
    "private const val OLED_MIRROR_POLL_INTERVAL_MS = 1500L",
    "delay(OLED_MIRROR_POLL_INTERVAL_MS)",
    "var frameBytes: ByteArray? = null",
    "chunk.data.copyInto(target, destinationOffset = writeOffset)",
    "require(writeOffset == completeFrame.size)",
]
for marker in required_android:
    assert marker in screen or marker in transport, f"missing Android responsiveness marker: {marker}"

assert "delay(450L)" not in screen, "old 450 ms OLED mirror polling remains"
assert "OLED_MIRROR_POLL_INTERVAL_MS = 800L" not in screen, "old 800 ms fallback mirror polling remains"
assert "chunks.fold(ByteArray(0))" not in transport, "quadratic framebuffer concatenation remains"

# Preserve exactness without forcing UI_ACTION to wait behind framebuffer traffic.
for marker in [
    "private val actionQueue = Channel<DeviceUiAction>(capacity = 16)",
    "actionMirrorJob?.cancel()",
    "delay(75L)",
    "refreshMirror()",
]:
    assert marker in vm, f"remote action responsiveness regression: {marker}"
assert "remoteUiMutex" not in vm, "mirror/action global mutex still present"

print("UI responsiveness optimization gate: PASS")
