#!/usr/bin/env python3
from pathlib import Path
import os
import sys

ROOT_MONITOR = Path(__file__).resolve().parent.parent / "UniversalArduinoMonitor.py"
os.execv(sys.executable, [sys.executable, str(ROOT_MONITOR), *sys.argv[1:]])
