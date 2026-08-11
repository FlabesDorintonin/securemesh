# SecureMesh Android Domain Alignment — repair report

This revision repairs the existing SecureMesh Android foundation in place. The transport/repository/UI boundary was preserved; domain truth, identity, permissions, message semantics and privacy were aligned with the actual SecureMesh direction.

## 1. Architectural errors corrected

- Removed hardcoded A/B/C/`COMMANDER A` identity assumptions from source.
- Application surface is now `SecureMesh`; the legacy Java package remains only for compatibility.
- Separated node facts from directional radio-link facts.
- Removed topology screen coordinates from domain.
- Stopped treating hop ACK as end-to-end delivery proof.
- Made route metrics optional rather than synthetic.
- Separated current-firmware mock behavior from future-product demo behavior.
- Added centralized session-driven visibility/projection rules.
- Added local-history ownership so cached data cannot silently cross authenticated local-node identities.
- Fixed a Demo startup race where repository `stateIn/flatMapLatest` projection could still expose the previous/null profile immediately after `launchDemo()` returned.
- Preserved BLE isolation: screens/ViewModels do not call Android GATT/scanner APIs.

## 2. New Node identity model

`NodeIdentity(nodeId, displayName, role, firmwareVersion?, protocolVersion?, capabilities)`.

`nodeId` is the stable SecureMesh ID. BLE address remains `DiscoveredDevice.address` transport metadata only. `secureMeshNodeId` is nullable until actual SecureMesh evidence/handshake exists.

## 3. Role model

`NodeRole` currently supports:

`MEMBER`, `RELAY`, `TEAM_LEADER`, `OPERATOR`, `COMMANDER`, `ADMIN`.

Role describes operational purpose. No privileged UI operation is authorized by a `role == COMMANDER/ADMIN` condition.

## 4. Permission model

`SessionPermission` represents concrete session grants: messaging, own/team position, topology, routes, field tests, logs, node/network management, SOS, etc.

`SecureMeshSession.can(permission)` requires an authenticated session and an explicit grant.

`UiAccessPolicy` is presentation/defense-in-depth only. Firmware remains the security authority for privileged commands.

## 5. Capability model

`DeviceCapability` describes what the local node technically supports: messaging, GPS, relay, SOS, field test, routing, diagnostics, OTA and sensors.

UI access often requires both a capability and a permission. Example: GPS hardware support alone does not grant team-map access.

## 6. SecureMeshSession

The new session model contains:

- `localNodeIdentity`;
- `SecureSessionConnectionState`;
- `AuthenticationState`;
- `grantedPermissions`;
- connected time;
- firmware/protocol/capabilities from local identity.

`BLE_CONNECTED` and `SECURE_SESSION_ESTABLISHED` are explicitly different states. Real `BleTransport` does not fabricate an authenticated session before the firmware handshake/authentication contract exists.

## 7. MeshNode vs MeshLink

`MeshNode` now contains identity/online/lastSeen and optional node telemetry/position.

`MeshLink` contains `fromNode`, `toNode`, RSSI, SNR and optional PDR/retries/lastSeen. Links are directional, so X→Y and Y→X may have different radio metrics.

Node Details may display derived link summaries, but RSSI/SNR/PDR are no longer intrinsic node properties.

## 8. Message vs HopTrace

`MeshMessage` holds origin, destination, payload, creation time, priority, progress state, final knowledge and conversation metadata.

`TransmissionHop` holds hop-specific frame/ACK/retry/RSSI/SNR/time information.

CURRENT v0.5 transitions successful hop ACK observations to `FINAL_CONFIRMATION_PENDING` with `finalState = UNKNOWN`. Only a profile/protocol that explicitly provides end-to-end confirmation may produce `DELIVERED`.

## 9. Local node concept

Every authenticated `SecureMeshSession` identifies the ESP32 directly connected to the phone as `localNodeIdentity`.

Message origin and Field Test source derive from that node. The mock transport rejects a Field Test whose source does not equal the authenticated local node ID.

## 10. Trusted device identity

`TrustedDeviceEntity` is semantically keyed by SecureMesh `nodeId`, not BLE MAC.

Legacy development rows that look like BLE MAC addresses are cleared rather than reinterpreted as SecureMesh identities. Auto-reconnect searches by `DiscoveredDevice.secureMeshNodeId`.

Room's legacy table/column name is retained to avoid an unnecessary destructive schema rewrite during this architectural repair.

## 11. CURRENT_FIRMWARE_V05 mock

Designed to stay honest to the current SecureMesh v0.5 stage:

- direct + static routes only;
- routed relay path;
- hop ACK/retry;
- directional RSSI/SNR;
- no GPS positions;
- no dynamic routing;
- no fabricated end-to-end delivery ACK;
- no fabricated battery/voltage/uptime;
- no fabricated aggregate PDR/retry link metrics;
- no fabricated route quality/hop count/age;
- field test exposes per-hop observations but leaves E2E PDR/received/lost unknown when unsupported.

## 12. FUTURE_DEMO mock

Separately demonstrates future architecture:

- GPS/map;
- SOS;
- dynamic routing;
- richer node telemetry/diagnostics;
- broader session permissions;
- explicit synthetic end-to-end delivery confirmation.

The UI exposes which demo profile is active so presentation behavior cannot be mistaken for current firmware behavior.

## 13. Intentionally deferred to Firmware ↔ Android BLE Contract

Not invented in this revision:

- production Service UUID and characteristic UUIDs;
- production advertisement/manufacturer identity encoding;
- cryptographic device identity proof;
- pairing/authentication/key lifecycle;
- capability/permission synchronization packet format;
- real command/event frame codec;
- fragmentation/MTU policy;
- end-to-end delivery ACK semantics;
- GPS frame/schema;
- real route telemetry and dynamic routing protocol;
- privileged command error/authorization responses;
- OTA/security-management protocol.

Those items must be frozen with firmware first and then implemented below `BleTransport`/`SecureMeshBleCodec` without moving BLE/protocol logic into UI.

## Additional privacy repair

Local Room history is scoped to authenticated `localNodeId`. Session-sensitive reads **and writes** require the current authenticated identity to match the DataStore history owner. When identity changes, the old session-sensitive history is cleared before the new owner is assigned. This conservative approach preserves the existing Room schema while preventing cross-identity leakage.
