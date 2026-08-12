# SecureMesh Android BLE Protocol v0.1 Integration Report

## Scope

This integration connects the existing Android architecture

`UI -> ViewModel -> Repository -> Transport -> { MockTransport, BleTransport }`

to the SecureMesh firmware BLE control protocol defined by the supplied `SecureMesh_BLE_Protocol_v0_1.md` and firmware bundle. The UI was not rebuilt and Bluetooth GATT access remains inside `BleTransport`.

## Wire protocol source of truth

No JSON or application-defined substitute protocol is used.

Implemented constants and layouts come from the supplied protocol document:

- SecureMesh service UUID: `7b7f0001-6b6f-4d65-7368-534543555245`
- INFO: `7b7f0002-6b6f-4d65-7368-534543555245`
- COMMAND: `7b7f0003-6b6f-4d65-7368-534543555245`
- RESPONSE: `7b7f0004-6b6f-4d65-7368-534543555245`
- EVENT: `7b7f0005-6b6f-4d65-7368-534543555245`
- application envelope: 10 bytes, little-endian multibyte fields, magic `0x4D53`, protocol version `1`
- transport fragment header: 12 bytes, magic `0x4653`, protocol version `1`
- maximum application packet: 384 bytes
- maximum fragment data: 180 bytes
- maximum fragment count: 48
- incomplete reassembly timeout: 3000 ms
- preferred MTU: 185; MTU 23 remains supported

## Discovery and identity

A scan result becomes a `SECUREMESH_CANDIDATE` only when its advertisement contains the exact SecureMesh service UUID. A name containing `SecureMesh` is not treated as identity evidence.

Stable trust identity is the authenticated `nodeId` returned by INFO. BLE MAC/address is stored only as optional transport metadata. Auto-reconnect may use the last BLE address as a hint, but trust is accepted only after the authenticated INFO handshake returns the expected `nodeId`.

Unknown BLE devices are hidden during normal operation and can only be shown when developer mode and the existing `showUnknownBle` setting are both enabled.

## Connection state machine

The real BLE path is:

1. scan;
2. GATT connect;
3. service discovery;
4. exact SecureMesh service and characteristic verification;
5. Android system BLE Secure Connections bonding;
6. user enters the six-digit firmware/OLED passkey in Android's system pairing UI;
7. request MTU;
8. subscribe RESPONSE;
9. subscribe EVENT;
10. read INFO;
11. validate application envelope, protocol version, authenticated security flag and `PROTOCOL_READY` firmware state;
12. map INFO identity/capabilities/permission placeholders into the existing domain session;
13. perform initial GET_STATUS / GET_NEIGHBORS / GET_ROUTES / GET_FIELD_TEST_STATUS sync;
14. expose `SECURE_SESSION_ESTABLISHED` to the app.

A plain GATT connection is not a SecureMesh session. `ProtocolUnavailableScreen` is reserved for a missing/incomplete SecureMesh v0.1 service or an unsupported protocol contract.

## System pairing

There is no custom PIN command and no hardcoded PIN. `BleTransport` starts/observes the Android system bonding flow. The application only explains that the code shown on the ESP32 OLED must be entered into the Android system pairing dialog.

## Codec and validation

`SecureMeshBleProtocolV01Codec` performs:

- magic validation;
- protocol version validation;
- packet type validation;
- payload length validation;
- maximum packet bounds checks;
- exact per-command/event payload checks;
- little-endian decoding;
- safe retention/rejection of unknown opcode/event/status values without inventing semantics;
- malformed frame rejection.

The implemented domain command bridge covers:

- GET_INFO;
- GET_STATUS;
- GET_NEIGHBORS;
- GET_ROUTES;
- SEND_MESSAGE;
- ADD_STATIC_ROUTE;
- REMOVE_STATIC_ROUTE;
- START_FIELD_TEST;
- STOP_FIELD_TEST;
- GET_FIELD_TEST_STATUS.

PING_LOCAL and CLEAR_STATS are represented by the codec because they exist in protocol v0.1, but no new unrelated Android domain API was introduced for them.

