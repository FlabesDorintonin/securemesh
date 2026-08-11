#!/usr/bin/env python3
"""Compatibility entrypoint. The active gate for this revision is domain_alignment_gate.py."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).with_name("domain_alignment_gate.py")), run_name="__main__")
