#!/usr/bin/env python3
"""Compatibility launcher.

Debug mirror mode now lives in UniversalArduinoMonitor.py and is controlled by
monitor_config.json (debug_enabled + debug_port).
"""

from UniversalArduinoMonitor import main


if __name__ == "__main__":
    main()
