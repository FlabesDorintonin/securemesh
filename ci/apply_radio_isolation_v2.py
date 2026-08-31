#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"
SCREEN = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 1:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        print(f"applied: {label}")
        return
    if count == 0 and new in text:
        print(f"already materialized: {label}")
        return
    raise SystemExit(
        f"fail-closed replacement for {label}: old occurrences={count}, new_present={new in text}"
    )


# -----------------------------------------------------------------------------
# Firmware: move every potentially blocking SX1268 recovery begin() off loop().
# The worker is pinned to the Arduino loop core at idle priority. This preserves
# single-core RadioLib ownership while allowing BLE/OLED/main-loop work to
# preempt an absent/under-powered radio initialization attempt.
# -----------------------------------------------------------------------------
replace_once(
    FW,
    "#include <Arduino.h>\n#include <SPI.h>",
    "#include <Arduino.h>\n#include <freertos/FreeRTOS.h>\n#include <freertos/task.h>\n#include <SPI.h>",
    "FreeRTOS radio-worker headers",
)

replace_once(
    FW,
    "uint32_t lastRadioRetryAtMs = 0;\nuint32_t radioRetryDelayMs = RADIO_RETRY_MS;\nint16_t lastRadioError = RADIOLIB_ERR_NONE;",
    "uint32_t lastRadioRetryAtMs = 0;\nuint32_t radioRetryDelayMs = RADIO_RETRY_MS;\nvolatile bool radioInitInProgress = false;\nTaskHandle_t radioRecoveryTaskHandle = nullptr;\nuint32_t radioInitAttemptCount = 0;\nuint32_t radioInitLastDurationMs = 0;\nint16_t lastRadioError = RADIOLIB_ERR_NONE;",
    "radio worker state",
)

replace_once(
    FW,
    "bool startReceiveMode() {\n  if (!radioReady) return false;\n\n  setRfReceive();\n  const int16_t state = radio.startReceive();\n  if (state != RADIOLIB_ERR_NONE) {\n    lastRadioError = state;\n    radioReady = false;\n    setRfIdle();\n    return false;\n  }\n\n  return true;\n}",
    "bool startReceiveMode() {\n  if (!radioReady || radioInitInProgress) return false;\n\n  setRfReceive();\n  const int16_t state = radio.startReceive();\n  if (state != RADIOLIB_ERR_NONE) {\n    lastRadioError = state;\n    radioReady = false;\n    radioIrqFlag = false;\n    detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n    pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);\n    setRfIdle();\n    return false;\n  }\n\n  return true;\n}",
    "RX failure isolates DIO1 and degraded control plane",
)

replace_once(
    FW,
    "bool initializeRadio() {\n  radioReady = false;\n  radioTransmitting = false;\n  radioIrqFlag = false;\n  activeTxIndex = -1;\n\n  initializeRfSwitchPins();",
    "bool initializeRadio() {\n  // This function may take an unbounded amount of time inside RadioLib when\n  // SX1268 is absent/under-powered. It is therefore called only by the\n  // low-priority radio recovery task, never directly from setup()/loop().\n  radioReady = false;\n  radioTransmitting = false;\n  radioIrqFlag = false;\n  activeTxIndex = -1;\n\n  detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n  pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);\n  initializeRfSwitchPins();",
    "radio init fail-safe entry",
)

replace_once(
    FW,
    "  radio.setDio1Action(onRadioDio1);\n  radioReady = true;\n  lastRadioError = RADIOLIB_ERR_NONE;\n  if (!startReceiveMode()) {\n    // A failed RX transition must not leave a live DIO1 callback attached to\n    // an unavailable/under-powered radio. Degraded BLE/OLED mode owns the loop.\n    detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n    return false;\n  }\n  return true;\n}",
    "  radio.setDio1Action(onRadioDio1);\n\n  // Do not publish radioReady until the whole initialization, including RX,\n  // is complete. Main-loop radio code is therefore unable to race a partially\n  // initialized RadioLib object while the worker is still configuring it.\n  setRfReceive();\n  state = radio.startReceive();\n  if (state != RADIOLIB_ERR_NONE) {\n    lastRadioError = state;\n    detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n    pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);\n    setRfIdle();\n    return false;\n  }\n\n  lastRadioError = RADIOLIB_ERR_NONE;\n  radioReady = true;\n  return true;\n}",
    "publish radio readiness only after RX is established",
)

