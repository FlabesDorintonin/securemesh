# SecureMesh v1.0.4 publication-safe source snapshot

This directory is the durable, lossless representation of the publication-safe
`SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip` release snapshot.

- decoded ZIP size: `188879` bytes
- decoded ZIP SHA-256: `baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`
- ordered parts: exactly `43` files named `part-000.txt` … `part-042.txt`
- encoding: standard Base64, concatenated in lexical filename order

The original exact firmware release ZIP remains identified by SHA-256
`9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`
but is intentionally not published because it contains a LAB development key.

Use `python3 tools/materialize_firmware_v1_0_4.py` to verify the decoded bytes,
reject unsafe paths/secrets, and materialize `firmware/v1.0.4-operator/`.

Do not edit individual parts. Any source change must be made in the versioned
firmware tree, tested, and released as a new snapshot/version rather than by
mutating the v1.0.4 evidence artifact.