## Fragmentation and request correlation

COMMAND packets are fragmented according to negotiated MTU using `min(180, MTU - 3 - 12)` payload bytes per transport fragment. RESPONSE and EVENT notifications are reassembled independently.

The reassembler enforces the firmware v0.1 sequential rules: one active assembly, first fragment index 0/offset 0, constant transport id/count/total length, exact next fragment index, exact next byte offset, and bounds checks. Out-of-order, overlapping, gapped, oversized, malformed and timed-out assemblies are rejected and counted in diagnostics.

A bounded request manager tracks up to 16 pending requests. Non-zero 16-bit request IDs increment and wrap safely. Only RESPONSE can complete a pending request; EVENT cannot. Disconnect fails all pending requests with a connection error.

## Messages

`SEND_MESSAGE -> OK` is interpreted only as acceptance into the local firmware TX queue. It does not produce an end-to-end delivery result.

`HOP_ACK` updates the corresponding first-hop transmission state but leaves ordinary outgoing message final state `UNKNOWN`. The Android app does not display a hop ACK as `DELIVERED`.

`MESSAGE_LOCAL_RECEIVED` represents a message that reached the locally attached node and is mapped into the existing message domain model as received locally.

## Field Test

The Android Field Test projection keeps first-hop reliability and end-to-end diagnostics separate.

Displayed real firmware values include, when present:

- requested probes;
- sent probes;
- first-hop ACK count;
- first-hop final failures;
- first-hop retry timeouts;
- E2E DIAG_PONG replies;
- E2E timeouts/loss;
- E2E PDR;
- RTT min/avg/max from DIAG_PONG;
- average first-hop RSSI;
- average first-hop SNR;
- target;
- current first nextHop.

A first-hop ACK is never counted as E2E success. E2E success comes from DIAG_PONG/status counters only.

## Capabilities

The real v0.6 capability mask maps only the v0.1-defined bits:

- messaging;
- static routing;
- relay;
- field test;
- BLE control.

REAL BLE does not advertise GPS, SOS, dynamic routing or OTA as available unless a future protocol explicitly defines and reports them. MockTransport and Future Demo remain separate from REAL BLE.

## Trust migration

Room schema version is incremented and a migration creates `trusted_devices` with:

- `nodeId` primary key;
- `displayName`;
- optional `lastSeenBleAddress`;
- `trustedAtEpochMs`;
- `firmwareVersion`;
- `protocolVersion`.

Legacy MAC-shaped entries are not promoted into SecureMesh identity. Existing unrelated database tables are preserved; no destructive migration is requested.

## Diagnostics

REAL BLE diagnostics expose:

- authenticated device nodeId;
- BLE address as transport metadata;
- GATT/handshake state;
- bonded state;
- protocol version;
- firmware version;
- negotiated MTU;
- RESPONSE subscription state;
- EVENT subscription state;
- SecureMesh session state;
- last command requestId;
- last response summary;
- reassembly error count;
- malformed application packet count.

## Automated verification

The project gate and JVM tests cover protocol headers, wrong magic/version/lengths, fragmentation/reassembly rules, request correlation, timeout, EVENT vs RESPONSE separation, service-UUID identity, capability mapping, and first-hop ACK vs E2E Field Test semantics. GitHub CI must pass JVM unit tests and `assembleDebug` before merge.

## Physical hardware verification status

A physical ESP32 v0.6/v0.6.1 + Android BLE smoke test cannot be executed by the repository CI environment. It must be performed on hardware after installing the generated APK.

Required hardware sequence:

`scan -> connect -> OLED passkey -> Android system pairing -> service discovery -> INFO -> PROTOCOL_READY -> GET_STATUS -> GET_NEIGHBORS -> GET_ROUTES -> SEND_MESSAGE -> START_FIELD_TEST`

During first hardware runs, use the in-app BLE diagnostics screen to capture GATT state, bond state, MTU, subscriptions, protocol/firmware versions, last request/response, reassembly errors and malformed packet count.
