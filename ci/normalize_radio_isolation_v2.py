#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt"
TRANSPORT = ROOT / "android/v1.0.4/source/app/src/main/java/dev/securemesh/commander/data/ble/BleTransport.kt"


def normalize_android_action_imports() -> None:
    text = VM.read_text(encoding="utf-8")
    anchor = "import dev.securemesh.commander.domain.service.UiAccessPolicy\n"
    job = "import kotlinx.coroutines.Job\n"
    channel = "import kotlinx.coroutines.channels.Channel\n"
    if text.count(anchor) != 1:
        raise SystemExit("fail-closed: DeviceControlViewModel import anchor is not unique")

    cleaned = text.replace(job, "").replace(channel, "")
    normalized = cleaned.replace(anchor, anchor + job + channel, 1)
    if normalized != text:
        VM.write_text(normalized, encoding="utf-8")
        print("normalized: Android queued-action imports")
    else:
        print("already normalized: Android queued-action imports")

    result = VM.read_text(encoding="utf-8")
    if result.count(job) != 1 or result.count(channel) != 1:
        raise SystemExit("fail-closed: queued-action imports are not canonical")


def normalize_oled_geometry_allocation() -> None:
    text = TRANSPORT.read_text(encoding="utf-8")
    validation = (
        '                require(width > 0 && height > 0 && width * height % 8 == 0) { "Invalid OLED geometry ${width}x$height" }\n'
        '                frameBytes = ByteArray(width * height / 8)'
    )
    count = text.count(validation)
    if count != 1:
        raise SystemExit(f"fail-closed: OLED geometry/allocation block count={count}, expected=1")
    print("verified: single OLED geometry/allocation block")


normalize_android_action_imports()
normalize_oled_geometry_allocation()
print("Radio Isolation v2 normalization: PASS")