replace_once(
    FW,
    "  if (radioTransmitting) {\n    radio.finishTransmit();\n  }\n\n  if (activeTxIndex >= 0 &&",
    "  // Once a radio transaction has faulted, do not make another RadioLib call\n  // from the main loop just to clean it up. finishTransmit()/standby()/RX can\n  // themselves block when the module has lost power. A later worker-side\n  // begin() reinitializes the transceiver from a clean state.\n  radioReady = false;\n\n  if (activeTxIndex >= 0 &&",
    "fault recovery never calls RadioLib cleanup from main loop",
)

replace_once(
    FW,
    "  radioTransmitting = false;\n  activeTxIndex = -1;\n  radioIrqFlag = false;\n  radioReady = false;\n  // If the module disappears after a previously successful init, keep a\n  // floating/noisy DIO1 from waking the main loop until a later retry succeeds.\n  detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n  setRfIdle();\n}\n\nvoid processRadioRecovery() {\n  if (!cryptoReady) return;\n  if (radioReady) return;\n  const uint32_t now = millis();\n  if (now - lastRadioRetryAtMs < radioRetryDelayMs) return;\n\n  lastRadioRetryAtMs = now;\n  if (initializeRadio()) {\n    radioRetryDelayMs = RADIO_RETRY_MS;\n    setLastEvent(\"RADIO RECOVERED\");\n    return;\n  }\n\n  // RadioLib initialization is synchronous. When the module is physically\n  // absent, retrying every 3 s repeatedly steals time from BLE and OLED. Keep\n  // automatic hot-plug recovery, but exponentially back off sustained failure.\n  const uint32_t doubled = radioRetryDelayMs > RADIO_RETRY_MAX_MS / 2\n    ? RADIO_RETRY_MAX_MS\n    : radioRetryDelayMs * 2UL;\n  radioRetryDelayMs = min(doubled, RADIO_RETRY_MAX_MS);\n}",
    "  radioTransmitting = false;\n  activeTxIndex = -1;\n  radioIrqFlag = false;\n  // If the module disappears after a previously successful init, keep a\n  // floating/noisy DIO1 from waking the main loop until a later retry succeeds.\n  detachInterrupt(digitalPinToInterrupt(PIN_RADIO_DIO1));\n  pinMode(PIN_RADIO_DIO1, INPUT_PULLDOWN);\n  setRfIdle();\n\n  // Runtime failures get one immediate worker-side recovery opportunity.\n  // Sustained absence is then exponentially backed off by the worker.\n  radioRetryDelayMs = RADIO_RETRY_MS;\n  lastRadioRetryAtMs = millis() - radioRetryDelayMs;\n}\n\nvoid radioRecoveryTask(void*) {\n  for (;;) {\n    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);\n\n    if (!cryptoReady || radioReady) {\n      radioInitInProgress = false;\n      continue;\n    }\n\n    const uint32_t startedAtMs = millis();\n    radioInitAttemptCount++;\n    const bool recovered = initializeRadio();\n    radioInitLastDurationMs = millis() - startedAtMs;\n\n    if (recovered) {\n      radioRetryDelayMs = RADIO_RETRY_MS;\n      Serial.printf(\n        \"[RADIO INIT] attempt=%lu OK duration=%lums\\r\\n\",\n        static_cast<unsigned long>(radioInitAttemptCount),\n        static_cast<unsigned long>(radioInitLastDurationMs)\n      );\n      setLastEvent(\"RADIO RECOVERED\");\n    } else {\n      const uint32_t doubled = radioRetryDelayMs > RADIO_RETRY_MAX_MS / 2\n        ? RADIO_RETRY_MAX_MS\n        : radioRetryDelayMs * 2UL;\n      radioRetryDelayMs = min(doubled, RADIO_RETRY_MAX_MS);\n      Serial.printf(\n        \"[RADIO INIT] attempt=%lu FAIL err=%d duration=%lums next=%lums\\r\\n\",\n        static_cast<unsigned long>(radioInitAttemptCount),\n        static_cast<int>(lastRadioError),\n        static_cast<unsigned long>(radioInitLastDurationMs),\n        static_cast<unsigned long>(radioRetryDelayMs)\n      );\n    }\n\n    radioInitInProgress = false;\n  }\n}\n\nbool initializeRadioRecoveryTask() {\n  if (radioRecoveryTaskHandle != nullptr) return true;\n\n  // Pin the worker to the Arduino loop core at idle priority. RadioLib may\n  // synchronously wait for a missing/under-powered SX1268; keeping that wait in\n  // a priority-0 task lets BLE/OLED/main-loop work preempt it every scheduler\n  // tick while also avoiding concurrent RadioLib access from another core.\n  const BaseType_t created = xTaskCreatePinnedToCore(\n    radioRecoveryTask,\n    \"sm-radio-recovery\",\n    4096,\n    nullptr,\n    0,\n    &radioRecoveryTaskHandle,\n    xPortGetCoreID()\n  );\n  return created == pdPASS;\n}\n\nvoid processRadioRecovery() {\n  if (!cryptoReady || radioReady || radioInitInProgress) return;\n  if (radioRecoveryTaskHandle == nullptr) return;\n\n  const uint32_t now = millis();\n  if (now - lastRadioRetryAtMs < radioRetryDelayMs) return;\n\n  // Scheduling is non-blocking: loop() never calls initializeRadio().\n  lastRadioRetryAtMs = now;\n  radioInitInProgress = true;\n  xTaskNotifyGive(radioRecoveryTaskHandle);\n}",
    "asynchronous low-priority radio recovery worker",
)

