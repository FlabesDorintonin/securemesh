#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"
SCREEN = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
TRANSPORT = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/data/ble/BleTransport.kt"

V2_SUPERSEDED_LABELS = {
    "radio recovery backoff state",
    "detach DIO1 after failed RX transition",
    "detach DIO1 during runtime recovery",
    "adaptive radio recovery backoff",
}


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if path == FW and label in V2_SUPERSEDED_LABELS and "TaskHandle_t radioRecoveryTaskHandle" in text:
        print(f"superseded by Radio Isolation v2: {label}")
        return
    if path == SCREEN and label == "Android mirror polling constant" and "OLED_MIRROR_POLL_INTERVAL_MS = 1500L" in text:
        print("superseded by Radio Isolation v2: Android mirror polling constant")
        return
    count = text.count(old)
    if count == 1:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        print(f"applied: {label}")
        return
    if count == 0 and new in text:
        print(f"already materialized: {label}")
        return
    raise SystemExit(f"fail-closed replacement for {label}: old occurrences={count}, new_present={new in text}")


def normalize_oled_frame_allocation() -> None:
    text = TRANSPORT.read_text(encoding="utf-8")
    original = (
        '                expectedCount = chunk.chunkCount\n'
        '                require(expectedCount == 4) { "Unexpected OLED chunk count $expectedCount" }'
    )
    validation = (
        '                require(width > 0 && height > 0 && width * height % 8 == 0) { "Invalid OLED geometry ${width}x$height" }\n'
        '                frameBytes = ByteArray(width * height / 8)'
    )
    desired = original + "\n" + validation
    duplicated = desired + "\n" + validation

    if duplicated in text:
        text = text.replace(duplicated, desired, 1)
        TRANSPORT.write_text(text, encoding="utf-8")
        print("applied: normalize duplicate OLED geometry/allocation")
        return
    if desired in text:
        print("already materialized: validate OLED geometry and allocate once")
        return
    if text.count(original) == 1:
        TRANSPORT.write_text(text.replace(original, desired, 1), encoding="utf-8")
        print("applied: validate OLED geometry and allocate once")
        return
    raise SystemExit("fail-closed: cannot normalize OLED framebuffer geometry/allocation")


