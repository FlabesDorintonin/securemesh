# Deferred Firmware ↔ Android BLE Contract

This document is intentionally a **contract checklist**, not an invented protocol. Values remain unset until ESP32 firmware and Android agree on them.

## 1. BLE discovery identity

Freeze:

- production Service UUID;
- manufacturer ID if used;
- advertisement byte layout;
- protocol version field;
- device type field;
- whether stable SecureMesh `nodeId` is advertised or revealed only after connection;
- privacy implications of exposing stable node identity in advertisements.

Android integration point: `BleProtocolConfig` + `SecureMeshDeviceMatcher`.

## 2. GATT service/characteristics

Freeze:

- command RX characteristic UUID/properties;
- event/telemetry TX characteristic UUID/properties;
- indication vs notification behavior;
- optional control/auth characteristics if separate;
- CCCD/subscribe sequence;
- service discovery validation rules.

Do not place UUIDs in feature/UI code.

## 3. Protocol framing

Freeze:

- frame header;
- protocol version;
- frame type;
- frame/message/correlation ID widths;
- payload length;
- integrity/authentication fields supplied by the security design;
- byte order;
- malformed-frame behavior;
- forward/backward compatibility policy.

Android integration point: production `SecureMeshBleCodec`.

## 4. MTU and fragmentation

Freeze:

- requested/required MTU;
- maximum application frame size;
- fragmentation and reassembly rules;
- fragment timeout;
- duplicate fragments;
- retransmission semantics;
- memory limits on ESP32 and Android.

## 5. SecureMesh device identification

Freeze how Android transitions from:

```text
BLE_CONNECTED
→ SECUREMESH_IDENTIFIED
```

Specify:

- identity payload;
- stable `nodeId` derivation/encoding;
- device/public identity material;
- protocol mismatch response;
- spoofing resistance expected from the future security layer.

BLE MAC must not become SecureMesh identity.

## 6. Pairing and authenticated session

Design separately with proper cryptographic review. Freeze:

- user-visible six-digit confirmation role, if retained;
- BLE platform security assumptions;
- device identity verification;
- key establishment/storage lifecycle;
- session expiry;
- reconnect/resume rules;
- trust revocation/reset;
- failed-attempt/rate-limiting behavior.

Android integration point: `PairingController` + `BleTransport` session state.

No hardcoded PIN/key belongs in the app.

## 7. Capability and permission sync

After authentication, firmware should provide authoritative session data:

- local `NodeIdentity`;
- firmware/protocol version;
- `DeviceCapability` set;
- granted `SessionPermission` set;
- optional expiry/revision/generation number.

Define how permissions can change while connected and how Android is notified.

Firmware must still validate each privileged command even after granting permissions to the UI.

## 8. Node/link telemetry

Freeze separate schemas for:

### Node facts

- online/last seen;
- uptime;
- battery/voltage;
- device capabilities/status.

### Directional link facts

- from/to node ID;
- RSSI;
- SNR;
- PDR if actually measured;
- retries if actually measured;
- sample/measurement time.

Do not send a single ambiguous `node RSSI` field.

## 9. Topology and routes

Topology should transmit network facts only:

- node IDs;
- directional links.

Never transmit Android screen coordinates.

For routes define which metrics are authoritative/available:

- destination;
- next hop;
- route type;
- optional hop count;
- optional path;
- optional quality metric and exact meaning;
- optional age/update time.

## 10. Messages and acknowledgements

Freeze distinctions between:

- application message ID;
- radio/frame ID;
- hop ACK/NACK/timeout;
- route/hop trace events;
- final end-to-end delivery confirmation, if/when implemented.

Do not map final hop ACK to E2E `DELIVERED` unless firmware defines an explicit end-to-end acknowledgement.

## 11. Conversations/channels

Foundation supports direct/group/team/system/command types. Firmware contract must later define:

- conversation/channel identifiers;
- authorized participant visibility;
- send permissions;
- command/system message semantics;
- security/keying model in a separate cryptographic design.

## 12. Field Test

Freeze:

- start/stop command;
- local source semantics;
- target;
- DIRECT/ROUTED/AUTO interpretation;
- packet index/test ID;
- per-hop ACK/retry/RSSI/SNR events;
- whether an end-to-end received/lost result exists;
- completion/cancellation/timeouts.

## 13. GPS/map

When firmware GPS API exists, define:

- node ID;
- coordinates;
- timestamp/time source;
- validity/fix status;
- satellites;
- HDOP/accuracy meaning;
- speed;
- stale/age behavior;
- authorization scope: own position vs team positions.

## 14. SOS

Freeze:

- SOS event identity;
- source node;
- priority/retry semantics;
- location freshness;
- acknowledgement semantics;
- authorization for ACK;
- deduplication and expiry.

## 15. Error model and timeouts

Map firmware responses to stable Android/domain errors:

- authorization required/denied;
- protocol mismatch;
- unsupported capability;
- route unavailable;
- node offline;
- command timeout;
- malformed frame;
- busy/rate-limited;
- internal firmware error.

## 16. Versioning

Define:

- protocol version negotiation;
- minimum/maximum supported version;
- capability-based optional features;
- behavior when Android is newer/older than firmware;
- explicit `NOT_SUPPORTED` rather than fabricated fallback values.

## Contract completion criterion

Only after these items are frozen should production UUIDs/codecs/authentication commands be implemented. The Android screens should consume resulting domain truth without learning BLE packet details.
