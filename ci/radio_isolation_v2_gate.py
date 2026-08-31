#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"
SCREEN = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"

fw = FW.read_text(encoding="utf-8")
screen = SCREEN.read_text(encoding="utf-8")
vm = VM.read_text(encoding="utf-8")


def body(signature: str, next_marker: str) -> str:
    start = fw.find(signature + " {")
    assert start >= 0, f"missing definition {signature}"
    end = fw.find(next_marker, start + len(signature))
    assert end >= 0, f"missing boundary after {signature}: {next_marker}"
    return fw[start:end]


init_radio = body("bool initializeRadio()", "void recoverRadio(")
recover = body("void recoverRadio(int16_t errorCode)", "void radioRecoveryTask(")
worker = body("void radioRecoveryTask(void*)", "bool initializeRadioRecoveryTask()")
scheduler = body("void processRadioRecovery()", "// ============================================================\n// 15. FRAME / MESSAGE BUILDERS")
irq = body("void processRadioInterrupt()", "void processAckTimeout()")
tx_start = body("bool startQueuedTransmission(int index)", "void finishQueuedTransmission()")
tx_scheduler = body("void processTxScheduler()", "void processTxWatchdog()")
setup = body("void setup()", "void loop()")
loop_start = fw.find("void loop() {")
assert loop_start >= 0
loop = fw[loop_start:]

# --- Firmware architectural isolation ---
for marker in [
    "#include <freertos/FreeRTOS.h>",
    "#include <freertos/task.h>",
    "volatile bool radioInitInProgress = false;",
    "TaskHandle_t radioRecoveryTaskHandle = nullptr;",
    "xTaskCreatePinnedToCore(",
    '"sm-radio-recovery"',
    "xPortGetCoreID()",
    "xTaskNotifyGive(radioRecoveryTaskHandle);",
]:
    assert marker in fw, f"missing async-radio marker: {marker}"

# loop()/setup() must never invoke the blocking initializer directly.
assert "initializeRadio();" not in setup, "setup still blocks on initializeRadio() before BLE/OLED control plane"
assert "initializeRadio()" not in scheduler, "main-loop recovery scheduler still invokes blocking initializeRadio()"
assert "xTaskNotifyGive" in scheduler, "radio recovery scheduler is not notification-only"
assert "processRadioRecovery();" in loop, "loop no longer schedules recovery"
assert "processBle();" in loop and "processUi();" in loop, "BLE/OLED control plane missing from loop"

# Low priority + same core are deliberate: prevent concurrent RadioLib access
# while still letting the Arduino loop preempt a blocking RadioLib wait.
create_call = fw[fw.find("xTaskCreatePinnedToCore("): fw.find("return created == pdPASS;", fw.find("xTaskCreatePinnedToCore("))]
assert re.search(r'"sm-radio-recovery",\s*4096,\s*nullptr,\s*0,\s*&radioRecoveryTaskHandle,\s*xPortGetCoreID\(\)', create_call, re.S), \
    "radio recovery worker must stay priority 0 on Arduino loop core"

# No RadioLib cleanup call is allowed after a runtime fault on the main loop.
assert "radio.finishTransmit()" not in recover, "fault recovery still calls RadioLib finishTransmit()"
for forbidden in ["radio.standby()", "radio.startReceive()", "radio.begin(", "radio.reset("]:
    assert forbidden not in recover, f"fault recovery contains blocking RadioLib call: {forbidden}"
assert "radioReady = false;" in recover
assert "detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));" in recover
assert "pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);" in recover

# Initialization publishes readiness only after RX has successfully started.
start_rx = init_radio.find("state = radio.startReceive();")
publish_ready = init_radio.find("radioReady = true;")
assert start_rx >= 0 and publish_ready > start_rx, "radioReady published before RX establishment"
assert "detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));" in init_radio
assert "pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);" in init_radio

# Main-loop RadioLib entry points fail closed while worker owns initialization.
assert "if (!radioReady || radioInitInProgress) return;" in irq
assert "radioInitInProgress || radioTransmitting" in tx_start
assert "!radioReady || radioInitInProgress || radioTransmitting || radioIrqFlag" in tx_scheduler

# Startup order: BLE initializes before the worker is created/first radio attempt.
ble_pos = setup.find("const bool bleOk = initializeBle();")
worker_pos = setup.find("initializeRadioRecoveryTask()")
assert ble_pos >= 0 and worker_pos > ble_pos, "radio worker starts before BLE control plane"
assert 'Radio: %s\\r\\n", radioWorkerOk ? "STARTING ASYNC"' in setup

# --- Android interaction path isolation ---
for marker in [
    "private val actionQueue = Channel<DeviceUiAction>(capacity = 16)",
    "for (action in actionQueue) processQueuedAction(action)",
    "private suspend fun processQueuedAction(action: DeviceUiAction)",
    "actionMirrorJob?.cancel()",
    "delay(75L)",
    "refreshMirror()",
]:
    assert marker in vm, f"missing queued remote action marker: {marker}"

assert "remoteUiMutex" not in vm, "old mirror/action global mutex still present"
mirror_fn = vm[vm.find("fun refreshMirror()") : vm.find("fun action(action", vm.find("fun refreshMirror()"))]
assert "withLock" not in mirror_fn, "mirror still holds a mutex that can block UI_ACTION"
action_fn = vm[vm.find("fun action(action") : vm.find("fun clearError()", vm.find("fun action(action"))]
assert "trySend(action)" in action_fn, "button actions are not queued"
assert "repository.refreshOledFramebuffer()" not in action_fn, "UI action still synchronously waits for framebuffer read"
assert "private const val OLED_MIRROR_POLL_INTERVAL_MS = 1500L" in screen

remote_button = screen[screen.find("private fun RemoteButton(") : screen.find("private fun LiveTelemetryCard", screen.find("private fun RemoteButton("))]
assert "enabled = true" in remote_button, "remote buttons are still disabled while one action is in flight"
assert "enabled = !busy" not in remote_button, "old busy-disable behavior remains in RemoteButton"

# Wire contracts / VANGUARD identity must remain unchanged by this reliability fix.
for marker in [
    "constexpr uint8_t MESSAGE_VERSION = 2;",
    "constexpr size_t BLE_OLED_FRAME_BYTES = (BLE_OLED_FRAME_WIDTH * BLE_OLED_FRAME_HEIGHT) / 8;",
    "constexpr uint8_t BLE_OLED_FRAME_CHUNK_COUNT",
    '#include "VanguardCore.h"',
    '#include "VanguardProtocol.h"',
    '#include "VanguardManifest.h"',
    '#include "VanguardRuntime.h"',
]:
    assert marker in fw, f"contract/VANGUARD marker lost: {marker}"

print("radio isolation v2 architecture gate: PASS")
