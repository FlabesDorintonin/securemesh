#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / "firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino"
text = FW.read_text(encoding="utf-8")


def body_of(signature: str, next_signature: str) -> str:
    # Arduino sketches contain generated-safe forward declarations. Match the
    # actual definition marker so the gate never audits from an earlier prototype.
    definition = signature + " {"
    start = text.find(definition)
    if start < 0:
        raise AssertionError(f"missing definition {definition}")
    end = text.find(next_signature, start + len(definition))
    if end < 0:
        raise AssertionError(f"missing boundary {next_signature}")
    return text[start:end]


checks = []

def check(name: str, condition: bool):
    checks.append((name, bool(condition)))


radio_irq = body_of("void processRadioInterrupt()", "void processAckTimeout()")
ui = body_of("void processUi()", "// ============================================================\n// 21. PERIODIC STATUS")
ui_action = body_of("bool uiHandleRemoteAction(uint8_t rawAction)", "uint16_t buildUiStatePayload")
dispatch = body_of("void dispatchCommand(const CommandRequest& request, CommandResult& result)", "void sendBleCommandResponse")
loop_start = text.find("void loop() {")
if loop_start < 0:
    raise AssertionError("missing loop definition")
loop = text[loop_start:]

# A stale/floating DIO1 from an unavailable/under-powered SX1268 must be consumed
# without touching RadioLib. The readiness guard must precede TX/RX dispatch.
ready_guard = radio_irq.find("if (!radioReady) return;")
radio_dispatch = min(
    p for p in (radio_irq.find("finishQueuedTransmission()"), radio_irq.find("handleReceivedRadioPacket()")) if p >= 0
)
check("radio IRQ readiness guard exists", ready_guard >= 0)
check("radio IRQ readiness guard precedes RadioLib dispatch", ready_guard >= 0 and ready_guard < radio_dispatch)
check("stale IRQ flag is cleared before readiness return", radio_irq.find("radioIrqFlag = false;") >= 0 and radio_irq.find("radioIrqFlag = false;") < ready_guard)

# OLED arbitration is valid only when the radio subsystem itself is online.
check("UI defers for IRQ only while radio is ready", "if (radioReady && radioIrqFlag)" in ui)
check("UI no longer has unconditional IRQ deferral", re.search(r"if\s*\(radioIrqFlag\)\s*\{", ui) is None)
check("normal TX still defers non-critical OLED flush", "if (radioReady && radioTransmitting && !critical)" in ui)
check("critical TX bounded defer also requires ready radio", "if (radioReady && radioTransmitting && critical" in ui)

# UI state transitions and BLE UI_ACTION acceptance must stay independent of RF readiness.
check("UI action state machine is radio-independent", "radioReady" not in ui_action)
ui_case = dispatch[dispatch.find("case CommandType::UiAction:") : dispatch.find("case CommandType::GetOledFrameChunk:")]
check("BLE UI_ACTION dispatcher is radio-independent", "radioReady" not in ui_case and "uiHandleRemoteAction" in ui_case)
check("main loop always services UI", "processUi();" in loop)

failed = [name for name, ok in checks if not ok]
for i, (name, ok) in enumerate(checks, 1):
    print(f"[{i:02d}/{len(checks):02d}] {'PASS' if ok else 'FAIL'} {name}")

if failed:
    print("radio-degraded UI isolation gate FAILED:", file=sys.stderr)
    for name in failed:
        print(f" - {name}", file=sys.stderr)
    sys.exit(1)

print(f"radio-degraded UI isolation gate: {len(checks)}/{len(checks)} PASS")
