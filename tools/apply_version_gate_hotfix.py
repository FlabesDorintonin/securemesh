from pathlib import Path

root = Path(__file__).resolve().parents[1]
gate = root / "tools/domain_alignment_gate.py"
text = gate.read_text(encoding="utf-8")
old = '''check("Firmware-aligned product version is stamped", 'versionName = \\"0.8.1-firmware-0.6.3\\"' in (ROOT / \\"app/build.gradle.kts\\").read_text() and \\"versionCode = 13\\" in (ROOT / \\"app/build.gradle.kts\\").read_text())'''
new = '''check("Firmware-aligned product version is stamped", 'versionName = \\"0.8.2-firmware-0.6.3-pairing-fix\\"' in (ROOT / \\"app/build.gradle.kts\\").read_text() and \\"versionCode = 14\\" in (ROOT / \\"app/build.gradle.kts\\").read_text())'''
if old not in text and new not in text:
    raise SystemExit("version gate source block not found")
if old in text:
    gate.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("version gate updated")
else:
    print("version gate already updated")
