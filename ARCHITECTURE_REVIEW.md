# Architecture review — SecureMesh Android Domain Alignment

## Verdict

The existing application architecture was worth preserving. The main weakness was not layering; it was **domain truth**. This repair keeps the clean UI → ViewModel → Repository → Transport boundary and replaces commander-demo assumptions with identity/session/link/message models that can survive the real firmware protocol.

## Strong points after repair

1. **One application, many access levels.** Navigation/features derive from authenticated session capabilities and permissions, not from a second commander-only app.
2. **Role is descriptive, not authorization.** No privileged path is based on `role == COMMANDER`.
3. **Local identity is explicit.** The phone always knows which SecureMesh node is directly attached over BLE once a session exists.
4. **BLE address is transport metadata.** Trust/reconnect identity uses SecureMesh node ID; legacy MAC-shaped trust records are discarded.
5. **Node/link domain is physically correct.** RSSI/SNR/PDR live on directional links, allowing asymmetric radio conditions.
6. **Message delivery semantics are honest.** Hop ACK does not manufacture end-to-end `DELIVERED`.
7. **Unknown stays unknown.** Optional telemetry/route metrics are not filled with attractive fake numbers.
8. **Topology is presentation-independent.** Domain contains network nodes/links only; graph positions belong to Compose.
9. **Current and future demo truth are separated.** v0.5 engineering mode does not silently inherit GPS/SOS/dynamic routing/E2E ACK from future mode.
10. **Privacy is defense-in-depth.** Screen/search projections are permission-aware, and local Room history is owned by authenticated local identity for both reads and writes.
11. **Real BLE remains honest.** Scanner/GATT/service discovery are real Android infrastructure, but an authenticated SecureMesh session is not fabricated until protocol/auth exists.
12. **Repair remained incremental.** Existing feature modules, Room schema, repository boundary and application package were preserved unless semantics required repair.
13. **Demo launch API is synchronized.** `launchDemo()` now returns only after repository projections have switched to the authenticated mock profile/session, eliminating a real transient-null race found by executable tests.

## Important non-security statement

`UiAccessPolicy` is not a security boundary against a hostile client. It only ensures the official Android UI does not accidentally expose data/controls outside the session projection. ESP32 firmware must authenticate the session and authorize every privileged command independently.

## Deliberate compatibility choices

### Legacy package name

`dev.securemesh.commander` is retained to avoid a destructive package/applicationId rewrite. The application surface name is `SecureMesh`, and domain models no longer assume commander access.

### Legacy Room trusted table/column

The existing development database table/column names are retained, but Kotlin semantics now use SecureMesh `nodeId`. A BLE-MAC-looking legacy trust record is deleted rather than upgraded.

### Local history ownership

Instead of adding a risky Room migration without an Android SDK build gate, session-sensitive history is conservatively cleared when authenticated local node identity changes. Reads/writes are blocked unless identity matches the DataStore owner. A later schema migration can namespace history per confirmed node ID.

## Remaining risks requiring hardware/Android validation

1. Physical BLE scanning behavior across Android vendors and permission states.
2. GATT connection/service discovery timing on the actual ESP32-S3 firmware.
3. Background/lifecycle behavior when Bluetooth is toggled during connect/scan.
4. Real authenticated pairing/identity flow — intentionally absent until firmware contract.
5. Real GATT MTU/fragmentation/notification pressure and reconnect behavior.
6. Compose instrumentation and layout validation under multiple screen sizes/font scaling.

These cannot be honestly closed in the current sandbox because it has no Android SDK/device runtime.

## Next integration milestone

Freeze `DEFERRED_FIRMWARE_CONTRACT.md` with firmware implementation details, then connect the real protocol only through `BleProtocolConfig`, `SecureMeshBleCodec`, `PairingController`, `BleTransport` and repository mapping.
