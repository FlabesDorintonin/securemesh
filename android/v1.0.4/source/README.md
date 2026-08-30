# SecureMesh — Android Domain Alignment

`SecureMesh` is the offline-first Android application for the SecureMesh network. This revision is an architectural repair of the existing SecureMesh Commander foundation, not a rewrite.

The core change is conceptual: the Android app is **one application for any SecureMesh participant**. A phone connects over BLE to the user's local ESP32-S3 node. The authenticated SecureMesh session then tells the UI which identity, capabilities and permissions actually exist. A commander can receive a broad network view; a normal participant can receive only the data allowed for that session.

The Android application is deliberately **not the security authority**. UI visibility is only presentation/defense-in-depth. Firmware must independently authorize every privileged network command.

## Revision

- Application surface name: `SecureMesh`
- Version: `0.2.0-domain-alignment`
- Android package/applicationId remains `dev.securemesh.commander` for compatibility with the existing project and local database. The package name is legacy technical metadata, not the application role model.
- Baseline architecture preserved: Compose UI → ViewModel → Repository → Transport → Mock/BLE.

## What this repair fixes

The previous foundation had several useful screens and a good transport boundary, but its domain model still reflected a commander-style demo:

- local identity could be mentally coupled to `A / COMMANDER A`;
- BLE address was too close to trusted identity semantics;
- RSSI/SNR/PDR/retries were mixed into node state;
- topology carried UI coordinates;
- hop ACK could lead to a fake `DELIVERED` impression;
- route metrics looked authoritative even when firmware did not provide them;
- Demo Mode mixed v0.5 truth with future GPS/SOS/dynamic-routing behavior;
- navigation filtering alone was not enough to protect locally cached data from accidental cross-session presentation.

This revision aligns those areas with the real SecureMesh architecture.

## Architecture

```text
┌──────────────────────────────────────────────┐
│               Jetpack Compose UI             │
│ dynamic navigation + permission projections │
└──────────────────────┬───────────────────────┘
                       │
┌──────────────────────▼───────────────────────┐
│                  ViewModels                  │
│ feature-scoped StateFlow                    │
└──────────────────────┬───────────────────────┘
                       │
┌──────────────────────▼───────────────────────┐
│            SecureMeshRepository              │
│ session / nodes / links / messages / routes │
│ local history / reconnect / exports         │
└───────────────┬──────────────────┬───────────┘
                │                  │
       ┌────────▼────────┐  ┌──────▼────────────┐
       │ TransportRouter│  │ Room + DataStore  │
       └────────┬────────┘  │ session-scoped   │
                │           │ local persistence│
        ┌───────┴────────┐  └──────────────────┘
        │                │
┌───────▼────────┐ ┌─────▼────────────┐
│ MockTransport │ │   BleTransport   │
│ CURRENT/FUTURE│ │ Android BLE/GATT │
└────────────────┘ └────────┬─────────┘
                            │
                 ┌──────────▼──────────┐
                 │ BleProtocolConfig   │
                 │ DeviceMatcher       │
                 │ SecureMeshBleCodec  │
                 │ PairingController   │
                 └─────────────────────┘
```

Hard boundary: feature UI never accesses `BluetoothGatt`, `BluetoothLeScanner` or `ScanCallback`.

## Identity model

### `NodeIdentity`

```text
nodeId
├─ displayName
├─ role
├─ firmwareVersion?
├─ protocolVersion?
└─ capabilities
```

`nodeId` is the stable SecureMesh identity. BLE MAC/address remains transport metadata only.

`DiscoveredDevice.secureMeshNodeId` is nullable and may be populated only by approved advertisement metadata or a future SecureMesh identity handshake. It is never inferred from the BLE address.

## Role, capability and permission are different things

### Role

Describes operational purpose:

`MEMBER`, `RELAY`, `TEAM_LEADER`, `OPERATOR`, `COMMANDER`, `ADMIN`.

### `DeviceCapability`

Describes what the attached node technically supports, for example:

`MESSAGING`, `GPS`, `RELAY`, `SOS`, `FIELD_TEST`, `ROUTING`, `NETWORK_DIAGNOSTICS`, `OTA`, `SENSORS`.

### `SessionPermission`

Describes what the currently authenticated session may request/display, for example:

`SEND_MESSAGE`, `VIEW_OWN_POSITION`, `VIEW_TEAM_POSITIONS`, `VIEW_NETWORK_TOPOLOGY`, `RUN_FIELD_TEST`, `VIEW_SYSTEM_LOG`, `MANAGE_ROUTES`, `MANAGE_NODES`, `MANAGE_NETWORK`.

