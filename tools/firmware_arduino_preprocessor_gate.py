#!/usr/bin/env python3
from pathlib import Path
import sys

FIRMWARE = Path("firmware/v1.0.4-operator/SecureMesh_v1_0_4_OPERATOR.ino")
text = FIRMWARE.read_text(encoding="utf-8")

checks = []

def require(name: str, condition: bool) -> None:
    checks.append((name, condition))
    print(("PASS  " if condition else "FAIL  ") + name)


def pos(token: str) -> int:
    return text.find(token)

require(
    "BLE OLED geometry is declared before BLE framebuffer size",
    0 <= pos("constexpr size_t BLE_OLED_FRAME_WIDTH = 128;")
    < pos("constexpr size_t BLE_OLED_FRAME_BYTES ="),
)
require(
    "BLE OLED framebuffer size no longer depends on later OLED_WIDTH symbol",
    "constexpr size_t BLE_OLED_FRAME_BYTES = (OLED_WIDTH * OLED_HEIGHT) / 8;" not in text,
)
require(
    "OperationalHealthSnapshot explicit Arduino-safe prototype exists after type",
    0 <= pos("struct OperationalHealthSnapshot {")
    < pos("OperationalHealthSnapshot captureOperationalHealth();")
    < pos("OperationalHealthSnapshot captureOperationalHealth() {"),
)
require(
    "UiLinkQuality explicit Arduino-safe prototype exists after type",
    0 <= pos("struct UiLinkQuality {")
    < pos("UiLinkQuality uiEvaluateLink(const NeighborEntry& n);")
    < pos("UiLinkQuality uiEvaluateLink(const NeighborEntry& n) {"),
)
require(
    "UiLinkGrade explicit Arduino-safe text prototype exists after enum",
    0 <= pos("enum class UiLinkGrade")
    < pos("const char* uiLinkGradeText(UiLinkGrade grade);")
    < pos("const char* uiLinkGradeText(UiLinkGrade grade) {"),
)
require(
    "OLED UI geometry is bound to BLE framebuffer geometry",
    "constexpr int OLED_WIDTH = static_cast<int>(BLE_OLED_FRAME_WIDTH);" in text
    and "constexpr int OLED_HEIGHT = static_cast<int>(BLE_OLED_FRAME_HEIGHT);" in text,
)

failed = sum(not ok for _, ok in checks)
print(f"\n{len(checks) - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
