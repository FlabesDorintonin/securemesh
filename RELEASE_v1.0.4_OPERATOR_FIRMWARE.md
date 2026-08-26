# SecureMesh Firmware v1.0.4 OPERATOR — public source sync

The firmware/VANGUARD/Commander release source was synchronized to GitHub after verification of the exact release bundle.

## Exact artifact identity

- artifact: `SecureMesh_v1_0_4_OPERATOR_FINAL.zip`
- SHA-256: `9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`
- release: `SecureMesh v1.0.4 OPERATOR`

## Public source path

`firmware/v1.0.4-operator/`

The public tree is intentionally not byte-identical to the ZIP because the LAB development group key is removed from tracked source. Documentation-only stale version labels were also corrected. Full details are in `firmware/v1.0.4-operator/PUBLIC_SYNC_NOTES.md`.

## Independent verification before sync

- exact ZIP SHA-256: PASS;
- 10/10 VANGUARD native tests: compile + run PASS with g++ 14.2 and clang++ 17;
- invariant fuzz: PASS, 200000 operations, seed `0xC001D00D`;
- operator vocabulary: PASS;
- Operational Intelligence: PASS;
- Signal Intelligence: PASS;
- LabPanel JavaScript syntax: PASS;
- LabPanel manifest JSON: PASS.

## Hardware boundary

This synchronization does not claim a full Arduino/ESP32-S3 build or physical qualification. The three-node LoRa + BLE + OLED/GPS + Android E2E test remains the P0 release gate.
