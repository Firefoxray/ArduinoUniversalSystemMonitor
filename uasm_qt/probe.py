"""Read-only v13 hardware probe; does not require Qt or open any serial port."""
from __future__ import annotations

import argparse
import json
import time
from dataclasses import asdict

from .backend import TelemetrySampler, serial_devices, service_state, version
from .wifi import discover


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wifi", action="store_true", help="Send read-only R4 UDP discovery broadcast")
    parser.add_argument("--discovery-port", type=int, default=5001)
    args = parser.parse_args(argv)
    sampler = TelemetrySampler()
    sampler.sample()
    time.sleep(0.5)
    snapshot = sampler.sample()
    active, enabled = service_state()
    result = {
        "runtime_version": version(),
        "service": {"active": active, "enabled": enabled},
        "usb_candidates": [asdict(device) for device in serial_devices()],
        "telemetry": asdict(snapshot),
        "wifi_discovery": None,
        "note": "Detection only; no serial ports opened or telemetry sent to boards.",
    }
    if args.wifi:
        try:
            result["wifi_discovery"] = [asdict(board) for board in discover(port=args.discovery_port)]
        except (OSError, ValueError) as exc:
            result["wifi_discovery"] = {"error": str(exc)}
    print(json.dumps(result, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
