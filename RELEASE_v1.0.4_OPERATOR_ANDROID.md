# SecureMesh Android 1.0.4 OPERATOR

This Android release is paired with `SecureMesh_v1_0_4_OPERATOR.ino`.

## Compatibility

- Android version: `1.0.4-operator-android`
- Firmware: `SecureMesh v1.0.4 OPERATOR`
- BLE service/characteristic UUIDs: unchanged
- BLE fragment transport: version `1`
- SecureMesh BLE application packet: version `2`
- Preferred MTU: `185`
- Maximum application packet: `384` bytes
- Maximum field-test/message payload: `70` bytes

The Android code keeps the historical `SecureMeshBleProtocolV01Codec` class name only to avoid a risky source-wide rename. Its application envelope is v2 in this release.

## Operator UI

Normal screens avoid transport/radio jargon. The link-check screen shows delivery, loss, reliability and response speed rather than PDR/RTT/RSSI/SNR/ACK terminology. Detailed GATT/MTU/request/reassembly information is visible only when Developer Mode is enabled.

## Scope of this release

The stable BLE commands already supported by the native Android transport remain enabled:

- device info/status;
- neighbor list;
- route list;
- messaging;
- static route operations;
- link/field test;
- local ping/stat reset.

Firmware v1.0.4 also exposes newer commands (UI OS, VANGUARD/manifest, GPS/SOS, BLE radar, operational health and self-diagnostics). Those opcodes are intentionally not advertised by the Android domain layer until their native parsers/transport mappings are implemented and tested. This prevents a screen from appearing functional while its real BLE action is not yet wired.

## Build gate

GitHub Actions must pass:

1. architecture/domain alignment gate;
2. JVM unit tests;
3. `assembleDebug` Android build;
4. APK existence check.

The produced debug APK is Android-debug signed and suitable for direct development/test installation. It is not a Play Store production-signed artifact.