replace_once(
    FW,
    "constexpr uint32_t RADIO_RETRY_MS = 3000;\nconstexpr uint32_t TX_WATCHDOG_MS = 3500;",
    "constexpr uint32_t RADIO_RETRY_MS = 3000;\nconstexpr uint32_t RADIO_RETRY_MAX_MS = 30000;\nconstexpr uint32_t TX_WATCHDOG_MS = 3500;",
    "bounded radio recovery backoff constant",
)
replace_once(FW, "constexpr uint32_t OLED_REFRESH_MS = 450;", "constexpr uint32_t OLED_REFRESH_MS = 650;", "OLED idle/dynamic frame load shedding")
replace_once(FW, "constexpr uint32_t UI_CRITICAL_FRAME_MS = 80;", "constexpr uint32_t UI_CRITICAL_FRAME_MS = 250;", "pairing frame load shedding")
replace_once(
    FW,
    "constexpr uint32_t UI_SUCCESS_ANIMATION_MS = 1250;\nconstexpr uint32_t UI_TOAST_SLIDE_MS = 180;",
    "constexpr uint32_t UI_SUCCESS_ANIMATION_MS = 1250;\nconstexpr uint32_t UI_CONNECTED_ANIMATION_MS = 600;\nconstexpr uint32_t UI_TOAST_SLIDE_MS = 180;",
    "connected-banner animation window",
)
replace_once(
    FW,
    "uint32_t lastRadioRetryAtMs = 0;\nint16_t lastRadioError = RADIOLIB_ERR_NONE;",
    "uint32_t lastRadioRetryAtMs = 0;\nuint32_t radioRetryDelayMs = RADIO_RETRY_MS;\nint16_t lastRadioError = RADIOLIB_ERR_NONE;",
    "radio recovery backoff state",
)
replace_once(
    FW,
    "  radio.setDio1Action(onRadioDio1);\n  radioReady = true;\n  lastRadioError = RADIOLIB_ERR_NONE;\n  return startReceiveMode();\n}",
    "  radio.setDio1Action(onRadioDio1);\n  radioReady = true;\n  lastRadioError = RADIOLIB_ERR_NONE;\n  if (!startReceiveMode()) {\n    // A failed RX transition must not leave a live DIO1 callback attached to\n    // an unavailable/under-powered radio. Degraded BLE/OLED mode owns the loop.\n    detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n    return false;\n  }\n  return true;\n}",
    "detach DIO1 after failed RX transition",
)
replace_once(
    FW,
    "  activeTxIndex = -1;\n  radioIrqFlag = false;\n  radioReady = false;\n  setRfIdle();\n}",
    "  activeTxIndex = -1;\n  radioIrqFlag = false;\n  radioReady = false;\n  // If the module disappears after a previously successful init, keep a\n  // floating/noisy DIO1 from waking the main loop until a later retry succeeds.\n  detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n  setRfIdle();\n}",
    "detach DIO1 during runtime recovery",
)
replace_once(
    FW,
    "void processRadioRecovery() {\n  if (!cryptoReady) return;\n  if (radioReady) return;\n  if (millis() - lastRadioRetryAtMs < RADIO_RETRY_MS) return;\n\n  lastRadioRetryAtMs = millis();\n  initializeRadio();\n}",
    "void processRadioRecovery() {\n  if (!cryptoReady) return;\n  if (radioReady) return;\n  const uint32_t now = millis();\n  if (now - lastRadioRetryAtMs < radioRetryDelayMs) return;\n\n  lastRadioRetryAtMs = now;\n  if (initializeRadio()) {\n    radioRetryDelayMs = RADIO_RETRY_MS;\n    setLastEvent(\"RADIO RECOVERED\");\n    return;\n  }\n\n  // RadioLib initialization is synchronous. When the module is physically\n  // absent, retrying every 3 s repeatedly steals time from BLE and OLED. Keep\n  // automatic hot-plug recovery, but exponentially back off sustained failure.\n  const uint32_t doubled = radioRetryDelayMs > RADIO_RETRY_MAX_MS / 2\n    ? RADIO_RETRY_MAX_MS\n    : radioRetryDelayMs * 2UL;\n  radioRetryDelayMs = min(doubled, RADIO_RETRY_MAX_MS);\n}",
    "adaptive radio recovery backoff",
)
replace_once(
    FW,
    "  const bool transitionAnimating = uiState.transitionStartedAtMs != 0 && now - uiState.transitionStartedAtMs < UI_TRANSITION_MS;\n  const bool toastAnimating = uiState.toastUntilMs != 0 && !timeReached(now, uiState.toastUntilMs);\n  const bool dynamicScene = uiState.scene == UiScene::Home ||\n    (uiState.scene == UiScene::Feature && uiFeatureIsDynamic(uiState.feature));\n  const bool critical = pairing;\n  const bool animating = !uiState.bootFinished || pairing || connectedBanner || cursorAnimating || transitionAnimating || toastAnimating;",
    "  const bool transitionAnimating = uiState.transitionStartedAtMs != 0 && now - uiState.transitionStartedAtMs < UI_TRANSITION_MS;\n  const bool toastVisible = uiState.toastUntilMs != 0 && !timeReached(now, uiState.toastUntilMs);\n  const uint32_t toastAge = toastVisible ? now - uiState.toastStartedAtMs : 0;\n  const uint32_t toastRemaining = toastVisible ? uiState.toastUntilMs - now : 0;\n  const bool toastAnimating = toastVisible &&\n    (toastAge < UI_TOAST_SLIDE_MS || toastRemaining < UI_TOAST_SLIDE_MS);\n  const bool connectedBannerAnimating = connectedBanner &&\n    now - uiState.overlayEnteredAtMs < UI_CONNECTED_ANIMATION_MS;\n  const bool dynamicScene = uiState.scene == UiScene::Home ||\n    (uiState.scene == UiScene::Feature && uiFeatureIsDynamic(uiState.feature));\n  const bool critical = pairing;\n  const bool animating = !uiState.bootFinished || pairing || cursorAnimating ||\n    transitionAnimating || toastAnimating || connectedBannerAnimating;",
    "OLED animation load shedding",
)

replace_once(
    SCREEN,
    "import kotlinx.coroutines.delay\n\n@Composable",
    "import kotlinx.coroutines.delay\n\nprivate const val OLED_MIRROR_POLL_INTERVAL_MS = 800L\n\n@Composable",
    "Android mirror polling constant",
)
replace_once(SCREEN, "            delay(450L)", "            delay(OLED_MIRROR_POLL_INTERVAL_MS)", "Android mirror polling interval")

replace_once(
    TRANSPORT,
    "        val chunks = ArrayList<ByteArray>(4)\n        var snapshotId: Long? = null\n        var width = 0\n        var height = 0\n        var expectedCount = 0",
    "        var frameBytes: ByteArray? = null\n        var snapshotId: Long? = null\n        var width = 0\n        var height = 0\n        var expectedCount = 0\n        var writeOffset = 0",
    "preallocate OLED framebuffer",
)
normalize_oled_frame_allocation()
replace_once(
    TRANSPORT,
    "            require(chunk.chunkIndex == index) { \"OLED chunk order mismatch\" }\n            chunks += chunk.data\n        }\n        val frameBytes = chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }\n        require(frameBytes.size == width * height / 8) { \"OLED framebuffer length mismatch: ${frameBytes.size}\" }",
    "            require(chunk.chunkIndex == index) { \"OLED chunk order mismatch\" }\n            val target = requireNotNull(frameBytes)\n            require(writeOffset + chunk.data.size <= target.size) { \"OLED chunk exceeds framebuffer\" }\n            chunk.data.copyInto(target, destinationOffset = writeOffset)\n            writeOffset += chunk.data.size\n        }\n        val completeFrame = requireNotNull(frameBytes)\n        require(writeOffset == completeFrame.size) { \"OLED framebuffer length mismatch: $writeOffset/${completeFrame.size}\" }",
    "copy OLED chunks without repeated concatenation",
)
replace_once(TRANSPORT, "            bytes = frameBytes,", "            bytes = completeFrame,", "publish complete preallocated OLED frame")

print("UI responsiveness materialization: PASS")
