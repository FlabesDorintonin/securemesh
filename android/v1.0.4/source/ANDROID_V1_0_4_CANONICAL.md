# SecureMesh Android 1.0.4 — canonical lineage

## Baseline

This Android release is a materialized successor of the last hardware-observed working Android baseline:

- baseline branch: `agent/0.9.2-offline-map-manager`
- baseline commit: `7a8ffa5e2f42442460ca445dc1ea9878f86194ef`
- baseline CI run: `118`
- baseline APK SHA-256: `6a77c70fcd817d879f6cb39368338c9eda02efe5f4dc9fa1bb2fe401fe68b339`

No v0.9.2 application source file is removed by this migration. The v0.9.2 feature surface is retained: MapLibre/PMTiles offline maps, messages, nodes, contacts, encrypted local data, topology/routes, Field Test, Device UI OS, VANGUARD/manifest/fault-lab controls, GPS/SOS/Command Map, Security Center, and `BleDiscoveryParityTransport`.

## Firmware contract target

Android 1.0.4 targets the application BLE contract implemented by SecureMesh firmware v1.0.4 OPERATOR (`SecureMesh_v1_0_4_OPERATOR.ino` blob `056b22cebd8d05721cff752c817d13a81e67cde3`).

The final application command additions are:

- 28 `GET_BLE_RADAR`
- 29 `CLEAR_BLE_RADAR`
- 30 `GET_OPERATIONAL_HEALTH`
- 31 `GET_SELF_DIAGNOSTICS`
- event 32 `OPERATIONAL_HEALTH_CHANGED`

Firmware maintenance commands 32–37 are intentionally not exposed as Android application commands. The cross-contract gate verifies that firmware rejects this maintenance range over the BLE application API.

## Selective v0.9.3 review

Accepted concepts/deltas:

- BLE Radar operator visualization, adapted to firmware-provided node radar data rather than a second phone-side scanner.
- read-only weighted Link Health presentation for operators; it never feeds VANGUARD or changes route selection.
- BLE permission-race robustness already present in the retained discovery transport.

Rejected from canonical 1.0.4:

- wholesale merge of any v0.9.3 branch;
- experimental opcode assignments such as `FIND_DEVICE(28)`, `RUN_SELF_TEST(29)`, `GET_HEALTH(28)`, or alternative 28–31 layouts;
- v0.9.3 phone-side BLE proximity scanner as a core feature, because firmware 1.0.4 already provides node BLE Radar and dual scanners would blur semantics;
- contact database v6/favorite/tag/group/accent migration in this release, because it is not required for firmware parity and adds persistence migration risk before BLE hardware qualification.

## Verification gates

`tools/domain_alignment_gate.py` verifies architectural invariants and retention of the v0.9.2 feature surface.

`tools/ble_contract_gate.py --firmware <exact .ino>` cross-checks firmware and Android command/event/status maps, UUIDs, packet and fragmentation constants, payload sizes, and capability bits.

CI must also pass JVM unit tests, Android lint, and `assembleDebug` before an APK is considered a release candidate.

## Evidence status

A green CI build is `NATIVE TESTED`, not `HARDWARE TESTED`. Android↔ESP32 discovery, pairing, MTU/subscription, authenticated INFO, `PROTOCOL_READY`, fragmentation/reassembly, old commands 1–27, and new commands 28–31 must still be exercised on the real phone/node pair before hardware qualification is closed.
