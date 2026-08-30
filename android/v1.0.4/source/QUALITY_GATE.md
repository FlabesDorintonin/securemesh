# Final quality gate — SecureMesh Android Domain Alignment

## Result

**Domain/architecture gate: PASS — 57/57**

Command:

```bash
python tools/domain_alignment_gate.py
```

The gate checks the new repair requirements, including:

- repository/transport boundary retained;
- no GATT/scanner API in feature UI;
- no cloud/backend/analytics SDK;
- no hardcoded PIN/key;
- `NodeIdentity`, `NodeRole`, `DeviceCapability`, `SessionPermission`, `SecureMeshSession` present;
- no role-equals-authorization branch;
- BLE link and authenticated session separated;
- UI visibility explicitly documented as non-authoritative;
- no RSSI/SNR/PDR/retries/route truth inside `MeshNode`;
- directional `MeshLink`;
- topology has no screen coordinates;
- route telemetry optional;
- `MeshMessage` / `TransmissionHop` separated;
- hop ACK cannot manufacture final delivery;
- current v0.5 uses `UNKNOWN` final delivery when E2E confirmation is absent;
- Field Test source enforced as local node;
- trusted identity keyed by SecureMesh `nodeId`;
- BLE MAC not assigned as node identity;
- legacy BLE-MAC trust discarded;
- reconnect matches discovered SecureMesh identity;
- local history owned by authenticated SecureMesh identity;
- session-sensitive Room writes require the authenticated history owner;
- cross-identity history regression test exists;
- current/future demo profiles separated;
- repository Demo launch waits for coherent profile/session/nodes/connection projection before returning;
- CURRENT v0.5 does not fabricate GPS/node telemetry/aggregate PDR/route metrics;
- Future Demo contains future-only dynamic routing;
- mock scan bounded;
- unknown mock BLE cannot authenticate as SecureMesh;
- offline mock node does not keep refreshing `lastSeen`;
- centralized UI/privacy projections;
- map requires capability + position permission;
- map position visibility is independent from full node-list permission;
- dynamic primary navigation;
- real BLE codec remains unconfigured until firmware contract;
- real BLE transport never fabricates `SecureMeshSession`;
- real BLE scan bounded internally;
- disconnect has local cleanup fallback;
- Welcome BLE/Demo navigation race removed;
- Field Test RSSI/SNR use separate chart surfaces;
- no A/B/C/`COMMANDER A` source identity assumptions;
- no giant Kotlin god class;
- ViewModel `combine` regressions checked;
- required unit/Compose test sources retained.

Largest production Kotlin file at final review: `MeshModels.kt`, 389 lines.

## Executable pure-Kotlin checks

These were compiled and executed against the actual repaired domain/mock sources using the sandbox Kotlin/JVM toolchain plus Coroutines:

### Domain integration

**PASS — `DOMAIN_INTEGRATION_PASS`**

Verified at runtime:

- role does not imply permission;
- capability does not imply permission;
- directional link asymmetry survives;
- hop ACK final state is `UNKNOWN`;
- CURRENT v0.5 has no GPS/fake node telemetry/fake aggregate PDR/fake route metrics;
- routed current message reaches `FINAL_CONFIRMATION_PENDING`, not fake `DELIVERED`;
- wrong Field Test source is rejected;
- v0.5 Field Test E2E PDR remains unavailable;
- Future Demo exposes GPS/dynamic route and only then simulates explicit E2E confirmation.

### Mock lifecycle/hardening

**PASS — `MOCK_HARDENING_RUNTIME_PASS`**

Verified:

- mock scan enforces minimum bounded duration inside transport;
- unknown BLE device never becomes an authenticated SecureMesh session;
- offline relay `lastSeen` does not advance forever.

### Device matcher

**PASS — `MATCHER_RUNTIME_PASS`**

Verified that service/manufacturer/name evidence produces candidate classification without treating arbitrary BLE devices as SecureMesh identity.

### Repository privacy

**PASS — `REPOSITORY_PRIVACY_RUNTIME_PASS`**

Verified with an existing `SM-OLD` history owner:

- new authenticated identity becomes `SM-7C21`;
- no new-session event write occurs into the old owner's history before clear;
- old event is removed;
- new owner data becomes readable/persisted after ownership sync.

## JVM unit tests executed without Gradle

A minimal local JUnit-compatible runner was used to execute the project's existing nine JVM unit-test classes with real assertion behavior against the repaired source.

**PASS — `JVM_UNIT_RUNTIME_PASS tests=31 classes=9`**

This run caught a real regression during the repair: `SecureMeshRepositoryImpl.launchDemo()` could return before `flatMapLatest/stateIn` had projected the newly selected mock transport, so an immediate read could see `demoProfile == null`. The repository API was repaired to wait (bounded by 2 seconds) for a coherent authenticated Demo projection before returning. The full 31-test runtime run then passed.

## Compile-oriented checks without Android SDK

### Repository/data layer

**PASS — `REPOSITORY_KOTLIN_COMPILE_PASS`**

Repository, Room entities/DAO contracts, MockTransport, transport router and domain interfaces were compiled with minimal annotation/settings stubs. This catches Kotlin type errors independently of Android UI.

### All ViewModels

**PASS — `VIEWMODEL_KOTLIN_COMPILE_PASS (14 ViewModels)`**

This check is valuable because it already caught and repaired a real bug during this work: a six-flow `combine(...)` was previously written as if a six-parameter typed overload existed. It is now composed safely through grouped flows.

### Unit-test source

**PASS — `UNIT_TEST_SOURCE_COMPILE_PASS (9 files)`**

All JVM unit-test source files compile against the repaired domain/mock/repository sources using minimal JUnit/Room/settings stubs.

### BLE transport

**PASS — `BLE_TRANSPORT_STUB_COMPILE_PASS`**

`BleTransport`, matcher/config/codec and transport interface compile against minimal Android API stubs. This validates Kotlin/API-shape consistency of the repaired BLE lifecycle code, but it is **not** a substitute for an Android SDK build or physical BLE test.

### Whole Android Kotlin syntax pass

The complete `app/src/main/java` source set was passed through the Kotlin compiler without Android/Compose classpath and diagnostics were filtered only for parser/syntax-class failures.

**PASS — `ANDROID_KOTLIN_SYNTAX_CLASS_ERRORS=0`**

Unresolved Android/Compose references are expected in this parser-only environment and are not presented as a successful Android compile.

## Real Gradle / APK gate

Command was actually attempted:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Result:

```text
GRADLE_EXIT=6
curl: (6) Could not resolve host: services.gradle.org
```

The sandbox also has no Android SDK (`ANDROID_HOME` and `ANDROID_SDK_ROOT` empty; no `adb`/`sdkmanager`). No APK exists in the project tree after the attempt.

**Therefore no APK is claimed.**

## What still requires Mirek's Windows/Android environment

Run from the project root:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

Then, with a device/emulator:

```bat
gradlew.bat connectedDebugAndroidTest
```

And with a physical phone + ESP32-S3, validate real scan/connect/service-discovery lifecycle under Bluetooth toggles, permission denial/regrant and reconnect.
