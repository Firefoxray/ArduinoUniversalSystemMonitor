#!/usr/bin/env python3
"""Compatibility launcher for the main monitor runtime.

Keeps a single Python runtime implementation in the repository root so CLI,
service, and one-shot flows always share the same backend.
"""

from pathlib import Path
import runpy

SCRIPT = Path(__file__).resolve().parent.parent / "UniversalArduinoMonitor.py"
runpy.run_path(str(SCRIPT), run_name="__main__")
