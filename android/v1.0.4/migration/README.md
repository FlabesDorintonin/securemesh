# SecureMesh Android v1.0.4 canonical migration

Baseline: exact hardware-observed Android v0.9.2 Offline Maps from branch `agent/0.9.2-offline-map-manager`, commit `7a8ffa5e2f42442460ca445dc1ea9878f86194ef`, CI run 118, APK SHA-256 `6a77c70fcd817d879f6cb39368338c9eda02efe5f4dc9fa1bb2fe401fe68b339`.

This migration keeps the complete v0.9.2 application surface and appends the final firmware v1.0.4 application-protocol-v2 contract: commands 28 GetBleRadar, 29 ClearBleRadar, 30 GetOperationalHealth, 31 GetSelfDiagnostics, and event 32 OperationalHealthChanged.

Reviewed v0.9.3 work is imported selectively only where it is wire-independent: firmware-backed BLE Radar presentation and display-only Link Health. Conflicting experimental opcode assignments (`FIND_DEVICE(28)`, `RUN_SELF_TEST(29)`, alternative 28–31 layouts), phone-side radar as a core feature, and contact DB v6 migration are not imported into this release.

The migration delta is stored as `android_v1.0.4_from_v0.9.2.patch.gz` (SHA-256 `6484db7d7059c658d4b7555dfbebe9b897b373c9d18dde9c2130377e1021b543`; uncompressed patch SHA-256 `aa0a5cc6757e6eed74b8d86401cac0777f7252eba5c8c5fc8fbd708274a238a8`). CI recovers exact run-118 materialized source, applies this reviewed delta, runs architecture/retention and firmware↔Android cross-contract gates, JVM tests, lint and assembleDebug, then commits a clean materialized source tree under `android/v1.0.4/source/`.

A green CI build is NATIVE TESTED. Android↔ESP32 hardware E2E remains pending until the produced APK is installed on the real phone and reaches authenticated `PROTOCOL_READY` with the v1.0.4 node.
