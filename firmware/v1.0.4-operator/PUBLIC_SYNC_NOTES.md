# SecureMesh v1.0.4 OPERATOR — public GitHub sync notes

This directory is the public source synchronization of the exact release bundle:

- artifact: `SecureMesh_v1_0_4_OPERATOR_FINAL.zip`
- SHA-256: `9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`
- release identity: SecureMesh v1.0.4 OPERATOR

## Intentional differences from the exact ZIP

The executable logic, VANGUARD headers, tests and Commander/LabPanel sources are copied from the exact release bundle, with only the following publication-safe changes:

1. The real LAB `DEVELOPMENT_GROUP_KEY` was removed from tracked source.
   `SecureMesh_v1_0_4_OPERATOR.ino` now includes local `SecureMeshSecrets.h`.
   Copy `SecureMeshSecrets.example.h` to that name and configure the LAB key locally.
   `SecureMeshSecrets.h` is ignored by Git.
2. Stale documentation-only version labels were corrected:
   - `TEST_PLAN_3_RADIOS_RU.md`: v1.0.1 heading → v1.0.4 OPERATOR.
   - `APP_TEST_PANEL_PROTOCOL.md`: v1.0.3 current-build labels → v1.0.4.
   These corrections do not change firmware or wire behavior.
3. Release screenshots (`screenshots/desktop.png`, `screenshots/mobile.png`) are not copied into the Git source tree; they remain evidence/media in the exact ZIP.
4. This note and the local-secret template were added for traceability.

The exact ZIP remains the release artifact used for identity/evidence. The public tree is a sanitized synchronization and therefore is not byte-identical to that ZIP.

## Verification boundary

Independent verification on 2026-08-26:

- ZIP SHA-256 matches the registered release SHA.
- VANGUARD native regression: 10/10 compile + run PASS with g++ 14.2 and clang++ 17.
- invariant fuzz: PASS, 200000 operations, seed `0xC001D00D`.
- operator vocabulary check: PASS.
- Operational Intelligence check: PASS.
- Signal Intelligence check: PASS.
- LabPanel `app.js` / `sw.js` syntax: PASS.
- `manifest.webmanifest` JSON: PASS.

Full Arduino/ESP32-S3 compile/flash and physical three-node qualification remain a separate P0 gate.
