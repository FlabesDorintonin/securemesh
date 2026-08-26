# SecureMesh v1.0.4 OPERATOR artifacts

This directory keeps durable release identities and the publication-safe firmware source archive.

Stored in Git:

- `SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip` — sanitized public firmware/VANGUARD/Commander source snapshot.
- `RELEASE_MANIFEST_v1.0.4.sha256` — hashes for the original release identity, public source snapshot and Android APK.

Not stored in public Git history:

- the original firmware ZIP, because it contains the LAB development group key;
- the exact Android APK binary, to avoid treating a chat-supplied debug binary as the long-term distribution channel.

The Android APK source and build lineage are versioned in this repository. Release identity is preserved by SHA-256 and Notion release records.
