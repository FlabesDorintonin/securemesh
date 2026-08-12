from pathlib import Path

root = Path(__file__).resolve().parents[1]
gate = root / "tools/domain_alignment_gate.py"
text = gate.read_text(encoding="utf-8")

old_version = "0.8.1-firmware-0.6.3"
new_version = "0.8.2-firmware-0.6.3-pairing-fix"
old_code = "versionCode = 13"
new_code = "versionCode = 14"

if new_version in text and new_code in text:
    print("version gate already updated")
elif old_version in text and old_code in text:
    gate.write_text(
        text.replace(old_version, new_version, 1).replace(old_code, new_code, 1),
        encoding="utf-8",
    )
    print("version gate updated")
else:
    raise SystemExit("version gate source block not found")
