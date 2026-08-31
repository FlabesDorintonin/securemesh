#!/usr/bin/env python3
from pathlib import Path

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
    "private const val OLED_MIRROR_POLL_INTERVAL_MS = 800L",
    "delay(OLED_MIRROR_POLL_INTERVAL_MS)",
    "var frameBytes: ByteArray? = null",
    "chunk.data.copyInto(target, destinationOffset = writeOffset)",
    "require(writeOffset == completeFrame.size)",
]
for marker in required_android:
    assert marker in screen or marker in transport, f"missing Android responsiveness marker: {marker}"

assert "delay(450L)" not in screen, "old 450 ms OLED mirror polling remains"
assert "chunks.fold(ByteArray(0))" not in transport, "quadratic framebuffer concatenation remains"

# Preserve the correctness path: explicit UI actions still serialize against a
# snapshot and request a fresh exact framebuffer after the firmware redraw pass.
for marker in [
    "private val remoteUiMutex = Mutex()",
    "delay(75L)",
    "repository.refreshOledFramebuffer()",
]:
    assert marker in vm, f"remote action exactness regression: {marker}"

print("UI responsiveness optimization gate: PASS")
