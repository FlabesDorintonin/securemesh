# SecureMesh firmware source

The canonical publication-safe SecureMesh v1.0.4 OPERATOR firmware, VANGUARD,
native tests and Commander/LabPanel source is browsable at:

`firmware/v1.0.4-operator/`

Git tree identity for this v1.0.4 source:

`ced8e6fde50a59d514cba5d3dac0649124ea651f`

It was first materialized by verified CI in commit:

`eb5a863953f4f9b87d776e0011695da81dce0225`

## Reproducible recovery

The publication-safe release snapshot is stored losslessly in
`artifacts/v1.0.4/public-source-b64/` as 43 ordered Base64 text parts.
Decoded snapshot identity:

- size: `188879` bytes
- SHA-256: `baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`

Reproduce the tree using repository data only:

```bash
python3 tools/materialize_firmware_v1_0_4.py
```

The tool verifies part count, Base64 validity, decoded size, SHA-256, ZIP paths,
required source files and absence of `SecureMeshSecrets.h` before accepting the
materialized tree.

## Secrets

The original exact release bundle is identified by SHA-256
`9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`,
but is not published because it contains a LAB development group key.

The public tree contains `SecureMeshSecrets.example.h`; real
`SecureMeshSecrets.h` is local provisioning data and must remain untracked.

## Evidence boundary

The source tree and native regression are software evidence. Physical ESP32-S3
compile/flash, real three-node LoRa/BLE testing and Android↔ESP32 BLE E2E remain
separate P0 hardware evidence gates.
