#!/usr/bin/env python3
"""Reconstruct and materialize SecureMesh v1.0.4 publication-safe firmware source.

The canonical archive bytes are stored in GitHub as ordered base64 text parts so
binary transport cannot silently corrupt the release snapshot. This script:
1. concatenates parts in lexical order;
2. base64-decodes them in memory;
3. verifies the exact release SHA-256;
4. rejects unsafe ZIP paths and committed secrets;
5. materializes firmware/v1.0.4-operator.
"""
from __future__ import annotations

import base64
import hashlib
import io
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARTS = ROOT / "artifacts/v1.0.4/public-source-b64"
DEST = ROOT / "firmware/v1.0.4-operator"
EXPECTED_SHA256 = "baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff"
EXPECTED_SIZE = 188879


def archive_bytes() -> bytes:
    files = sorted(PARTS.glob("part-*.txt"))
    if len(files) != 43:
        raise RuntimeError(f"expected 43 archive parts, found {len(files)}")
    encoded = "".join(p.read_text(encoding="ascii") for p in files)
    try:
        data = base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise RuntimeError("invalid base64 source snapshot") from exc
    if len(data) != EXPECTED_SIZE:
        raise RuntimeError(f"archive size mismatch: expected {EXPECTED_SIZE}, got {len(data)}")
    actual = hashlib.sha256(data).hexdigest()
    if actual != EXPECTED_SHA256:
        raise RuntimeError(f"SHA-256 mismatch: expected {EXPECTED_SHA256}, got {actual}")
    return data


def safe_members(zf: zipfile.ZipFile):
    root = DEST.resolve()
    for info in zf.infolist():
        candidate = (DEST / info.filename).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise RuntimeError(f"unsafe archive path: {info.filename!r}") from exc
        yield info


def main() -> int:
    try:
        data = archive_bytes()
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        members = list(safe_members(zf))
        zf.extractall(DEST, members=members)

    required = [
        DEST / "SecureMesh_v1_0_4_OPERATOR.ino",
        DEST / "VanguardCore.h",
        DEST / "VanguardRuntime.h",
        DEST / "tests/vanguard_core_invariant_fuzz_test.cpp",
        DEST / "tests/vanguard_three_radio_lab_test.cpp",
        DEST / "LabPanel/app.js",
        DEST / "SecureMeshSecrets.example.h",
    ]
    missing = [str(p.relative_to(ROOT)) for p in required if not p.is_file()]
    if missing:
        print("materialization incomplete; missing: " + ", ".join(missing), file=sys.stderr)
        return 3

    if (DEST / "SecureMeshSecrets.h").exists():
        print("refusing materialized tree containing SecureMeshSecrets.h", file=sys.stderr)
        return 4

    print(f"verified publication-safe archive SHA-256: {EXPECTED_SHA256}")
    print(f"materialized: {DEST.relative_to(ROOT)}")
    print("create SecureMeshSecrets.h locally from the example; never commit it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
