#!/usr/bin/env python3
"""Compatibility launcher for moved sync_version script."""
from pathlib import Path
import runpy

SCRIPT = Path(__file__).resolve().parent / "arduino" / "sync_version.py"
runpy.run_path(str(SCRIPT), run_name="__main__")
