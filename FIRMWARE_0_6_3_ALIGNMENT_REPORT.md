# SecureMesh Android ↔ Firmware 0.6.3 Alignment Report

## Scope

This integration is built on the existing Android architecture:

`UI → ViewModel → Repository → MeshTransport → BleTransport / MockTransport`

No second BLE stack was introduced. The proven discovery, system pairing/bonding, authenticated GATT session, request manager, fragmentation and existing Protocol v0.1 command/event handling remain in place.

Firmware reference used for this alignment: `SecureMesh_v0_6_3_UI_OS_PRO(1).ino`.

## Core protocol compatibility

Firmware 0.6.3 keeps the existing SecureMesh BLE Protocol v0.1 transport contract:

- Service UUID and INFO/COMMAND/RESPONSE/EVENT characteristic UUIDs are unchanged.
- Application envelope remains 10 bytes.
- Transport fragmentation/reassembly remains unchanged.
- Preferred MTU remains 185.
- Existing command opcodes 1..12 remain unchanged.
- Existing event IDs 1..17 remain unchanged.
- Existing INFO / STATUS / NEIGHBORS / ROUTES / SEND_MESSAGE / Field Test payload layouts remain compatible.

Therefore no changes were made to the working Android BLE discovery/pairing/GATT architecture solely for firmware 0.6.3.

## Firmware 0.6.3 UI OS extension

Android now supports the firmware-defined UI OS extension:

- capability bit 5: `UI_OS`
- opcode 13: `GET_UI_STATE`
- opcode 14: `UI_ACTION`
- event 18: `UI_CHANGED`
- UI model version: 2
- actions: UP=1, DOWN=2, SELECT=3, BACK=4, HOME=5

The UI state payload is parsed as the exact 29-byte little-endian firmware structure. Android validates exact payload length and the authenticated local `nodeId` before publishing state into the domain layer.

`UI_ACTION` carries exactly one action byte. No extra Android-only action codes were invented.

## Domain integration

A `DeviceUiState` domain projection was added instead of exposing wire DTOs to UI.

The projection contains:

- scene / menu / feature;
- menu cursor / scroll / navigation depth;
- OLED ready and BLE protocol ready flags;
- field test / toast / planned-feature / unread flags;
- inbox, unread, neighbor and route counts;
- message / neighbor / route cursors;
- local node identity;
- field-test id and target.

Raw numeric scene/menu/feature values are retained where useful for diagnostics, while known firmware values are mapped to named domain enums.

## Security boundaries

The remote OLED controller is available only when:

1. a real authenticated SecureMesh session exists;
2. firmware advertises the `UI_OS` capability.

`GET_UI_STATE` and `UI_ACTION` still pass through the normal authenticated `BleTransport.command()` request pipeline. Firmware remains the authorization boundary.

Android does not treat BLE MAC/name as SecureMesh identity. Every UI state payload is rejected if its `localNodeId` does not match the authenticated session identity.

## UI behavior

A new `DeviceControlScreen` exposes the physical node UI OS as a remote control:

- current synchronized scene/menu/feature;
- firmware / node / protocol context;
- UP / DOWN / SELECT / BACK / HOME controls;
- live inbox/unread/neighbor/route counters;
- Field Test state;
- clear indication when firmware marks a feature as planned rather than active.

The phone does not fabricate a screen feature that firmware marks as planned.

`UI_CHANGED` events update Android state in realtime; explicit refresh uses `GET_UI_STATE`.

## Tests

Added/extended tests cover:

- INFO capability bit 5 mapping to `UI_OS`;
- exact GET_UI_STATE command bytes;
- exact UI_ACTION SELECT command bytes;
- invalid UI action rejection;
- exact 29-byte UI-state parsing;
- UI_CHANGED parsing;
- preservation of existing protocol/fragmentation/request-correlation guarantees.

GitHub Actions run #65 passed:

- architecture/domain alignment gate: PASS;
- JVM unit tests: PASS;
- Android `assembleDebug`: PASS;
- APK existence verification: PASS;
- APK/source artifact upload: PASS.

## Hardware validation still required

CI cannot replace a physical ESP32 + Android test. Required hardware sequence:

1. flash `SecureMesh_v0_6_3_UI_OS_PRO(1).ino`;
2. discover and connect from Android;
3. complete system passkey pairing;
4. reach authenticated `PROTOCOL_READY`;
5. confirm firmware reports version 0.6.3 and UI_OS capability;
6. open `Устройство` in Android;
7. verify initial GET_UI_STATE matches the OLED;
8. press UP / DOWN / SELECT / BACK / HOME and verify OLED changes;
9. verify Android updates after `UI_CHANGED` without manual refresh;
10. verify disconnect/reconnect clears and resynchronizes device UI state.

Until this physical test is completed, the firmware-alignment branch should remain separate from the release branch.
