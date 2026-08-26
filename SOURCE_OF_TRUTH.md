# SecureMesh — source of truth

This repository is the durable source of truth for SecureMesh code. Notion is the durable source of truth for project state, architecture decisions, evidence, roadmap and release interpretation.

## Current release

**SecureMesh v1.0.4 OPERATOR**

| Component | Canonical source |
| --- | --- |
| Android application | repository `main`, with Android final-QA lineage beginning at `8087909a33200b11ee5476f23a63bdf65b2b4c3e` |
| Firmware | `firmware/v1.0.4-operator/` |
| VANGUARD | `firmware/v1.0.4-operator/Vanguard*.h` plus native tests |
| Commander / LabPanel | `firmware/v1.0.4-operator/LabPanel/` |
| Public firmware source archive | `artifacts/v1.0.4/SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip` |
| Release hashes | `artifacts/v1.0.4/RELEASE_MANIFEST_v1.0.4.sha256` |

## Release artifact identities

- original firmware release ZIP: `SecureMesh_v1_0_4_OPERATOR_FINAL.zip` — SHA-256 `9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`;
- publication-safe source ZIP: `SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip` — SHA-256 `baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`;
- Android debug APK: `SecureMesh_v1_0_4_OPERATOR_ANDROID_FINAL.apk` — SHA-256 `83e2339bae482b90f1c393d7044b9f63b96ad2513e23783fed1e95463a255862`.

The original firmware ZIP is **not** committed because it contains a LAB development group key. That ZIP remains historical release evidence by hash only; the canonical maintained firmware source is the sanitized GitHub tree where the LAB key is injected locally through untracked `SecureMeshSecrets.h`.

The exact 25 MiB APK binary is also not stored in Git history. Its source, build lineage and SHA-256 identity are retained here and in Notion; future APKs must be produced by CI from versioned source rather than relying on chat attachments.

## Evidence boundary

Native/software PASS does not imply hardware qualification. Current P0 remains physical ESP32-S3 compile/flash, three-node LoRa/BLE/OLED/GPS validation, Android↔ESP32 BLE v2 E2E, multi-hop, Primary/G2, failover/recovery and GPS/SOS evidence.