replace_once(
    FW,
    "  if (!radioReady || radioTransmitting ||\n      index < 0 || index >= static_cast<int>(MAX_TX_QUEUE) ||",
    "  if (!radioReady || radioInitInProgress || radioTransmitting ||\n      index < 0 || index >= static_cast<int>(MAX_TX_QUEUE) ||",
    "TX start excludes radio initialization window",
)
replace_once(
    FW,
    "  if (!radioReady) return;\n\n  if (radioTransmitting) {\n    finishQueuedTransmission();",
    "  if (!radioReady || radioInitInProgress) return;\n\n  if (radioTransmitting) {\n    finishQueuedTransmission();",
    "IRQ dispatch excludes radio initialization window",
)
replace_once(
    FW,
    "  if (!radioReady || radioTransmitting || radioIrqFlag) return;",
    "  if (!radioReady || radioInitInProgress || radioTransmitting || radioIrqFlag) return;",
    "TX scheduler excludes radio initialization window",
)

replace_once(
    FW,
    "  initializeOled();\n  initializeGps();\n  const bool radioOk = cryptoOk && initializeRadio();\n\n  const uint32_t heapBeforeBle = static_cast<uint32_t>(ESP.getFreeHeap());\n  const uint32_t largestBeforeBle = largestFreeHeapBytes();\n  const bool bleOk = initializeBle();\n  const uint32_t heapAfterBle = static_cast<uint32_t>(ESP.getFreeHeap());\n  const uint32_t largestAfterBle = largestFreeHeapBytes();",
    "  initializeOled();\n  initializeGps();\n\n  const uint32_t heapBeforeBle = static_cast<uint32_t>(ESP.getFreeHeap());\n  const uint32_t largestBeforeBle = largestFreeHeapBytes();\n  const bool bleOk = initializeBle();\n  const uint32_t heapAfterBle = static_cast<uint32_t>(ESP.getFreeHeap());\n  const uint32_t largestAfterBle = largestFreeHeapBytes();\n  const bool radioWorkerOk = cryptoOk && initializeRadioRecoveryTask();",
    "BLE control plane starts before any radio initialization",
)
replace_once(
    FW,
    "  nextNeighborLifecycleAtMs = now + 1000;\n  lastRadioRetryAtMs = now;",
    "  nextNeighborLifecycleAtMs = now + 1000;\n  // First radio attempt is queued immediately, but runs only in the\n  // low-priority worker after the control plane has already started.\n  lastRadioRetryAtMs = now - RADIO_RETRY_MS;",
    "queue first asynchronous radio attempt",
)
replace_once(
    FW,
    "  Serial.printf(\"AES-256-GCM: %s\\r\\n\", cryptoOk ? \"OK\" : \"FAIL-CLOSED\");\n  Serial.printf(\"Radio: %s\\r\\n\", radioOk ? \"OK\" : \"ERROR\");\n  Serial.printf(\"BLE: %s stack=NimBLE app-protocol=2\\r\\n\", bleOk ? \"OK\" : \"ERROR\");",
    "  Serial.printf(\"AES-256-GCM: %s\\r\\n\", cryptoOk ? \"OK\" : \"FAIL-CLOSED\");\n  Serial.printf(\"Radio: %s\\r\\n\", radioWorkerOk ? \"STARTING ASYNC\" : \"DISABLED\");\n  Serial.printf(\"BLE: %s stack=NimBLE app-protocol=2\\r\\n\", bleOk ? \"OK\" : \"ERROR\");",
    "asynchronous radio boot status",
)
replace_once(
    FW,
    "  if (!identityOk || !cryptoOk) setLastEvent(\"CRYPTO FAIL-CLOSED\");\n  else if (!radioOk) setLastEvent(\"RADIO ERROR\");\n  else if (!bleOk) setLastEvent(\"BLE ERROR\");\n  else setLastEvent(\"CONTROL READY\");\n\n  initializeUi();",
    "  if (!identityOk || !cryptoOk) setLastEvent(\"CRYPTO FAIL-CLOSED\");\n  else if (!bleOk) setLastEvent(\"BLE ERROR\");\n  else if (!radioWorkerOk) setLastEvent(\"RADIO WORKER ERROR\");\n  else setLastEvent(\"CONTROL READY\");\n\n  initializeUi();\n  if (radioWorkerOk) processRadioRecovery();",
    "control-plane-first startup state",
)

