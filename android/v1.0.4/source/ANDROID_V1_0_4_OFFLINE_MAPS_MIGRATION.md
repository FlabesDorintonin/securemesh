# SecureMesh Android v1.0.4 OFFLINE MAPS

Canonical Android lineage is restored from the hardware-proven v0.9.2 offline-maps build.

Baseline evidence:
- branch: `agent/0.9.2-offline-map-manager`
- commit: `7a8ffa5e2f42442460ca445dc1ea9878f86194ef`
- CI run: `118`
- exact working APK SHA-256: `6a77c70fcd817d879f6cb39368338c9eda02efe5f4dc9fa1bb2fe401fe68b339`

This release preserves the complete v0.9.2 application (MapLibre/offline maps, contacts, encrypted local data, Device UI OS, Command Map, VANGUARD/manifest/fault-lab controls and the hardware-proven `BleDiscoveryParityTransport`) and extends it with the firmware v1.0.4 application-protocol-v2 surface:

- opcode 28 `GetBleRadar`
- opcode 29 `ClearBleRadar`
- opcode 30 `GetOperationalHealth`
- opcode 31 `GetSelfDiagnostics`
- event 32 `OperationalHealthChanged`

The historical reduced `1.0.4-operator-android` line is not a baseline for this application. Its useful protocol/diagnostics deltas were ported without removing v0.9.2 capabilities.

Physical Android↔ESP32 v1.0.4 E2E remains a hardware qualification gate until the new APK is tested on the real phone/node pair.
