# SecureMesh

SecureMesh is an offline-first autonomous mesh communication system built around ESP32-S3 field nodes, EBYTE E22-400M30S/SX1268 radio, GPS, BLE, VANGUARD routing and operator applications.

## Current baseline

**SecureMesh v1.0.4 OPERATOR** is the current architecture baseline.

Component source of truth:

- **Android:** this repository, final-QA lineage `8087909a33200b11ee5476f23a63bdf65b2b4c3e`.
- **Firmware / VANGUARD / Commander:** public sanitized source is synchronized under `firmware/v1.0.4-operator/`.
- **Exact firmware release artifact:** `SecureMesh_v1_0_4_OPERATOR_FINAL.zip`, SHA-256 `9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`.
- **Exact Android APK:** `SecureMesh_v1_0_4_OPERATOR_ANDROID_FINAL.apk`, SHA-256 `83e2339bae482b90f1c393d7044b9f63b96ad2513e23783fed1e95463a255862`.

The public firmware tree intentionally does **not** contain the real LAB development group key. See `firmware/v1.0.4-operator/PUBLIC_SYNC_NOTES.md`.

## Evidence boundary

Current software evidence is strong, but v1.0.4 is **not yet hardware-qualified**.

Verified:

- Android architecture/domain alignment and JVM tests;
- Android debug APK build lineage;
- BLE application protocol v2 with fragment transport v1;
- VANGUARD native regression;
- VANGUARD three-radio simulation;
- operator vocabulary / Operational Intelligence / Signal Intelligence checks;
- Commander/LabPanel syntax and manifest checks.

Still required for P0 hardware qualification:

1. exact ESP32-S3 compile and flash;
2. boot/OLED/GPS/BLE/LoRa on three nodes;
3. bidirectional end-to-end delivery;
4. real multi-hop forwarding;
5. Primary + exact G2 validation;
6. forced failover and G2 replenishment;
7. Android ↔ ESP32 BLE v2 end-to-end session;
8. GPS/SOS hardware flow;
9. versioned logs, HW revision and test evidence.

Native simulation must not be reported as hardware or field validation.

## Architecture

Firmware keeps seven logical boundaries:

1. radio / frame transport;
2. crypto / integrity;
3. neighbor state;
4. VANGUARD routing;
5. application services;
6. Operational Intelligence;
7. BLE Command API.

Android follows:

```text
UI → ViewModel → Repository → Transport → BLE/Mock
```

Android/Commander are not the security authority. Firmware must authorize privileged network operations.

## BLE contract — v1.0.4

- fragment transport: `v1`;
- application packet: `v2`;
- application header: `10 B`;
- maximum application packet: `384 B`;
- preferred MTU: `185`;
- current field-test/message payload: up to `70 B`.

A hop ACK is not an end-to-end delivery receipt.

## Radio baseline — v1.0.4

- frequency: `433.92 MHz`;
- bandwidth: `125 kHz`;
- spreading factor: `SF9`;
- coding rate: `4/5`;
- preamble: `12`;
- SX1268 driver power setting: `10 dBm`;
- TCXO: `2.2 V`.

`10 dBm` is the software driver setting, not a calibrated E22 antenna-port measurement.

## Repository layout

```text
app/                         Android application
firmware/v1.0.4-operator/   sanitized firmware/VANGUARD/Commander release source
artifacts/v1.0.4/            versioned source bundle + release hashes
tools/                       Android architecture/quality gates
.github/workflows/           Android + firmware CI
```

See `SOURCE_OF_TRUTH.md` for the canonical component map.

## Verification

Android:

```bash
python3 tools/domain_alignment_gate.py
gradle testDebugUnitTest
gradle assembleDebug
```

Firmware native checks are under:

```text
firmware/v1.0.4-operator/tests/
```

The exact Arduino/ESP32-S3 toolchain and physical three-node test remain a separate gate.

## Security note

v1.0.4 uses AES-256-GCM and replay-related logic, but this does not imply production-grade security. The current shared development group-key model is LAB-only. Secure Boot, Flash/NVS encryption, key lifecycle, provisioning, rotation/revocation and secure update/rollback remain separate engineering tasks.