# -----------------------------------------------------------------------------
# Android: UI_ACTION must not wait behind a four-command framebuffer poll.
# Firmware snapshots are immutable after chunk 0, so mirror reads can safely
# interleave with UI actions. Rapid actions are queued and the mirror refresh is
# coalesced after the burst instead of disabling the remote for every frame read.
# -----------------------------------------------------------------------------
replace_once(
    SCREEN,
    "private const val OLED_MIRROR_POLL_INTERVAL_MS = 800L",
    "private const val OLED_MIRROR_POLL_INTERVAL_MS = 1500L",
    "lower fallback OLED mirror polling load",
)
replace_once(
    SCREEN,
    "        enabled = !busy,",
    "        enabled = true,",
    "remote buttons remain responsive while action queue drains",
)

replace_once(
    VM,
    "import kotlinx.coroutines.flow.MutableStateFlow",
    "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.channels.Channel\nimport kotlinx.coroutines.flow.MutableStateFlow",
    "Android queued-action imports",
)
replace_once(
    VM,
    "    // GET_OLED_FRAME_CHUNK uses a multi-request snapshot. Keep it mutually exclusive\n    // with UI_ACTION so a button press cannot be hidden behind mirror traffic.\n    private val remoteUiMutex = Mutex()",
    "    // UI actions are serialized independently from framebuffer traffic. Chunk 0\n    // freezes a firmware-side 1024-byte OLED snapshot, so later chunks may safely\n    // interleave with UI_ACTION without tearing the image or blocking the remote.\n    private val uiStateMutex = Mutex()\n    private val actionQueue = Channel<DeviceUiAction>(capacity = 16)\n    private var actionMirrorJob: Job? = null",
    "split UI action path from mirror traffic",
)
replace_once(
    VM,
    "    val uiState = combine(\n        repository.session,\n        repository.deviceUiState,\n        repository.oledFramebuffer,\n        busy,\n        error,\n    ) { session, device, frame, isBusy, failure ->\n        DeviceControlUiState(\n            session = session,\n            device = device,\n            oledFramebuffer = frame,\n            allowed = UiAccessPolicy.canControlDeviceUi(session),\n            exactMirrorAvailable = session?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true,\n            busy = isBusy,\n            error = failure,\n        )\n    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceControlUiState())\n\n    fun refresh() = execute {\n        remoteUiMutex.withLock { repository.refreshDeviceUiState() }\n    }",
    "    val uiState = combine(\n        repository.session,\n        repository.deviceUiState,\n        repository.oledFramebuffer,\n        busy,\n        error,\n    ) { session, device, frame, isBusy, failure ->\n        DeviceControlUiState(\n            session = session,\n            device = device,\n            oledFramebuffer = frame,\n            allowed = UiAccessPolicy.canControlDeviceUi(session),\n            exactMirrorAvailable = session?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true,\n            busy = isBusy,\n            error = failure,\n        )\n    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceControlUiState())\n\n    init {\n        viewModelScope.launch {\n            for (action in actionQueue) processQueuedAction(action)\n        }\n    }\n\n    fun refresh() = execute {\n        uiStateMutex.withLock { repository.refreshDeviceUiState() }\n    }",
    "start bounded sequential UI action worker",
)
replace_once(
    VM,
    "                remoteUiMutex.withLock { repository.refreshOledFramebuffer() }",
    "                repository.refreshOledFramebuffer()",
    "mirror no longer blocks UI action path",
)
replace_once(
    VM,
    "    fun action(action: DeviceUiAction) = execute {\n        remoteUiMutex.withLock {\n            val result = repository.sendDeviceUiAction(action)\n            if (result.isSuccess && repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true) {\n                // Firmware redraw happens in the next main-loop pass. Waiting a short,\n                // bounded interval makes the following snapshot represent the action ACK,\n                // rather than the framebuffer that existed just before the button press.\n                delay(75L)\n                repository.refreshOledFramebuffer()\n            }\n            result\n        }\n    }",
    "    fun action(action: DeviceUiAction) {\n        if (!actionQueue.trySend(action).isSuccess) {\n            error.value = \"Очередь пульта заполнена — дождитесь выполнения команд\"\n        }\n    }\n\n    private suspend fun processQueuedAction(action: DeviceUiAction) {\n        busy.value = true\n        error.value = null\n        val result = try {\n            repository.sendDeviceUiAction(action)\n        } catch (t: Throwable) {\n            Result.failure(t)\n        }\n        result.onFailure { throwable ->\n            error.value = throwable.message ?: \"Узел не принял команду\"\n        }\n        busy.value = false\n\n        if (result.isSuccess && repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true) {\n            // Coalesce a burst of button presses into one exact framebuffer refresh.\n            // The next UI_ACTION is not held behind four GET_OLED_FRAME_CHUNK calls.\n            actionMirrorJob?.cancel()\n            actionMirrorJob = viewModelScope.launch {\n                delay(75L)\n                refreshMirror()\n            }\n        }\n    }",
    "queued UI actions with coalesced exact mirror refresh",
)

print("radio isolation v2 materialization: PASS")
