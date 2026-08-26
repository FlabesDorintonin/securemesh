# SecureMesh v1.0.4 OPERATOR artifacts

This directory keeps durable release identities and a lossless publication-safe
firmware source snapshot.

Stored in Git:

- `public-source-b64/` — the exact publication-safe source ZIP represented as
  ordered Base64 text parts; reconstruction is SHA-256 gated.
- `RELEASE_MANIFEST_v1.0.4.sha256` — identities of the original firmware ZIP,
  publication-safe source ZIP, and Android APK.

The publication-safe ZIP identity is
`baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`.
Run `python3 tools/materialize_firmware_v1_0_4.py` to reconstruct, verify and
materialize `firmware/v1.0.4-operator/` without relying on chat attachments.

Not stored as public binaries in the current tree:

- the original firmware ZIP, because it contains the LAB development group key;
- the Android debug APK, because CI/source lineage is the durable distribution path.

The original firmware and APK identities remain preserved by SHA-256 and Notion
release/evidence records. Secrets must never be committed.