**Role != permission. Capability != permission.** A commander role does not automatically grant a command, and a GPS-capable device does not automatically grant access to team positions.

`UiAccessPolicy` uses both capability and session permission for presentation. Firmware must re-check privileged operations independently.

## SecureMesh session

`SecureMeshSession` contains:

- `localNodeIdentity` — the ESP32 directly attached to this phone;
- `connectionState` — BLE link versus established secure session;
- `authenticationState`;
- `grantedPermissions`;
- capabilities/protocol/firmware inherited from identity;
- `connectedSinceEpochMs`.

Important distinction:

```text
BLE GATT CONNECTED
        ≠
SECUREMESH SESSION ESTABLISHED
```

The real `BleTransport` currently never fabricates `SecureMeshSession`. With no approved firmware contract it can honestly stop at BLE connected / protocol not configured.

## Node vs directional link

`MeshNode` contains node facts only:

- identity;
- online state;
- last seen;
- optional uptime/battery/voltage;
- optional position.

`MeshLink` contains directional radio observations:

- `fromNode` / `toNode`;
- RSSI;
- SNR;
- optional PDR;
- optional retries;
- optional last seen.

`SM-X → SM-Y` and `SM-Y → SM-X` are independent links. Node screens may derive a display summary from links, but RSSI is not intrinsic node truth.

## Topology

Domain topology contains only:

```text
MeshTopology
├─ List<NodeId>
└─ List<MeshLink>
```

There are no screen `x/y` coordinates in the domain. Compose computes graph layout locally and can be replaced by a stronger layout algorithm later without changing firmware/network models.

## Messages and hop trace

`MeshMessage` describes end-to-end message intent and final knowledge.

`TransmissionHop` describes each observed hop:

```text
from → to
frameId?
ACK state
retries?
RSSI?
SNR?
time
```

SecureMesh v0.5 has hop-by-hop ACK behavior, but hop ACK is not proof of end-to-end delivery. Therefore CURRENT v0.5 mock uses:

```text
HOP_PROGRESS
→ FINAL_CONFIRMATION_PENDING
→ finalState = UNKNOWN
```

Only Future Demo, which explicitly simulates an end-to-end confirmation, may set `DELIVERED`.

## Routes

Current types:

- `DIRECT`
- `STATIC`

Future-ready types:

- `DYNAMIC`
- `STALE`
- `FAILED`

`hopCount`, `quality`, `updatedAtEpochMs` and `path` are optional. The UI prints `UNKNOWN` instead of inventing values the firmware did not supply.

## Local node

Every authenticated session has one `localNodeIdentity`:

```text
Phone
  │ BLE
  ▼
LOCAL ESP32 NODE
  │ LoRa / SecureMesh
  ▼
Remote mesh nodes
```

Messages originate from the local node. Field Test source is fixed to the local node; a caller cannot submit a different arbitrary source ID through MockTransport.

## Trusted device identity

Trusted metadata is keyed by SecureMesh `nodeId`.

BLE MAC is never primary trust identity. Legacy development rows that look like BLE MAC addresses are discarded rather than silently upgraded into a SecureMesh identity.

The Room table/column names are intentionally retained for development-database compatibility; Kotlin semantics now use `TrustedDeviceEntity.nodeId`.

## Privacy and local history

The repository projects network data through `UiAccessPolicy` for screens/search and also scopes local history to the authenticated `localNodeId`.

When authenticated local identity changes, session-sensitive history is conservatively cleared before the new owner is assigned. Reads and writes of events/messages/nodes/positions/field tests require the current authenticated session to match the history owner.

This preserves the old Room schema while preventing cross-node history leakage. A future schema may namespace history per cryptographically confirmed identity instead of clearing it.

## BLE discovery states

Discovery separates:

- `UNKNOWN_BLE`
- `SECUREMESH_CANDIDATE`
- `KNOWN_SECUREMESH`
- `TRUSTED_SECUREMESH`
- authenticated SecureMesh session (separate session state, not a discovery classification)

Bluetooth name is weak development evidence only. The future production identity path is Service UUID / manufacturer metadata / protocol handshake / cryptographic identity confirmation.

## Connection flow seam

Prepared flow:

