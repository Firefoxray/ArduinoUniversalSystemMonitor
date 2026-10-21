"""Read-only monitoring and safe local configuration for the Qt frontend.

No GUI dependency and no imports from the long-running sender: this module may
also be used by future Windows collectors without starting a second sender.
"""
from __future__ import annotations

import json
import os
import platform
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psutil


ROOT = Path(__file__).resolve().parent.parent
SERVICE_NAME = "arduino-monitor.service"
EDITABLE_CONFIG = frozenset({
    "wifi_enabled", "wifi_host", "wifi_port", "wifi_auto_discovery",
    "wifi_discovery_port", "prefer_usb", "debug_enabled",
    "qbittorrent_enabled",
})


def version(root: Path = ROOT) -> str:
    try:
        return (root / "VERSION").read_text(encoding="utf-8").strip() or "unknown"
    except OSError:
        return "unknown"


class ConfigError(ValueError):
    """Invalid configuration; existing files are not changed."""


class ConfigStore:
    """Merge default < shared < local, like UniversalArduinoMonitor.load_config.

    Save only to the ignored machine-local override. Never overwrite a user's
    shared config, password fields, profiles, or firmware headers.
    """

    def __init__(self, root: Path = ROOT):
        self.root = Path(root)
        self.directory = self.root / "config"
        self.default = self.directory / "monitor_config.default.json"
        self.shared = self.directory / "monitor_config.json"
        self.local = self.directory / "monitor_config.local.json"

    @staticmethod
    def _read(path: Path) -> dict[str, Any]:
        if not path.exists():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise ConfigError(f"Cannot read {path}: {exc}") from exc
        if not isinstance(data, dict):
            raise ConfigError(f"Expected a JSON object in {path}")
        return data

    def load(self) -> dict[str, Any]:
        merged: dict[str, Any] = {}
        for path in (self.default, self.shared, self.local):
            merged.update(self._read(path))
        return merged

    @staticmethod
    def _validate(values: dict[str, Any]) -> None:
        unknown = set(values) - EDITABLE_CONFIG
        if unknown:
            raise ConfigError(f"Unsupported settings: {', '.join(sorted(unknown))}")
        for key in ("wifi_port", "wifi_discovery_port"):
            if key in values:
                value = values[key]
                if type(value) is not int or not 1 <= value <= 65535:
                    raise ConfigError(f"{key} must be a port between 1 and 65535")
        if "wifi_host" in values:
            host = values["wifi_host"]
            if not isinstance(host, str) or len(host) > 253 or "\n" in host:
                raise ConfigError("Wi-Fi host must be a valid-sized hostname or IP")
        for key in EDITABLE_CONFIG - {"wifi_host", "wifi_port", "wifi_discovery_port"}:
            if key in values and type(values[key]) is not bool:
                raise ConfigError(f"{key} must be true or false")

    def save_local(self, changes: dict[str, Any]) -> None:
        self._validate(changes)
        current = self._read(self.local)
        current.update(changes)
        self.directory.mkdir(parents=True, exist_ok=True)
        temporary: str | None = None
        try:
            with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", dir=self.directory, prefix=".uasm-qt-",
                delete=False
            ) as stream:
                temporary = stream.name
                os.chmod(temporary, 0o600)
                json.dump(current, stream, indent=2, ensure_ascii=False)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.local)
            temporary = None
        finally:
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)


@dataclass(frozen=True)
class Device:
    port: str
    description: str
    hardware_id: str


def serial_devices() -> list[Device]:
    try:
        from serial.tools import list_ports
    except ImportError:
        return []
    devices: list[Device] = []
    for port in list_ports.comports():
        path = str(port.device)
        if port.vid is None and not (
            path.startswith("/dev/ttyACM") or path.startswith("/dev/ttyUSB")
        ):
            continue  # Avoid 32+ irrelevant /dev/ttyS ports.
        devices.append(Device(path, str(port.description or "Serial device"), str(port.hwid or "")))
    return sorted(devices, key=lambda device: device.port)


def _systemctl(command: str) -> str:
    if sys.platform != "linux":
        return "unsupported"
    try:
        result = subprocess.run(
            ["systemctl", command, SERVICE_NAME], capture_output=True, text=True,
            timeout=2, check=False,
        )
        return result.stdout.strip() or result.stderr.strip() or "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"


def service_state() -> tuple[str, str]:
    return _systemctl("is-active"), _systemctl("is-enabled")


def run_doctor(root: Path = ROOT) -> str:
    """Read-only CLI diagnostics; never invokes install, flash, or update."""
    try:
        result = subprocess.run(
            [sys.executable, str(root / "UniversalArduinoMonitor.py"), "doctor"],
            capture_output=True, text=True, timeout=12, check=False,
            cwd=root,
        )
        output = (result.stdout + result.stderr).strip()
        return output or f"Doctor exited with status {result.returncode}"
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"Doctor unavailable: {exc}"


def _temperature() -> float | None:
    try:
        for sensors in psutil.sensors_temperatures().values():
            for sensor in sensors:
                if sensor.current is not None and 0 <= sensor.current <= 150:
                    return float(sensor.current)
    except (AttributeError, OSError):
        pass
    return None


@dataclass(frozen=True)
class Snapshot:
    cpu: float
    ram: float
    used_gb: float
    total_gb: float
    temperature: float | None
    down_bytes_per_sec: float
    up_bytes_per_sec: float
    per_cpu: tuple[float, ...]
    hostname: str
    os_name: str


class TelemetrySampler:
    """Lightweight local stats. One sampler is shared across Qt windows."""

    def __init__(self) -> None:
        self._last_time: float | None = None
        self._last_net: tuple[int, int] | None = None
        psutil.cpu_percent(interval=None)
        psutil.cpu_percent(interval=None, percpu=True)

    def sample(self) -> Snapshot:
        current_time = time.monotonic()
        net = psutil.net_io_counters()
        down = up = 0.0
        if self._last_time is not None and self._last_net is not None:
            seconds = max(0.001, current_time - self._last_time)
            down = max(0, net.bytes_recv - self._last_net[0]) / seconds
            up = max(0, net.bytes_sent - self._last_net[1]) / seconds
        self._last_net = (net.bytes_recv, net.bytes_sent)
        self._last_time = current_time
        memory = psutil.virtual_memory()
        return Snapshot(
            cpu=psutil.cpu_percent(interval=None),
            ram=float(memory.percent),
            used_gb=(memory.total - memory.available) / 1024**3,
            total_gb=memory.total / 1024**3,
            temperature=_temperature(),
            down_bytes_per_sec=down,
            up_bytes_per_sec=up,
            per_cpu=tuple(psutil.cpu_percent(interval=None, percpu=True)),
            hostname=platform.node(),
            os_name=platform.platform(),
        )


def drives() -> list[tuple[str, str, int, int, float]]:
    result: list[tuple[str, str, int, int, float]] = []
    seen: set[str] = set()
    for partition in psutil.disk_partitions(all=False):
        if partition.mountpoint in seen:
            continue
        seen.add(partition.mountpoint)
        try:
            usage = psutil.disk_usage(partition.mountpoint)
        except (PermissionError, OSError):
            continue
        result.append((partition.mountpoint, partition.device, usage.used, usage.total, usage.percent))
    return sorted(result, key=lambda row: row[0])


def readable_rate(value: float) -> str:
    if value >= 1024**3:
        return f"{value / 1024**3:.1f} GiB/s"
    if value >= 1024**2:
        return f"{value / 1024**2:.1f} MiB/s"
    if value >= 1024:
        return f"{value / 1024:.1f} KiB/s"
    return f"{value:.0f} B/s"
