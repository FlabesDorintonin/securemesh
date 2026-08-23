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

## Native firmware v1.0.4 coverage

The native Android transport now includes the stable command set plus the operator-facing v1.0.4 extensions needed by the current application:

- device info/status;
- neighbor list;
- route list;
- messaging;
- static route operations;
- link/field test;
- local ping/stat reset;
- GPS position synchronization for the existing offline map;
- SOS receive/acknowledge flow using the existing native alert overlay;
- BLE Radar state;
- Operational Health;
- Self Diagnostics.

VANGUARD route-source mapping is aligned to current firmware semantics: route source `2/3` is treated as dynamic/VANGUARD and source `4` as static.

Operational Health, Self Diagnostics and BLE Radar are represented by typed domain models instead of raw byte arrays or JSON. GPS/SOS reuse the existing node/position and alert domain surfaces, so UI code does not access GATT/opcodes directly.

## Final QA gate

The release candidate is accepted only when all of the following pass on the materialized source tree:

1. architecture/domain alignment gate;
2. JVM unit tests, including v1.0.4 binary payload parsing and route mapping;
3. full `assembleDebug` Android build with Kotlin/Compose/Room/KSP;
4. APK existence and SHA-256 check;
5. source ZIP integrity check;
6. local post-CI inspection of APK ZIP integrity/signature block and required source files.

The produced debug APK is Android-debug signed and suitable for direct development/test installation. It is not a Play Store production-signed artifact.

## Hardware validation boundary

CI and local artifact verification prove software build/protocol parsing consistency, not RF performance on physical hardware. Final end-to-end field validation still requires an Android phone plus flashed ESP32-S3 nodes to exercise real BLE pairing, GPS/SOS events and multi-radio routing in the field.
