# SecureMesh — source of truth

This repository is the durable source of truth for SecureMesh code, tests,
versioned contracts and technical release lineage. Notion is the durable source
of truth for project state, architecture decisions, evidence, roadmap and
release interpretation. Chat attachments are not a source of truth.

## Current release

**SecureMesh v1.0.4 OPERATOR**

| Component | Canonical source |
| --- | --- |
| Android application | repository `main`; Android final-QA lineage begins at `8087909a33200b11ee5476f23a63bdf65b2b4c3e` |
| Firmware | `firmware/v1.0.4-operator/` |
| VANGUARD | `firmware/v1.0.4-operator/Vanguard*.h` + `firmware/v1.0.4-operator/tests/` |
| Commander / LabPanel | `firmware/v1.0.4-operator/LabPanel/` |
| Immutable publication-safe snapshot | `artifacts/v1.0.4/public-source-b64/` |
| Release identities | `artifacts/v1.0.4/RELEASE_MANIFEST_v1.0.4.sha256` |

The v1.0.4 firmware tree was first materialized by CI in commit
`eb5a863953f4f9b87d776e0011695da81dce0225`. Its Git tree identity is
`ced8e6fde50a59d514cba5d3dac0649124ea651f`.

## Release artifact identities

- original firmware release ZIP: `SecureMesh_v1_0_4_OPERATOR_FINAL.zip` — SHA-256 `9bd38544218f19077cef8d50d6bfe6e3baeb52781535e1f0e2317c718d2030a4`;
- publication-safe source ZIP identity: `SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip` — SHA-256 `baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff`;
- Android debug APK identity: `SecureMesh_v1_0_4_OPERATOR_ANDROID_FINAL.apk` — SHA-256 `83e2339bae482b90f1c393d7044b9f63b96ad2513e23783fed1e95463a255862`.

The original firmware ZIP is not published because it contains a LAB development
group key. The publication-safe snapshot removes the tracked key and expects a
local ignored `SecureMeshSecrets.h` created from `SecureMeshSecrets.example.h`.

The publication-safe ZIP is stored losslessly as 43 ordered Base64 text parts
instead of a binary Git blob. `tools/materialize_firmware_v1_0_4.py` concatenates
and decodes the parts, requires exactly 188879 decoded bytes, verifies SHA-256
`baff43e5...032ff`, rejects unsafe ZIP paths/secrets, and materializes the
browsable firmware tree. This avoids dependence on chat/file-transfer behavior.

The exact historical Android APK binary is not kept in Git history. Android
source is versioned and CI builds a fresh debug APK; the historical APK identity
is retained by SHA-256 and Notion evidence records.

## Recovery without chat history

1. Read Notion `00 — ЦЕНТР УПРАВЛЕНИЯ` and `98 — КОНТЕКСТ ДЛЯ ИИ`.
2. Read this file and `artifacts/v1.0.4/RELEASE_MANIFEST_v1.0.4.sha256`.
3. Use `firmware/v1.0.4-operator/` as the v1.0.4 working firmware source.
4. To independently reproduce that tree, run `python3 tools/materialize_firmware_v1_0_4.py`; the tool fails closed on size/SHA/path/secret mismatch.
5. Treat all real keys, credentials and provisioning values as local secrets; never reconstruct them from public GitHub or Notion.

## Evidence boundary

Native/software PASS does not imply hardware qualification. Current P0 remains
physical ESP32-S3 compile/flash, three-node LoRa/BLE/OLED/GPS validation,
Android↔ESP32 BLE v2 E2E, multi-hop, Primary/G2, failover/recovery and GPS/SOS
evidence.
