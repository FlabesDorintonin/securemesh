#!/usr/bin/env python3
"""Materialize SecureMesh v1.0.4 publication-safe firmware source from GitHub archive.

This script deliberately does not reconstruct or provision SecureMeshSecrets.h.
Secrets remain local provisioning data and must never be committed.
"""
from __future__ import annotations

import hashlib
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "artifacts/v1.0.4/SecureMesh_v1_0_4_OPERATOR_PUBLIC_SOURCE.zip"
DEST = ROOT / "firmware/v1.0.4-operator"
EXPECTED_SHA256 = "baff43e5eaac9d214cd4a22ec8f62d1845080d2f106345d26f06353848b032ff"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


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
    if not ARCHIVE.is_file():
        print(f"archive missing: {ARCHIVE}", file=sys.stderr)
        return 2

    actual = sha256(ARCHIVE)
    if actual != EXPECTED_SHA256:
        print(f"SHA-256 mismatch: expected {EXPECTED_SHA256}, got {actual}", file=sys.stderr)
        return 3

    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(ARCHIVE) as zf:
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
        return 4

    secret = DEST / "SecureMeshSecrets.h"
    if secret.exists():
        print("refusing materialized tree containing SecureMeshSecrets.h", file=sys.stderr)
        return 5

    print(f"verified archive SHA-256: {actual}")
    print(f"materialized: {DEST.relative_to(ROOT)}")
    print("create SecureMeshSecrets.h locally from the example; never commit it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
