# SecureMesh firmware source

The complete publication-safe SecureMesh v1.0.4 OPERATOR firmware/VANGUARD/Commander source is stored durably in this repository as:

`artifacts/v1.0.4/SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip`

Expected SHA-256:

`baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`

To materialize the browsable working tree from repository data only:

```bash
python3 tools/materialize_firmware_v1_0_4.py
```

The command creates `firmware/v1.0.4-operator/` after verifying the archive hash and archive paths.

## Secrets

The original exact release bundle is identified by SHA-256
`9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`, but is not committed because it contains a LAB development group key.

The public source archive contains `SecureMeshSecrets.example.h`, while the real `SecureMeshSecrets.h` must remain local and untracked.

## Evidence boundary

Materializing source does not qualify hardware. Physical ESP32-S3 compile/flash, three-node LoRa/BLE testing and Android↔ESP32 BLE E2E remain separate P0 evidence gates.
