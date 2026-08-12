from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt"
text = path.read_text(encoding="utf-8")

old = """            state.error?.let { message ->
                item {
"""
new = """            val errorMessage = state.error
            if (errorMessage != null) {
                item {
"""

if old in text:
    text = text.replace(old, new, 1)
    text = text.replace("Text(message, color = SecureMeshColors.TextSecondary", "Text(errorMessage, color = SecureMeshColors.TextSecondary", 1)
    path.write_text(text, encoding="utf-8")
    print("Delight UI LazyColumn syntax fixed")
elif new in text:
    print("Delight UI syntax already fixed")
else:
    raise SystemExit("Expected DeviceControlScreen error block not found")
