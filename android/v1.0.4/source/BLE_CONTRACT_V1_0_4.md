# SecureMesh BLE application contract v1.0.4

- Application envelope: magic `0x4D53`, version `2`, header `10` bytes, max packet `384` bytes.
- Fragment transport: magic `0x4653`, version `1`, header `12` bytes, max fragment data `180`, max fragments `48`, max reassembled application packet `384`.
- Service UUID: `7b7f0001-6b6f-4d65-7368-534543555245`
- INFO: `7b7f0002-6b6f-4d65-7368-534543555245`
- COMMAND: `7b7f0003-6b6f-4d65-7368-534543555245`
- RESPONSE: `7b7f0004-6b6f-4d65-7368-534543555245`
- EVENT: `7b7f0005-6b6f-4d65-7368-534543555245`

Commands 1–27 retain their v0.9.2 numeric identity. Commands 28–31 are BLE Radar/Operational Intelligence additions. Event 32 is Operational Health Changed. Capability bits 0–14 are cross-checked against firmware by `tools/ble_contract_gate.py`.

The contract gate is authoritative for numeric parity; this document is a human-readable index, not a replacement for executable verification.