```text
SCAN
→ DEVICE FOUND
→ BLE CONNECT
→ SERVICE DISCOVERY
→ SECUREMESH IDENTIFICATION
→ PAIRING / AUTHENTICATION
→ SESSION ESTABLISHED
→ CAPABILITY + PERMISSION SYNC
→ APP
```

Until the ESP32 BLE contract exists, the real transport stops honestly at the appropriate earlier stage.

## Demo profiles

### CURRENT_FIRMWARE_V05

Engineering mode that intentionally reflects current firmware limits:

- four stable SecureMesh IDs, no A/B/C identity contract;
- direct + static routing;
- routed path through a relay;
- directional RSSI/SNR;
- hop ACK + retry;
- no GPS positions;
- no real SOS system;
- no dynamic routing;
- no fabricated battery/voltage/uptime;
- no fabricated aggregate link PDR/retry metrics;
- no fabricated route quality/hop count/age;
- no end-to-end delivery claim after hop ACK;
- field-test per-hop telemetry, but end-to-end PDR remains `UNKNOWN` when no final confirmation exists.

### FUTURE_DEMO

Presentation/future architecture mode:

- GPS positions;
- map access;
- SOS;
- dynamic routes;
- richer diagnostics;
- broader permissions;
- synthetic end-to-end confirmation clearly separated from v0.5 truth.

The active profile is visible in the UI so engineering tests are not confused with future product behavior.

## Adaptive navigation

Navigation is session-driven rather than role-hardcoded.

A restricted session can expose HOME / local NODE / MESSAGES / MORE. A broader session may additionally expose NODES / MAP / Network / Routes / Field Test / Events / Diagnostics depending on capability + permission.

Direct navigation into a screen still applies the same projection policy; hiding a menu item is not the only privacy boundary.

## Field Test

- source = authenticated local node;
- target = selected remote node;
- per-hop telemetry is preserved when available;
- overall delivery values remain nullable when firmware cannot prove them;
- RSSI (dBm) and SNR (dB) use separate chart surfaces/scales.

## Local storage

Room stores local engineering history and trusted metadata. DataStore stores user preferences plus the internal history-owner node ID.

No cryptographic keys are stored in plaintext Room tables.

## Project structure

```text
app/src/main/java/dev/securemesh/commander/
├── core/       database / settings / map / export / UI
├── data/
│   ├── ble/    real Android BLE + protocol seams
│   ├── mock/   CURRENT_FIRMWARE_V05 + FUTURE_DEMO
│   ├── repository/
│   └── transport/
├── domain/
│   ├── model/
│   ├── repository/
│   └── service/UiAccessPolicy.kt
├── feature/    existing screens repaired to consume domain truth
└── navigation/ dynamic session-driven navigation
```

## Build stack

- compile/target SDK 36; min SDK 26
- AGP 8.13.2; Gradle 8.13
- Kotlin 2.3.20; KSP 2.3.11
- Compose UI/Foundation 1.11.4; Material 3 1.4.0
- Navigation Compose 2.9.8
- Lifecycle 2.10.0
- DataStore 1.2.1
- Room 3.0.1 + bundled SQLite 2.7.0
- Coroutines 1.10.2

## Build locally

Windows:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

Expected APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

On a connected device/emulator:

```bat
gradlew.bat connectedDebugAndroidTest
```

This generation sandbox has Java/Kotlin tooling but no Android SDK and cannot resolve the Gradle distribution host, so no APK is claimed from this environment. See `BUILD_ENVIRONMENT.md`.

## Verification

Run the architecture/domain truth gate:

```bash
python tools/domain_alignment_gate.py
```

Detailed results are recorded in:

- `QUALITY_GATE.md`
- `ARCHITECTURE_REVIEW.md`
- `DOMAIN_ALIGNMENT_REPORT.md`
- `DEFERRED_FIRMWARE_CONTRACT.md`

## Where firmware integration goes next

Do not place protocol parsing in screens/ViewModels. The future contract plugs into:

- `BleProtocolConfig`
- `SecureMeshDeviceMatcher`
- `SecureMeshBleCodec`
- `PairingController`
- `BleTransport`
- repository mapping from authenticated transport frames to domain models.

The goal is that approved firmware protocol details change transport/mapping code, not Dashboard/Map/Messages/Topology architecture.

### GitHub Android compile repair

The GitHub build revision also hardens Compose LazyList DSL usage found by the first real `compileDebugKotlin` run: lazy `item/items` declarations are now emitted directly from `LazyListScope` rather than from nested `let/forEach` blocks in Messages, Field Test, Search and More.
