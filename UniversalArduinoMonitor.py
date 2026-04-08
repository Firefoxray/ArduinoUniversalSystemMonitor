#!/usr/bin/env python3
"""Universal Linux Arduino system monitor sender.

Originally built on Fedora for an Arduino desktop monitor, but intended to run
across Linux desktops in general.

Highlights:
- Linux-focused (no Windows codepaths)
- Best-effort GPU support for NVIDIA, AMD, and Intel
- Auto-detects useful storage targets while still allowing overrides
- Handles Arduino disconnects/reconnects
- Preserves the positional payload shape expected by the matching Arduino sketch
- Optional debug mirror output for the Java fake display (KEY:VALUE stream)
- Configurable via config/monitor_config.json with environment variable overrides
"""

import json
import argparse
import os
import re
import shutil
import socket
import subprocess
import time
import fcntl
import ipaddress
import urllib.parse
import urllib.request
import http.cookiejar
from pathlib import Path
from typing import Any, Dict, List, NamedTuple, Optional, Set, Tuple
from collections import deque

import psutil
import serial
from serial import SerialException
from serial.tools import list_ports

REPO_ROOT = Path(__file__).resolve().parent
CONFIG_DIR = REPO_ROOT / "config"
VERSION_FILE = REPO_ROOT / "VERSION"


def load_app_version() -> str:
    try:
        value = VERSION_FILE.read_text(encoding="utf-8").strip()
        if value:
            return value
    except Exception:
        pass
    return "unknown version"


APP_VERSION = load_app_version()


def config_path(name: str) -> Path:
    return CONFIG_DIR / name


DEFAULT_CONFIG_PATH = config_path("monitor_config.default.json")
CONFIG_PATH = config_path("monitor_config.json")
LOCAL_CONFIG_PATH = config_path("monitor_config.local.json")
WIFI_LOCAL_CONFIG_PATH = REPO_ROOT / "R4_WIFI35" / "wifi_config.local.h"
WIFI_DEFAULT_CONFIG_PATH = REPO_ROOT / "R4_WIFI35" / "wifi_config.h"
LOCK_PATH = Path("/tmp/universal_arduino_monitor.lock")
_LOCK_HANDLE = None



def acquire_single_instance_lock() -> bool:
    global _LOCK_HANDLE
    try:
        _LOCK_HANDLE = open(LOCK_PATH, "w")
        fcntl.flock(_LOCK_HANDLE.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        _LOCK_HANDLE.write(str(os.getpid()))
        _LOCK_HANDLE.flush()
        return True
    except OSError:
        try:
            if _LOCK_HANDLE is not None:
                _LOCK_HANDLE.close()
        except Exception:
            pass
        _LOCK_HANDLE = None
        return False


def release_single_instance_lock() -> None:
    global _LOCK_HANDLE
    try:
        if _LOCK_HANDLE is not None:
            fcntl.flock(_LOCK_HANDLE.fileno(), fcntl.LOCK_UN)
            _LOCK_HANDLE.close()
    except Exception:
        pass
    _LOCK_HANDLE = None


def load_json_config(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return raw if isinstance(raw, dict) else {}


def load_config() -> Dict[str, object]:
    defaults: Dict[str, object] = {
        "arduino_port": "AUTO",
        "baud": 115200,
        "debug_enabled": False,
        "debug_port": "",
        "root_mount": "/",
        "secondary_mount": "/mnt/linux_storage",
        "storage_enabled_targets": [],
        "storage_disk0_target": "",
        "storage_disk1_target": "",
        "graph_net_down_enabled": False,
        "graph_net_up_enabled": False,
        "send_interval": 1.0,
        "wifi_enabled": True,
        "wifi_host": "192.168.1.50",
        "wifi_port": 5000,
        "prefer_usb": True,
        "wifi_retry_delay": 5,
        "wifi_auto_discovery": False,
        "wifi_discovery_port": 5001,
        "wifi_discovery_timeout": 1.2,
        "wifi_discovery_refresh": 30,
        "wifi_discovery_magic": "UAM_DISCOVER",
        "wifi_discovery_debug": False,
        "wifi_discovery_ignore_board_filter": False,
        "program_mode": "System Monitor",
        "macro_trigger_model": "Whole-screen tap cycles entries",
        "macro_entries": [],
        "qbittorrent_enabled": False,
        "qbittorrent_host": "127.0.0.1",
        "qbittorrent_port": 8080,
        "qbittorrent_url": "http://127.0.0.1:8080",
        "qbittorrent_username": "",
        "qbittorrent_password": "",
        "qbittorrent_timeout": 1.5,
        "qbittorrent_poll_interval": 2.0,
    }
    for path in (DEFAULT_CONFIG_PATH, CONFIG_PATH, LOCAL_CONFIG_PATH):
        try:
            defaults.update(load_json_config(path))
        except Exception as exc:
            print(f"Config load warning ({path}): {exc}")
    return defaults


def to_bool(value: object, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def to_int(value: object, default: int) -> int:
    try:
        return int(value)
    except Exception:
        return default


def to_float(value: object, default: float) -> float:
    try:
        return float(value)
    except Exception:
        return default


def read_wifi_header_define(define_name: str, default_value: str = "") -> str:
    marker = f"#define {define_name}"
    for path in (WIFI_LOCAL_CONFIG_PATH, WIFI_DEFAULT_CONFIG_PATH):
        try:
            if not path.exists():
                continue
            for raw_line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
                line = raw_line.strip()
                if not line.startswith(marker):
                    continue
                value = line[len(marker):].strip()
                if value.startswith('"') and value.endswith('"') and len(value) >= 2:
                    return value[1:-1]
                return value or default_value
        except Exception:
            continue
    return default_value


def resolve_wifi_port(config: Dict[str, object]) -> Tuple[int, str]:
    env_value = os.environ.get("ARDUINO_MONITOR_WIFI_PORT")
    if env_value is not None:
        return to_int(env_value, 5000), "environment override (ARDUINO_MONITOR_WIFI_PORT)"

    local_value = load_json_config(LOCAL_CONFIG_PATH).get("wifi_port")
    if local_value not in (None, ""):
        return to_int(local_value, 5000), "config/monitor_config.local.json"

    for path in (CONFIG_PATH, DEFAULT_CONFIG_PATH):
        shared_value = load_json_config(path).get("wifi_port")
        if shared_value not in (None, ""):
            return to_int(shared_value, 5000), f"shared JSON config ({path.name})"

    merged_value = config.get("wifi_port")
    return to_int(merged_value, 5000), "merged JSON config default"


def normalize_identity_value(text: object, max_len: int = 64) -> str:
    value = str(text or "").replace("|", "/").replace("\n", " ").replace("\r", " ").strip()
    return value[:max_len]


CONFIG = load_config()

BAUD = to_int(os.environ.get("ARDUINO_MONITOR_BAUD", CONFIG.get("baud")), 115200)
RETRY_DELAY = 10
SERIAL_SETTLE_DELAY = 2

CONFIG_ARDUINO_PORT = str(CONFIG.get("arduino_port", "AUTO")).strip()
PREFERRED_PORT = os.environ.get("ARDUINO_MONITOR_PORT")
if not PREFERRED_PORT and CONFIG_ARDUINO_PORT and CONFIG_ARDUINO_PORT.upper() != "AUTO":
    PREFERRED_PORT = CONFIG_ARDUINO_PORT


def parse_port_list(value: object) -> List[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        raw_ports = [str(item).strip() for item in value]
    else:
        raw_ports = [chunk.strip() for chunk in str(value).split(",")]
    ports: List[str] = []
    for port in raw_ports:
        if port and port.upper() != "AUTO" and port not in ports:
            ports.append(port)
    return ports


CONFIG_ARDUINO_PORTS = parse_port_list(CONFIG.get("arduino_ports"))
ENV_ARDUINO_PORTS = parse_port_list(os.environ.get("ARDUINO_MONITOR_PORTS"))
EXPLICIT_ARDUINO_PORTS = ENV_ARDUINO_PORTS or CONFIG_ARDUINO_PORTS
if PREFERRED_PORT and PREFERRED_PORT not in EXPLICIT_ARDUINO_PORTS:
    EXPLICIT_ARDUINO_PORTS = [PREFERRED_PORT, *EXPLICIT_ARDUINO_PORTS]

CPU_SAMPLE_INTERVAL = 0.20
SEND_INTERVAL = max(0.2, to_float(CONFIG.get("send_interval"), 1.0))
IDLE_LOOP_SLEEP = 0.03
GPU_CACHE_TTL = 0.20
GPU_SMOOTHING_WINDOW = 6
GPU_ZERO_FLOOR_RATIO = 0.35
PROC_CACHE_TTL = 1.0
STATIC_CACHE_TTL = 30.0
TEMP_CACHE_TTL = 1.0
STORAGE_CACHE_TTL = 10.0
BATTERY_CACHE_TTL = 10.0
QBT_CACHE_TTL = max(0.5, to_float(CONFIG.get("qbittorrent_poll_interval"), 2.0))

ROOT_MOUNT = str(CONFIG.get("root_mount") or os.environ.get("ARDUINO_MONITOR_ROOT") or "/")
CONFIG_SECONDARY_MOUNT = str(CONFIG.get("secondary_mount") or "").strip()
SECONDARY_MOUNT_CANDIDATES = [
    os.environ.get("ARDUINO_MONITOR_STORAGE"),
    CONFIG_SECONDARY_MOUNT,
    "/mnt/linux_storage",
    "/mnt/storage",
    "/mnt/data",
    "/home",
]

raw_storage_enabled_targets = CONFIG.get("storage_enabled_targets")
if isinstance(raw_storage_enabled_targets, list):
    STORAGE_ENABLED_TARGET_IDS = [str(item).strip() for item in raw_storage_enabled_targets if str(item).strip()]
else:
    STORAGE_ENABLED_TARGET_IDS = []
STORAGE_ENABLED_TARGET_SET = set(STORAGE_ENABLED_TARGET_IDS)
STORAGE_DISK0_TARGET = str(CONFIG.get("storage_disk0_target") or "").strip()
STORAGE_DISK1_TARGET = str(CONFIG.get("storage_disk1_target") or "").strip()
STORAGE_DEBUG = to_bool(os.environ.get("ARDUINO_MONITOR_STORAGE_DEBUG", CONFIG.get("storage_debug")), False)
STORAGE_LINES = 8
STORAGE_IO_SLOTS = 3
PROCESS_ROWS = 6
CPU_THREADS_TO_SEND = 16
ARDUINO_PAYLOAD_FIELD_COUNT = 73
ARDUINO_PAYLOAD_LAYOUT = (
    "0=cpu_total, 1-16=per_core, 17=ram_pct, 18=disk0_pct, 19=disk1_pct, "
    "20=cpu_temp, 21=os_name, 22=host_name, 23=ip, 24=uptime, 25=down_rate, "
    "26=up_rate, 27=down_total, 28=up_total, 29=cpu_freq, 30=ram_usage_text, "
    "31=gpu_util, 32=gpu_temp, 33=gpu_mem_used, 34=gpu_mem_total, "
    "35=gpu_mem_pct, 36=gpu_clock, 37=gpu_name, 38-43=proc_names, "
    "44-49=proc_cpu, 50-55=proc_ram, 56-63=storage_lines, 64=battery_pct, "
    "65=battery_state, 66=battery_mode, 67-69=extra_battery_label, "
    "70-72=extra_battery_state"
)
EXTRA_BATTERY_SLOTS = 3

DEBUG_MIRROR_PORT = str(os.environ.get("ARDUINO_MONITOR_DEBUG_PORT") or CONFIG.get("debug_port") or "").strip()
DEBUG_MIRROR_BAUD = to_int(os.environ.get("ARDUINO_MONITOR_DEBUG_BAUD"), BAUD)
DEBUG_MIRROR_ENABLED = to_bool(CONFIG.get("debug_enabled"), False) and bool(DEBUG_MIRROR_PORT)

WIFI_ENABLED = to_bool(os.environ.get("ARDUINO_MONITOR_WIFI_ENABLED", CONFIG.get("wifi_enabled")), True)
WIFI_HOST = str(os.environ.get("ARDUINO_MONITOR_WIFI_HOST") or CONFIG.get("wifi_host") or "").strip()
WIFI_PORT, WIFI_PORT_SOURCE = resolve_wifi_port(CONFIG)
PREFER_USB = to_bool(os.environ.get("ARDUINO_MONITOR_PREFER_USB", CONFIG.get("prefer_usb")), True)
WIFI_RETRY_DELAY = max(2, to_int(os.environ.get("ARDUINO_MONITOR_WIFI_RETRY_DELAY", CONFIG.get("wifi_retry_delay")), 5))
WIFI_QUICK_RETRY_DELAY = max(0.5, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_QUICK_RETRY_DELAY"), 1.0))
WIFI_QUICK_RETRY_WINDOW = max(5.0, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_QUICK_RETRY_WINDOW"), 20.0))
WIFI_AUTO_DISCOVERY = to_bool(os.environ.get("ARDUINO_MONITOR_WIFI_AUTO_DISCOVERY", CONFIG.get("wifi_auto_discovery")), True)
WIFI_DISCOVERY_PORT = max(1, to_int(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_PORT", CONFIG.get("wifi_discovery_port")), 5001))
WIFI_DISCOVERY_TIMEOUT = max(0.2, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_TIMEOUT", CONFIG.get("wifi_discovery_timeout")), 1.2))
WIFI_DISCOVERY_REFRESH = max(5.0, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_REFRESH", CONFIG.get("wifi_discovery_refresh")), 30.0))
WIFI_DISCOVERY_MAGIC = str(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_MAGIC") or CONFIG.get("wifi_discovery_magic") or "UAM_DISCOVER").strip()
WIFI_DISCOVERY_DEBUG = to_bool(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_DEBUG", CONFIG.get("wifi_discovery_debug")), False)
WIFI_DISCOVERY_IGNORE_BOARD_FILTER = to_bool(
    os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_IGNORE_BOARD_FILTER", CONFIG.get("wifi_discovery_ignore_board_filter")),
    False,
)
PROGRAM_MODE = str(os.environ.get("ARDUINO_MONITOR_PROGRAM_MODE") or CONFIG.get("program_mode") or "System Monitor").strip() or "System Monitor"
MACRO_TRIGGER_MODEL = str(CONFIG.get("macro_trigger_model") or "Whole-screen tap cycles entries").strip() or "Whole-screen tap cycles entries"
raw_macro_entries = CONFIG.get("macro_entries")
if isinstance(raw_macro_entries, list):
    MACRO_ENTRIES = [str(entry).strip() for entry in raw_macro_entries if str(entry).strip()][:8]
else:
    MACRO_ENTRIES = []
WIFI_DEVICE_NAME = normalize_identity_value(read_wifi_header_define("WIFI_DEVICE_NAME_VALUE", "R4_WIFI35"), 32) or "R4_WIFI35"
WIFI_TARGET_HOST = normalize_identity_value(read_wifi_header_define("WIFI_TARGET_HOST_VALUE", ""), 64)
WIFI_TARGET_HOSTNAME = normalize_identity_value(read_wifi_header_define("WIFI_TARGET_HOSTNAME_VALUE", ""), 64)
WIFI_PAIRING_MAGIC = "UAM_PAIR"
QBITTORRENT_ENABLED = to_bool(os.environ.get("ARDUINO_MONITOR_QBITTORRENT_ENABLED", CONFIG.get("qbittorrent_enabled")), False)
PAGE_PROCESSES_ENABLED = to_bool(CONFIG.get("page_processes_enabled"), True)
PAGE_GPU_ENABLED = to_bool(CONFIG.get("page_gpu_enabled"), True)
PAGE_STORAGE_ENABLED = to_bool(CONFIG.get("page_storage_enabled"), True)


def storage_debug_log(message: str) -> None:
    if STORAGE_DEBUG:
        print(f"[STORAGE] {message}")


storage_debug_log(
    "configured targets: enabled="
    + (",".join(STORAGE_ENABLED_TARGET_IDS) if STORAGE_ENABLED_TARGET_IDS else "<all>")
    + f", disk0={STORAGE_DISK0_TARGET or '<auto>'}, disk1={STORAGE_DISK1_TARGET or '<auto>'}"
)


def resolve_qbittorrent_url(config: Dict[str, object]) -> str:
    env_url = str(os.environ.get("ARDUINO_MONITOR_QBITTORRENT_URL") or "").strip()
    env_host = str(os.environ.get("ARDUINO_MONITOR_QBITTORRENT_HOST") or "").strip()
    env_port = os.environ.get("ARDUINO_MONITOR_QBITTORRENT_PORT")

    cfg_url = str(config.get("qbittorrent_url") or "").strip()
    cfg_host = str(config.get("qbittorrent_host") or "").strip()
    cfg_port = config.get("qbittorrent_port")

    source_url = env_url or cfg_url
    parsed = urllib.parse.urlparse(source_url) if source_url else None

    raw_host = env_host or cfg_host or (parsed.hostname if parsed else "") or "127.0.0.1"
    host = raw_host.strip()
    scheme = (parsed.scheme if parsed and parsed.scheme else "http").strip().lower() or "http"

    if env_port not in (None, ""):
        port = to_int(env_port, 8080)
    elif cfg_port not in (None, ""):
        port = to_int(cfg_port, 8080)
    elif parsed and parsed.port:
        port = int(parsed.port)
    else:
        port = 8080

    if host.startswith(("http://", "https://")):
        host_parsed = urllib.parse.urlparse(host)
        if host_parsed.hostname:
            host = host_parsed.hostname
        if host_parsed.scheme:
            scheme = host_parsed.scheme.lower()
        if env_port in (None, "") and cfg_port in (None, "") and host_parsed.port:
            port = int(host_parsed.port)

    port = max(1, min(65535, port))
    return f"{scheme}://{host}:{port}".rstrip("/")


QBITTORRENT_URL = resolve_qbittorrent_url(CONFIG)
QBITTORRENT_USERNAME = str(
    os.environ.get("ARDUINO_MONITOR_QBITTORRENT_USERNAME", CONFIG.get("qbittorrent_username", ""))
).strip()
QBITTORRENT_PASSWORD = str(
    os.environ.get("ARDUINO_MONITOR_QBITTORRENT_PASSWORD", CONFIG.get("qbittorrent_password", ""))
).strip()
QBITTORRENT_TIMEOUT = max(
    0.5,
    to_float(os.environ.get("ARDUINO_MONITOR_QBITTORRENT_TIMEOUT", CONFIG.get("qbittorrent_timeout")), 1.5),
)


_gpu_cache = {"ts": 0.0, "data": None}
_gpu_util_history = deque(maxlen=GPU_SMOOTHING_WINDOW)
_proc_cache = {"ts": 0.0, "data": None}
_static_cache = {"ts": 0.0, "data": None}
_temp_cache = {"ts": 0.0, "data": None}
_storage_cache = {"ts": 0.0, "data": None}
_storage_io_cache = {"ts": 0.0, "data": None}
_storage_io_prev_samples: Dict[str, Dict[str, float]] = {}
_battery_cache = {"ts": 0.0, "data": None}
_qbt_cache = {"ts": 0.0, "data": None}


def clean_field(text: object, max_len: int = 32) -> str:
    value = str(text).replace("|", "/").replace("\n", " ").replace("\r", " ").strip()
    if not value:
        return "--"
    return value[:max_len]


def extract_first_number(value: object) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    m = re.search(r"-?\d+(?:\.\d+)?", str(value))
    return float(m.group(0)) if m else None


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore").strip()
    except Exception:
        return ""


def run_cmd(cmd: List[str], timeout: float = 2.0) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
        return (result.stdout or "").strip()
    except Exception:
        return ""


def cmd_exists(name: str) -> bool:
    return shutil.which(name) is not None


def get_ip() -> str:
    preferred_ip = get_preferred_host_ipv4()
    if preferred_ip:
        return preferred_ip
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "No IP"


def get_hostname() -> str:
    try:
        return clean_field(socket.gethostname(), 28)
    except Exception:
        return "--"


class WifiDiscoveryRecord(NamedTuple):
    host: str
    port: int
    name: str
    target_host: str
    target_hostname: str


def get_os_name() -> str:
    def normalize_os_name(value: str) -> str:
        cleaned = re.sub(r"\s*\([^)]*\)", "", value).strip()
        if cleaned.lower().startswith("fedora linux"):
            match = re.search(r"fedora linux\s+(\d+)", cleaned, re.IGNORECASE)
            if match:
                return f"Fedora Linux {match.group(1)}"
            return "Fedora Linux"
        return cleaned or "Linux"

    try:
        os_release = Path("/etc/os-release")
        if os_release.exists():
            data: Dict[str, str] = {}
            for raw_line in os_release.read_text(encoding="utf-8", errors="ignore").splitlines():
                if "=" not in raw_line:
                    continue
                key, value = raw_line.split("=", 1)
                data[key.strip()] = value.strip().strip('"')
            pretty = data.get("PRETTY_NAME")
            if pretty:
                return clean_field(normalize_os_name(pretty), 28)
            name = data.get("NAME", "Linux")
            version = data.get("VERSION_ID", "")
            return clean_field(normalize_os_name(f"{name} {version}".strip()), 28)
    except Exception:
        pass
    return "Linux"


def get_uptime() -> str:
    seconds = int(time.time() - psutil.boot_time())
    days, seconds = divmod(seconds, 86400)
    hours, seconds = divmod(seconds, 3600)
    minutes = seconds // 60
    if days > 0:
        return f"{days}d {hours}h"
    return f"{hours}h {minutes}m"


def format_speed(bytes_per_sec: float) -> str:
    if bytes_per_sec >= 1024 * 1024:
        return f"{bytes_per_sec / (1024 * 1024):.1f} MB/s"
    if bytes_per_sec >= 1024:
        return f"{bytes_per_sec / 1024:.0f} KB/s"
    return f"{bytes_per_sec:.0f} B/s"


def format_total_bytes(num_bytes: float) -> str:
    if num_bytes >= 1024 ** 4:
        return f"{num_bytes / (1024 ** 4):.2f} TB"
    if num_bytes >= 1024 ** 3:
        return f"{num_bytes / (1024 ** 3):.1f} GB"
    if num_bytes >= 1024 ** 2:
        return f"{num_bytes / (1024 ** 2):.1f} MB"
    if num_bytes >= 1024:
        return f"{num_bytes / 1024:.0f} KB"
    return f"{num_bytes:.0f} B"


def format_eta(seconds: object) -> str:
    try:
        secs = int(float(seconds))
    except Exception:
        return "--"
    if secs < 0:
        return "∞"
    if secs < 60:
        return f"{secs}s"
    mins = secs // 60
    if mins < 60:
        return f"{mins}m"
    hours = mins // 60
    mins = mins % 60
    if hours < 24:
        return f"{hours}h {mins}m"
    days = hours // 24
    hours = hours % 24
    return f"{days}d {hours}h"


def format_ram_usage_gb() -> str:
    try:
        vm = psutil.virtual_memory()
        used_gb = vm.used / (1024 ** 3)
        total_gb = vm.total / (1024 ** 3)
        return f"{used_gb:.2f}GB/{total_gb:.1f}GB"
    except Exception:
        return "--"


def get_cpu_freq() -> str:
    try:
        freq = psutil.cpu_freq()
        if not freq or not freq.current:
            return "N/A"
        if freq.current >= 1000:
            return f"{freq.current / 1000:.2f} GHz"
        return f"{freq.current:.0f} MHz"
    except Exception:
        return "N/A"


def get_network_iface() -> str:
    stats = psutil.net_if_stats()
    addrs = psutil.net_if_addrs()
    preferred = None
    fallback = None
    for name, meta in stats.items():
        if not meta.isup or name == "lo":
            continue
        iface_addrs = addrs.get(name, [])
        if not any(a.family == socket.AF_INET for a in iface_addrs):
            continue
        if is_virtual_tunnel_iface(name):
            if fallback is None:
                fallback = name
            continue
        if name.startswith(("en", "eth", "wlan", "wl")):
            return name
        if preferred is None:
            preferred = name
    return preferred or fallback or "lo"


def is_virtual_tunnel_iface(name: str) -> bool:
    lowered = (name or "").strip().lower()
    return lowered.startswith(("wg", "tun", "tap", "ppp", "tailscale", "zt", "docker", "veth", "virbr", "br-"))


def get_preferred_host_ipv4() -> str:
    stats = psutil.net_if_stats()
    addrs = psutil.net_if_addrs()
    preferred_fallback = ""
    tunnel_fallback = ""
    for name, meta in stats.items():
        if not meta.isup or name == "lo":
            continue
        ipv4 = ""
        for addr in addrs.get(name, []):
            if addr.family != socket.AF_INET:
                continue
            candidate = (addr.address or "").strip()
            if not candidate or candidate.startswith("127."):
                continue
            ipv4 = candidate
            break
        if not ipv4:
            continue
        if is_virtual_tunnel_iface(name):
            if not tunnel_fallback:
                tunnel_fallback = ipv4
            continue
        if name.startswith(("en", "eth", "wlan", "wl")):
            return ipv4
        if not preferred_fallback:
            preferred_fallback = ipv4
    return preferred_fallback or tunnel_fallback


def get_temp() -> str:
    try:
        temps = psutil.sensors_temperatures(fahrenheit=False)
        preferred_keys = ("coretemp", "k10temp", "cpu_thermal", "zenpower", "acpitz")
        for key in preferred_keys:
            for entry in temps.get(key, []):
                label = (entry.label or "").lower()
                if any(tok in label for tok in ("package", "tctl", "tdie", "cpu")) or not label:
                    if entry.current is not None:
                        return f"{entry.current:.1f}C"
        for _, entries in temps.items():
            for entry in entries:
                label = (entry.label or "").lower()
                if any(tok in label for tok in ("package", "core", "tctl", "tdie", "cpu")):
                    if entry.current is not None:
                        return f"{entry.current:.1f}C"
    except Exception:
        pass

    sensors_out = run_cmd(["sensors"], timeout=2) if cmd_exists("sensors") else ""
    for line in sensors_out.splitlines():
        lower = line.lower()
        if any(tok in lower for tok in ("package id 0", "package", "tctl", "tdie", "cpu")):
            val = extract_first_number(line)
            if val is not None:
                return f"{val:.1f}C"
    return "N/A"


def list_candidate_ports() -> List[str]:
    ports = []

    for explicit_port in EXPLICIT_ARDUINO_PORTS:
        if explicit_port not in ports:
            ports.append(explicit_port)

    if ports:
        return ports

    for port in list_ports.comports():
        device = port.device
        if device not in ports and ("ttyACM" in device or "ttyUSB" in device):
            ports.append(device)
    return ports


def connect_arduino_port(port: str) -> Optional[serial.Serial]:
    try:
        print(f"Arduino found on {port}")
        ser = serial.Serial(port, BAUD, timeout=1, write_timeout=2)
        time.sleep(SERIAL_SETTLE_DELAY)
        try:
            ser.reset_input_buffer()
            ser.reset_output_buffer()
        except Exception:
            pass
        print(f"Connected to Arduino on {port}")
        return ser

    except (SerialException, OSError) as exc:
        err = str(exc)

        # Ignore common "normal" cases
        if any(x in err for x in [
            "Device or resource busy",
            "Permission denied",
            "FileNotFoundError"
        ]):
            # Silent skip (or you can uncomment debug line below)
            # print(f"[SKIP] {port} busy or unavailable")
            return None

        # Only print REAL unexpected errors
        print(f"Failed to open {port}: {exc}")
        return None


def connect_arduinos(existing: Optional[Dict[str, serial.Serial]] = None) -> Dict[str, serial.Serial]:
    connected: Dict[str, serial.Serial] = dict(existing or {})
    for port in list_candidate_ports():
        if port in connected and connected[port].is_open:
            continue
        ser = connect_arduino_port(port)
        if ser is not None:
            connected[port] = ser
    return connected


def connect_arduinos_blocking() -> Dict[str, serial.Serial]:
    connected: Dict[str, serial.Serial] = {}
    while not connected:
        connected = connect_arduinos(connected)
        if connected:
            break
        print("Waiting for Arduino to reconnect...")
        time.sleep(RETRY_DELAY)
    return connected


def connect_optional_serial(path: str, baud: int, label: str) -> Optional[serial.Serial]:
    if not path:
        return None
    try:
        ser = serial.Serial(path, baud, timeout=0.5, write_timeout=1)
        time.sleep(0.15)
        try:
            ser.reset_input_buffer()
            ser.reset_output_buffer()
        except Exception:
            pass
        print(f"{label} connected on {path}")
        return ser
    except (SerialException, OSError) as exc:
        print(f"{label} unavailable on {path}: {exc}")
        return None


def write_optional_serial(current: Optional[serial.Serial], payload: str, path: str, baud: int, label: str) -> Optional[serial.Serial]:
    if not path:
        return None
    ser = current
    try:
        if ser is None or not ser.is_open:
            ser = connect_optional_serial(path, baud, label)
        if ser is not None:
            ser.write(payload.encode("utf-8"))
        return ser
    except (SerialException, OSError) as exc:
        print(f"{label} write failed: {exc}")
        try:
            if ser is not None:
                ser.close()
        except Exception:
            pass
        return None



def connect_wifi_socket(host: str, port: int, quiet: bool = False, device_name: Optional[str] = None) -> Optional[socket.socket]:
    if not WIFI_ENABLED or not host:
        return None
    target_label = f"{host}:{port}"
    if device_name:
        target_label = f"{device_name} @ {target_label}"
    try:
        if not quiet:
            print(f"Trying Wi-Fi monitor at {target_label}...")
        sock = socket.create_connection((host, port), timeout=3.0)
        sock.settimeout(2.0)
        if not perform_wifi_pairing_handshake(sock):
            if not quiet:
                print(f"Wi-Fi pairing rejected by {target_label}")
            close_socket(sock)
            return None
        print(f"Connected to Arduino over Wi-Fi at {target_label}")
        return sock
    except OSError as exc:
        if not quiet:
            print(f"Wi-Fi unavailable at {target_label}: {exc}")
        return None


def close_socket(sock: Optional[socket.socket]) -> None:
    if sock is None:
        return
    try:
        sock.close()
    except Exception:
        pass


def perform_wifi_pairing_handshake(sock: socket.socket) -> bool:
    try:
        local_hostname = normalize_identity_value(socket.gethostname(), 64) or "--"
    except Exception:
        local_hostname = normalize_identity_value(get_hostname(), 64) or "--"
    local_host_ip = normalize_identity_value(get_ip(), 64) or "--"
    hello = f"{WIFI_PAIRING_MAGIC}|{local_hostname}|{local_host_ip}\n"
    try:
        sock.sendall(hello.encode("utf-8"))
        response = sock.recv(128).decode("utf-8", errors="ignore").strip()
    except OSError:
        return False
    return response == "PAIR_OK"



def identity_matches(expected: str, actual: str) -> bool:
    expected_value = str(expected or "").strip().lower()
    actual_value = str(actual or "").strip().lower()
    if not expected_value:
        return True
    if not actual_value:
        return False
    return expected_value == actual_value


def evaluate_discovery_record(record: WifiDiscoveryRecord) -> Tuple[bool, List[str]]:
    reasons: List[str] = []
    if (
        not WIFI_DISCOVERY_IGNORE_BOARD_FILTER
        and WIFI_DEVICE_NAME
        and WIFI_DEVICE_NAME != "R4_WIFI35"
        and not identity_matches(record.name, WIFI_DEVICE_NAME)
    ):
        reasons.append(f"board name mismatch (expected '{WIFI_DEVICE_NAME}', got '{record.name}')")
    if WIFI_TARGET_HOST and not identity_matches(record.target_host, WIFI_TARGET_HOST):
        reasons.append(f"pairing target_host mismatch (expected '{WIFI_TARGET_HOST}', got '{record.target_host or '--'}')")
    if WIFI_TARGET_HOSTNAME and not identity_matches(record.target_hostname, WIFI_TARGET_HOSTNAME):
        reasons.append(
            f"pairing target_hostname mismatch (expected '{WIFI_TARGET_HOSTNAME}', got '{record.target_hostname or '--'}')"
        )

    local_host_ip = get_ip()
    local_hostname = get_hostname()
    if record.target_host and not identity_matches(record.target_host, local_host_ip):
        reasons.append(f"pairing mismatch: reply targets host '{record.target_host}', local host is '{local_host_ip}'")
    if record.target_hostname and not identity_matches(record.target_hostname, local_hostname):
        reasons.append(
            f"pairing mismatch: reply targets hostname '{record.target_hostname}', local hostname is '{local_hostname}'"
        )
    if record.port <= 0 or record.port > 65535:
        reasons.append(f"port mismatch/invalid reply TCP port '{record.port}'")

    return len(reasons) == 0, reasons


def parse_discovery_response(message: str, addr: Tuple[str, int]) -> Tuple[Optional[WifiDiscoveryRecord], Optional[str]]:
    text = (message or "").strip()
    parts = text.split("|")
    if len(parts) < 3 or parts[0] != "UAM_HERE":
        return None, f"malformed reply (unexpected format): '{text}'"
    host = parts[1].strip() or addr[0]
    try:
        port = int(parts[2].strip())
    except Exception:
        return None, f"malformed reply (invalid TCP port): '{text}'"
    name = parts[3].strip() if len(parts) > 3 and parts[3].strip() else host
    target_host = parts[4].strip() if len(parts) > 4 else ""
    target_hostname = parts[5].strip() if len(parts) > 5 else ""
    return WifiDiscoveryRecord(host, port, name, target_host, target_hostname), None


def discovery_broadcast_targets() -> List[str]:
    targets: List[str] = []
    try:
        stats = psutil.net_if_stats()
        addrs = psutil.net_if_addrs()
        for iface, iface_stats in stats.items():
            if not iface_stats.isup or iface == "lo":
                continue
            if is_virtual_tunnel_iface(iface):
                continue
            for addr in addrs.get(iface, []):
                if addr.family != socket.AF_INET:
                    continue
                ip_value = (addr.address or "").strip()
                mask_value = (addr.netmask or "").strip()
                if not ip_value or not mask_value:
                    continue
                try:
                    interface = ipaddress.IPv4Interface(f"{ip_value}/{mask_value}")
                    broadcast = str(interface.network.broadcast_address)
                    if broadcast not in targets:
                        targets.append(broadcast)
                except Exception:
                    continue
    except Exception:
        pass
    if "255.255.255.255" not in targets:
        targets.append("255.255.255.255")
    return targets


def discover_wifi_monitor(timeout: Optional[float] = None) -> Optional[WifiDiscoveryRecord]:
    if not WIFI_ENABLED or not WIFI_AUTO_DISCOVERY:
        return None
    wait_time = WIFI_DISCOVERY_TIMEOUT if timeout is None else max(0.2, float(timeout))
    sock: Optional[socket.socket] = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(wait_time)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", 0))
        targets = discovery_broadcast_targets()
        payload = WIFI_DISCOVERY_MAGIC.encode("utf-8")
        sent_targets: List[str] = []
        for target in targets:
            try:
                sock.sendto(payload, (target, WIFI_DISCOVERY_PORT))
                sent_targets.append(target)
            except OSError as exc:
                if WIFI_DISCOVERY_DEBUG:
                    print(f"Wi-Fi discovery warning: failed to send UDP probe to {target}:{WIFI_DISCOVERY_PORT}: {exc}")
        target_summary = ", ".join(sent_targets) if sent_targets else "<none>"
        print(
            f"Wi-Fi discovery sent: UDP broadcast target(s) {target_summary}:{WIFI_DISCOVERY_PORT} "
            f"magic='{WIFI_DISCOVERY_MAGIC}' timeout={wait_time:.2f}s"
        )
        deadline = time.time() + wait_time
        all_records: List[WifiDiscoveryRecord] = []
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(512)
            except socket.timeout:
                break
            message = data.decode("utf-8", errors="ignore")
            parsed, parse_error = parse_discovery_response(message, addr)
            if parse_error:
                print(f"Wi-Fi discovery reply from {addr[0]}:{addr[1]} rejected: {parse_error}")
                continue
            if parsed is None:
                continue
            all_records.append(parsed)
            accepted, reasons = evaluate_discovery_record(parsed)
            reason_text = "; ".join(reasons) if reasons else "matched all active filters"
            print(
                "Wi-Fi discovery reply: "
                f"reply_ip={addr[0]} reply_port={addr[1]} tcp_target={parsed.host}:{parsed.port} "
                f"board='{parsed.name}' paired_host='{parsed.target_host or '--'}' "
                f"paired_hostname='{parsed.target_hostname or '--'}' "
                f"-> {'ACCEPTED' if accepted else 'REJECTED'} ({reason_text})"
            )
            if accepted:
                return parsed
        if WIFI_DISCOVERY_DEBUG:
            print(f"Wi-Fi discovery debug: discovered {len(all_records)} board reply/replies in this scan.")
            for idx, record in enumerate(all_records, start=1):
                accepted, reasons = evaluate_discovery_record(record)
                reason_text = "; ".join(reasons) if reasons else "matched all active filters"
                print(
                    f"  [{idx}] board='{record.name}' tcp={record.host}:{record.port} "
                    f"paired_host='{record.target_host or '--'}' paired_hostname='{record.target_hostname or '--'}' "
                    f"=> {'ACCEPTED' if accepted else 'REJECTED'} ({reason_text})"
                )
    except OSError:
        return None
    finally:
        try:
            if sock is not None:
                sock.close()
        except Exception:
            pass
    return None


def resolve_wifi_endpoint(cached_host: str, cached_port: int, last_discovery_ts: float) -> Tuple[str, int, float, Optional[WifiDiscoveryRecord]]:
    host = cached_host
    port = cached_port if cached_port > 0 else WIFI_PORT
    discovered: Optional[WifiDiscoveryRecord] = None
    should_refresh = WIFI_AUTO_DISCOVERY and (time.time() - last_discovery_ts) >= WIFI_DISCOVERY_REFRESH
    if should_refresh:
        found = discover_wifi_monitor()
        if found is not None:
            host, port = found.host, found.port
            discovered = found
            last_discovery_ts = time.time()
    return host, port, last_discovery_ts, discovered


def send_to_usb_devices(payload: str, arduino_serials: Dict[str, serial.Serial]) -> Dict[str, serial.Serial]:
    disconnected_ports: List[str] = []
    for port, ser in list(arduino_serials.items()):
        try:
            ser.write(payload.encode("utf-8"))
        except (SerialException, OSError) as exc:
            print(f"Arduino disconnected or serial write failed on {port}: {exc}")
            try:
                ser.close()
            except Exception:
                pass
            disconnected_ports.append(port)
    for port in disconnected_ports:
        arduino_serials.pop(port, None)
    return arduino_serials


def close_serials(arduino_serials: Dict[str, serial.Serial]) -> Dict[str, serial.Serial]:
    for ser in list(arduino_serials.values()):
        try:
            ser.close()
        except Exception:
            pass
    return {}


def send_to_wifi_device(payload: str, wifi_sock: Optional[socket.socket]) -> Optional[socket.socket]:
    if wifi_sock is None:
        return None
    try:
        wifi_sock.sendall(payload.encode("utf-8"))
        return wifi_sock
    except OSError as exc:
        print(f"Wi-Fi send failed: {exc}")
        close_socket(wifi_sock)
        return None


def describe_active_transports(arduino_serials: Dict[str, serial.Serial], wifi_sock: Optional[socket.socket]) -> str:
    parts: List[str] = []
    if arduino_serials:
        parts.append("USB")
    if wifi_sock is not None:
        parts.append("WIFI")
    return "+".join(parts) if parts else "NONE"


def current_wifi_retry_delay(start_ts: float, last_success_ts: float, now: float) -> float:
    if last_success_ts <= 0.0 and (now - start_ts) >= 3.0 and (now - start_ts) <= WIFI_QUICK_RETRY_WINDOW:
        return min(WIFI_RETRY_DELAY, WIFI_QUICK_RETRY_DELAY)
    if last_success_ts > 0.0 and (now - last_success_ts) <= WIFI_QUICK_RETRY_WINDOW:
        return min(WIFI_RETRY_DELAY, WIFI_QUICK_RETRY_DELAY)
    return WIFI_RETRY_DELAY


def should_prefer_usb_transport(arduino_serials: Dict[str, serial.Serial]) -> bool:
    return PREFER_USB and bool(arduino_serials)


def wifi_first_mode() -> bool:
    return WIFI_ENABLED and not PREFER_USB


def prime_process_cpu() -> None:
    for proc in psutil.process_iter(["name"]):
        try:
            proc.cpu_percent(None)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass


def get_top_processes(limit: int = PROCESS_ROWS) -> List[Tuple[str, str, str]]:
    rows = []
    seen = set()
    for proc in psutil.process_iter(["name", "memory_percent"]):
        try:
            cpu = proc.cpu_percent(None)
            ram = float(proc.info.get("memory_percent") or 0.0)
            name = clean_field(proc.info.get("name", "--"), 18)
            if not name or name == "--" or name in seen:
                continue
            seen.add(name)
            rows.append((cpu, ram, name))
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    rows.sort(key=lambda x: x[0], reverse=True)
    out = [(name, f"{cpu:.0f}%", f"{ram:.1f}%") for cpu, ram, name in rows[:limit]]
    while len(out) < limit:
        out.append(("--", "--", "--"))
    return out


def get_mount_percent(mountpoint: str) -> int:
    try:
        return int(psutil.disk_usage(mountpoint).percent)
    except Exception:
        return 0


def mount_exists(path: Optional[str]) -> bool:
    return bool(path and os.path.ismount(path))


def pick_secondary_mount() -> str:
    for candidate in SECONDARY_MOUNT_CANDIDATES:
        if mount_exists(candidate) and os.path.abspath(candidate) != os.path.abspath(ROOT_MOUNT):
            return candidate

    try:
        parts = [
            p for p in psutil.disk_partitions(all=False)
            if p.mountpoint not in ("/boot", "/boot/efi", ROOT_MOUNT)
            and not p.mountpoint.startswith(("/snap", "/run", "/var/lib/flatpak"))
        ]
        ranked = sorted(parts, key=lambda p: len(p.mountpoint))
        if ranked:
            return ranked[0].mountpoint
    except Exception:
        pass
    return ROOT_MOUNT


def load_lsblk() -> dict:
    try:
        out = run_cmd(["lsblk", "-J", "-o", "NAME,KNAME,TYPE,SIZE,FSTYPE,MOUNTPOINT,LABEL,MODEL,PATH"], timeout=3)
        return json.loads(out) if out else {}
    except Exception:
        return {}


def looks_generic_storage_name(value: str) -> bool:
    cleaned = re.sub(r"[^a-z0-9]", "", (value or "").strip().lower())
    if not cleaned:
        return True
    generic_patterns = (
        r"nvme\d+(n\d+)?(p\d+)?",
        r"sd[a-z]+\d*",
        r"hd[a-z]+\d*",
        r"vd[a-z]+\d*",
        r"xvd[a-z]+\d*",
        r"mmcblk\d+(p\d+)?",
        r"dm\d+",
        r"md\d+",
        r"loop\d+",
        r"disk\d*",
        r"part\d*",
    )
    return any(re.fullmatch(pattern, cleaned) for pattern in generic_patterns)


def storage_target_id(mountpoint: str, path_name: str, label: str) -> str:
    if mountpoint:
        return f"mnt:{mountpoint}"
    if path_name:
        return f"dev:{path_name}"
    return f"label:{label.strip().lower()}"


def collect_storage_targets() -> List[Dict[str, Any]]:
    data = load_lsblk()
    seen_keys: Set[str] = set()
    ignored_names = {"sda1", "sda2"}
    hidden_mounts = {"/boot", "/boot/efi", "/opt"}
    hidden_aliases = {"boot", "bootefi", "opt", "unmnt"}

    def mount_alias(mountpoint: str) -> str:
        mountpoint = (mountpoint or "").strip()
        if mountpoint == "/":
            return "root"
        if mountpoint == "/home":
            return "home"
        if mountpoint.startswith("/mnt/"):
            return mountpoint[5:] or "mnt"
        if mountpoint.startswith("/media/"):
            return mountpoint[7:] or "media"
        if mountpoint.startswith("/run/media/"):
            return mountpoint[11:] or "media"
        if mountpoint.startswith("/boot/efi"):
            return "bootefi"
        if mountpoint.startswith("/boot"):
            return "boot"
        tail = mountpoint.rstrip("/").split("/")[-1]
        return tail or mountpoint or "unmnt"

    def cleaned_name(node: dict) -> str:
        return str(node.get("name") or node.get("kname") or "").strip()

    def cleaned_path(node: dict) -> str:
        return str(node.get("path") or "").strip()

    def friendly_label(node: dict, parent_label: str = "disk") -> str:
        mountpoint = (node.get("mountpoint") or "").strip()
        raw_label = str(node.get("label") or "").strip()
        if raw_label and raw_label.lower() not in {"rootfs", "none"} and not looks_generic_storage_name(raw_label):
            return clean_field(raw_label, 14)

        alias = clean_field(mount_alias(mountpoint), 14)
        if alias != "--" and alias not in hidden_aliases:
            return alias

        model = clean_field(node.get("model") or "", 14)
        if model != "--" and not looks_generic_storage_name(model):
            return model

        path_name = cleaned_path(node).replace("/dev/", "").strip()
        if path_name and path_name.lower() not in ignored_names and not looks_generic_storage_name(path_name):
            return clean_field(path_name, 14)

        parent = clean_field(parent_label, 14)
        if parent != "--" and not looks_generic_storage_name(parent):
            return parent

        name = clean_field(cleaned_name(node) or "disk", 14)
        return name if name != "--" else "disk"

    secondary_mount = pick_secondary_mount()
    candidates: List[Tuple[int, Dict[str, Any]]] = []

    def visit(node: dict, parent_label: str = "") -> None:
        node_name = cleaned_name(node).lower()
        mountpoint = (node.get("mountpoint") or "").strip()
        fstype = (node.get("fstype") or "").strip().lower()
        node_type = (node.get("type") or "").strip()
        size = clean_field(node.get("size") or "", 8)
        raw_path = cleaned_path(node)

        if node_name in ignored_names or mountpoint in hidden_mounts:
            for child in node.get("children") or []:
                visit(child, parent_label)
            return

        if mountpoint == secondary_mount and mountpoint in hidden_mounts:
            return

        label = friendly_label(node, parent_label or "disk")
        alias = mount_alias(mountpoint) if mountpoint else ""

        score = 0
        if mountpoint:
            score += 200
            if mountpoint == ROOT_MOUNT:
                score += 120
            if mountpoint == secondary_mount:
                score += 60
            if mountpoint == "/home":
                score += 45
            if mountpoint.startswith(("/mnt/", "/media/", "/run/media/")):
                score += 35
        elif node_type == "disk":
            score += 25

        if str(node.get("label") or "").strip():
            score += 55
        if alias and alias not in hidden_aliases:
            score += 20
        if fstype in ("ext4", "btrfs", "xfs", "ntfs", "exfat", "vfat", "swap"):
            score += 25
        if node_type in ("part", "lvm", "crypt"):
            score += 15
        if node_type == "disk" and mountpoint:
            score += 10

        include_unmounted_disk = node_type == "disk" and not mountpoint and label.lower() not in ignored_names
        include_node = bool(mountpoint) or include_unmounted_disk or fstype == "swap"
        key = f"{label}|{mountpoint}|{size}|{fstype}"
        if include_node and key not in seen_keys:
            seen_keys.add(key)
            percent = get_mount_percent(mountpoint) if mountpoint else None
            target = {
                "id": storage_target_id(mountpoint, raw_path, label),
                "label": label,
                "mountpoint": mountpoint,
                "device_name": clean_field(cleaned_name(node), 24),
                "size": size,
                "percent": percent,
                "score": score,
                "line": clean_field(f"{label} {percent}% {mount_alias(mountpoint)}" if percent is not None else f"{label} {size}", 30),
            }
            candidates.append((score, target))

        for child in node.get("children") or []:
            visit(child, label)

    for disk in data.get("blockdevices", []):
        visit(disk)

    candidates.sort(key=lambda item: (-item[0], item[1]["label"], item[1]["mountpoint"], item[1]["size"]))
    return [item[1] for item in candidates]


def build_storage_lines(max_lines: int = STORAGE_LINES) -> List[str]:
    lines: List[str] = []
    targets = collect_storage_targets()
    if STORAGE_ENABLED_TARGET_SET:
        targets = [target for target in targets if target["id"] in STORAGE_ENABLED_TARGET_SET]
    for target in targets[:max_lines]:
        lines.append(clean_field(target.get("line") or target.get("label") or "disk", 30))

    while len(lines) < max_lines:
        lines.append("--")
    if STORAGE_DEBUG:
        summary = [
            f'{target.get("id")}=>{target.get("line")}'
            for target in targets
        ]
        storage_debug_log(
            "resolved targets: "
            + (", ".join(summary) if summary else "<none>")
            + f" | outgoing lines: {' || '.join(lines[:max_lines])}"
        )
    return lines[:max_lines]


def get_cached_storage_lines() -> List[str]:
    now = time.time()
    if _storage_cache["data"] is None or (now - _storage_cache["ts"]) >= STORAGE_CACHE_TTL:
        _storage_cache["data"] = build_storage_lines(STORAGE_LINES)
        _storage_cache["ts"] = now
    return _storage_cache["data"]


def collect_storage_io_entries(targets: List[Dict[str, Any]], now_time: float) -> List[Dict[str, object]]:
    entries: List[Dict[str, object]] = []
    io_by_disk = psutil.disk_io_counters(perdisk=True, nowrap=True) or {}
    seen_devices: Set[str] = set()

    for target in targets:
        mountpoint = str(target.get("mountpoint") or "").strip()
        if not mountpoint:
            continue
        device_name = str(target.get("device_name") or "").strip()
        if not device_name or device_name in seen_devices:
            continue
        counters = io_by_disk.get(device_name)
        if counters is None:
            continue

        prev = _storage_io_prev_samples.get(device_name)
        read_bps = 0.0
        write_bps = 0.0
        busy_pct: Optional[int] = None
        if prev:
            elapsed = max(now_time - float(prev.get("ts", now_time)), 0.001)
            read_delta = max(float(counters.read_bytes) - float(prev.get("read_bytes", counters.read_bytes)), 0.0)
            write_delta = max(float(counters.write_bytes) - float(prev.get("write_bytes", counters.write_bytes)), 0.0)
            read_bps = read_delta / elapsed
            write_bps = write_delta / elapsed
            if hasattr(counters, "busy_time") and prev.get("busy_time") is not None:
                busy_delta = max(float(counters.busy_time) - float(prev.get("busy_time", counters.busy_time)), 0.0)
                busy_pct = max(0, min(100, int(round((busy_delta / (elapsed * 1000.0)) * 100.0))))

        _storage_io_prev_samples[device_name] = {
            "ts": now_time,
            "read_bytes": float(counters.read_bytes),
            "write_bytes": float(counters.write_bytes),
            "busy_time": float(counters.busy_time) if hasattr(counters, "busy_time") else None,
        }

        seen_devices.add(device_name)
        entries.append({
            "label": clean_field(target.get("label") or mountpoint, 16),
            "mountpoint": mountpoint,
            "device_name": device_name,
            "read_bps": read_bps,
            "write_bps": write_bps,
            "total_bps": read_bps + write_bps,
            "util_pct": busy_pct,
        })
        if len(entries) >= STORAGE_IO_SLOTS:
            break
    return entries


def get_cached_storage_io_entries(targets: List[Dict[str, Any]], now_time: float) -> List[Dict[str, object]]:
    if _storage_io_cache["data"] is None:
        _storage_io_cache["data"] = collect_storage_io_entries(targets, now_time)
        _storage_io_cache["ts"] = now_time
    else:
        _storage_io_cache["data"] = collect_storage_io_entries(targets, now_time)
        _storage_io_cache["ts"] = now_time
    return _storage_io_cache["data"]


def get_battery_status() -> Tuple[str, str, str, List[Dict[str, str]]]:
    def normalize_status(status_text: str) -> str:
        lowered = status_text.strip().lower()
        if lowered in {"charging", "pending-charge"}:
            return "Charging"
        if lowered in {"discharging", "not charging", "full", "unknown", "pending-discharge", "fully-charged"}:
            return "Not Charging"
        return "Unknown"

    def is_system_battery_name(name: str) -> bool:
        upper_name = name.upper()
        return upper_name.startswith("BAT") or upper_name in {"CMB0", "MACSMC-BATTERY"}

    def looks_like_peripheral(label_text: str) -> bool:
        lowered = label_text.lower()
        return any(token in lowered for token in {
            "headset", "headphone", "earbud", "speaker", "controller", "gamepad",
            "mouse", "keyboard", "pen", "stylus", "joystick", "remote", "bluetooth",
            "dualsense", "dualshock", "playstation", "ps5", "ps4", "xbox"
        })

    def prettify_label(label_text: str, fallback_name: str) -> str:
        label = re.sub(r"[_-]+", " ", str(label_text or "")).strip() or fallback_name
        lowered = label.lower()
        if any(token in lowered for token in {"dualsense", "dualshock", "playstation", "ps5", "ps4"}):
            return "PlayStation Controller"
        if "xbox" in lowered:
            return "Xbox Controller"
        if "headset" in lowered or "headphone" in lowered or "earbud" in lowered:
            return "Bluetooth Audio"
        if "speaker" in lowered:
            return "Bluetooth Speaker"
        if "gamepad" in lowered or "controller" in lowered:
            return "Game Controller"
        return label

    def make_entry(name: str, label_text: str, percent_value: Optional[float], status_text: str) -> Optional[Dict[str, str]]:
        label = prettify_label(label_text, name)
        percent = "--" if percent_value is None else str(max(0, min(100, int(round(percent_value)))))
        if percent == "--" and normalize_status(status_text) == "Unknown":
            return None
        return {
            "name": clean_field(name, 32),
            "label": clean_field(label, 28),
            "percent": clean_field(percent, 6),
            "status": clean_field(normalize_status(status_text), 18),
        }

    def append_unique(target: List[Dict[str, str]], entry: Optional[Dict[str, str]]) -> None:
        if not entry:
            return
        key = (entry["name"].lower(), entry["label"].lower())
        for existing in target:
            if (existing["name"].lower(), existing["label"].lower()) == key:
                if existing.get("percent") in {"", "--", "N/A"} and entry.get("percent") not in {"", "--", "N/A"}:
                    existing["percent"] = entry["percent"]
                if existing.get("status") in {"", "--", "Unknown"} and entry.get("status") not in {"", "--", "Unknown"}:
                    existing["status"] = entry["status"]
                return
        target.append(entry)

    system_supplies: List[Dict[str, str]] = []
    extra_supplies: List[Dict[str, str]] = []
    for supply in sorted(Path("/sys/class/power_supply").glob("*")):
        try:
            supply_type = read_text(supply / "type").strip().lower()
            if supply_type not in {"battery", "ups"}:
                continue
            name = supply.name.strip() or "Battery"
            scope = read_text(supply / "scope").strip().lower()
            model_name = read_text(supply / "model_name").strip()
            manufacturer = read_text(supply / "manufacturer").strip()
            serial = read_text(supply / "serial_number").strip()
            label_base = model_name or manufacturer or serial or name
            cap = extract_first_number(read_text(supply / "capacity"))
            status_raw = read_text(supply / "status")
            entry = make_entry(name, label_base, cap, status_raw)
            if not entry:
                continue
            if scope == "system" or is_system_battery_name(name):
                append_unique(system_supplies, entry)
            elif scope == "device" or looks_like_peripheral(f"{name} {label_base} {manufacturer}"):
                append_unique(extra_supplies, entry)
            else:
                append_unique(extra_supplies, entry)
        except Exception:
            continue

    try:
        upower_paths = run_command(["upower", "-e"]).splitlines()
    except Exception:
        upower_paths = []

    for raw_path in upower_paths:
        device_path = raw_path.strip()
        if not device_path or "/battery_" not in device_path and not any(token in device_path.lower() for token in ("headset", "speaker", "mouse", "keyboard", "controller", "device")):
            continue
        try:
            details = run_command(["upower", "-i", device_path])
        except Exception:
            continue
        details_map: Dict[str, str] = {}
        for line in details.splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            details_map[key.strip().lower()] = value.strip()
        percent_value = extract_first_number(details_map.get("percentage", ""))
        state_value = details_map.get("state", "")
        model = details_map.get("model", "")
        vendor = details_map.get("vendor", "")
        native_path = details_map.get("native-path", "")
        name = native_path or Path(device_path).name
        label_text = " ".join(part for part in [vendor, model, name] if part).strip()
        category_hint = " ".join(part for part in [device_path, label_text] if part)
        entry = make_entry(name, label_text, percent_value, state_value)
        if not entry:
            continue
        if looks_like_peripheral(category_hint) or not is_system_battery_name(name):
            append_unique(extra_supplies, entry)
        else:
            append_unique(system_supplies, entry)

    try:
        batt = psutil.sensors_battery()
    except Exception:
        batt = None

    if system_supplies:
        primary = system_supplies[0]
        percent = primary["percent"] if primary["percent"] != "--" else "N/A"
        return percent, primary["status"], "LAPTOP", extra_supplies[:EXTRA_BATTERY_SLOTS]

    if batt is not None and batt.percent is not None and not extra_supplies:
        percent = max(0, min(100, int(round(batt.percent))))
        charging = "Charging" if batt.power_plugged else "Not Charging"
        return str(percent), charging, "LAPTOP", []

    return "N/A", "DESKTOP", "DESKTOP", extra_supplies[:EXTRA_BATTERY_SLOTS]


def get_cached_battery_status() -> Tuple[str, str, str, List[Dict[str, str]]]:
    now = time.time()
    if _battery_cache["data"] is None or (now - _battery_cache["ts"]) >= BATTERY_CACHE_TTL:
        _battery_cache["data"] = get_battery_status()
        _battery_cache["ts"] = now
    return _battery_cache["data"]


def get_qbittorrent_status() -> Dict[str, str]:
    fallback = {
        "status": "qBittorrent unavailable",
        "active_downloads": "--",
        "active_seeding": "--",
        "down_speed": "--",
        "up_speed": "--",
        "top_torrent": "--",
        "top_state": "--",
        "progress": "--",
        "eta": "--",
    }
    if not QBITTORRENT_ENABLED:
        fallback["status"] = "qBittorrent disabled"
        return fallback
    if not QBITTORRENT_URL:
        return fallback

    try:
        cookie_jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
        opener.addheaders = [("Referer", QBITTORRENT_URL), ("User-Agent", "UASM/1.0")]

        if QBITTORRENT_USERNAME:
            login_url = f"{QBITTORRENT_URL}/api/v2/auth/login"
            login_body = urllib.parse.urlencode({
                "username": QBITTORRENT_USERNAME,
                "password": QBITTORRENT_PASSWORD,
            }).encode("utf-8")
            with opener.open(login_url, data=login_body, timeout=QBITTORRENT_TIMEOUT) as resp:
                login_resp = resp.read().decode("utf-8", errors="ignore").strip()
            if "ok" not in login_resp.lower():
                return fallback

        data_url = f"{QBITTORRENT_URL}/api/v2/sync/maindata"
        with opener.open(data_url, timeout=QBITTORRENT_TIMEOUT) as resp:
            payload = json.loads(resp.read().decode("utf-8", errors="ignore"))

        server_state = payload.get("server_state", {}) if isinstance(payload, dict) else {}
        torrents_raw = payload.get("torrents", {}) if isinstance(payload, dict) else {}
        torrents = list(torrents_raw.values()) if isinstance(torrents_raw, dict) else []
        download_states = {"downloading", "stalleddl", "metadl", "queueddl", "forceddl", "checkingdl"}
        seeding_states = {"uploading", "stalledup", "queuedup", "forcedup"}
        active = []
        seeding = []
        for torrent in torrents:
            if not isinstance(torrent, dict):
                continue
            state = str(torrent.get("state", "")).lower()
            if state in download_states:
                active.append(torrent)
            if state in seeding_states:
                seeding.append(torrent)

        dl_speed = float(server_state.get("dl_info_speed", 0.0) or 0.0)
        up_speed = float(server_state.get("up_info_speed", 0.0) or 0.0)

        if active:
            top = max(active, key=lambda t: float(t.get("dlspeed", 0.0) or 0.0))
            progress_value = float(top.get("progress", 0.0) or 0.0) * 100.0
            eta_value = format_eta(top.get("eta", -1))
            return {
                "status": "Active downloads",
                "active_downloads": str(len(active)),
                "active_seeding": str(len(seeding)),
                "down_speed": format_speed(dl_speed),
                "up_speed": format_speed(up_speed),
                "top_torrent": clean_field(top.get("name", "--"), 60),
                "top_state": "Downloading",
                "progress": f"{progress_value:.1f}%",
                "eta": eta_value,
            }

        if seeding:
            top_seed = max(seeding, key=lambda t: float(t.get("upspeed", 0.0) or 0.0))
            return {
                "status": "Active seeding",
                "active_downloads": "0",
                "active_seeding": str(len(seeding)),
                "down_speed": format_speed(dl_speed),
                "up_speed": format_speed(up_speed),
                "top_torrent": clean_field(top_seed.get("name", "--"), 60),
                "top_state": "Seeding",
                "progress": "100.0%",
                "eta": "--",
            }

        return {
            "status": "No active torrents",
            "active_downloads": "0",
            "active_seeding": "0",
            "down_speed": format_speed(dl_speed),
            "up_speed": format_speed(up_speed),
            "top_torrent": "--",
            "top_state": "--",
            "progress": "--",
            "eta": "--",
        }
    except Exception:
        return fallback


def get_cached_qbittorrent_status() -> Dict[str, str]:
    now = time.time()
    if _qbt_cache["data"] is None or (now - _qbt_cache["ts"]) >= QBT_CACHE_TTL:
        _qbt_cache["data"] = get_qbittorrent_status()
        _qbt_cache["ts"] = now
    return _qbt_cache["data"]


def get_optical_status() -> str:
    dev = Path("/dev/sr0")
    if not dev.exists():
        return "No Drive"
    blkid = run_cmd(["blkid", "/dev/sr0"], timeout=2)
    if blkid:
        label_match = re.search(r'LABEL="([^"]+)"', blkid)
        if label_match:
            return clean_field(f"Media:{label_match.group(1)}", 18)
        return "Media"
    return "Empty"


def enumerate_drm_cards() -> List[Path]:
    return sorted([p for p in Path("/sys/class/drm").glob("card[0-9]*") if (p / "device").exists()])


def vendor_name_from_hex(vendor_hex: str) -> str:
    lookup = {"0x10de": "NVIDIA", "0x1002": "AMD", "0x8086": "Intel"}
    return lookup.get(vendor_hex.lower(), "GPU")


def card_vendor(card: Path) -> str:
    vendor = read_text(card / "device/vendor")
    return vendor_name_from_hex(vendor) if vendor else "GPU"


def pick_primary_gpu_card() -> Optional[Path]:
    cards = enumerate_drm_cards()
    if not cards:
        return None
    boot_cards = [c for c in cards if read_text(c / "device/boot_vga") == "1"]
    if boot_cards:
        return boot_cards[0]
    for preferred_vendor in ("NVIDIA", "AMD", "Intel"):
        for card in cards:
            if card_vendor(card) == preferred_vendor:
                return card
    return cards[0]


def read_hwmon_temp_c(card: Path) -> Optional[str]:
    hwmon_root = card / "device/hwmon"
    if not hwmon_root.exists():
        return None
    for hwmon in sorted(hwmon_root.glob("hwmon*")):
        for temp_file in sorted(hwmon.glob("temp*_input")):
            try:
                milli = int(read_text(temp_file) or "0")
                if milli > 0:
                    return f"{milli / 1000.0:.1f}C"
            except Exception:
                continue
    return None


def get_lspci_gpu_lines() -> List[str]:
    if not cmd_exists("lspci"):
        return []
    out = run_cmd(["lspci"], timeout=3)
    return [line.strip() for line in out.splitlines() if ("VGA compatible controller" in line or "Display controller" in line)]


def parse_pretty_gpu_name(vendor_hint: str) -> str:
    vendor_hint = vendor_hint.lower()
    for line in get_lspci_gpu_lines():
        lower = line.lower()
        if vendor_hint not in lower:
            continue
        try:
            name = line.split(":", 2)[-1].strip()
        except Exception:
            name = line

        bracket_parts = re.findall(r"\[([^\]]+)\]", name)
        useful_bracket = None
        for part in bracket_parts:
            part_lower = part.lower()
            if any(tok in part_lower for tok in ("radeon", "geforce", "rtx", "gtx", "arc", "iris", "uhd", "vega")):
                useful_bracket = part.strip()
                break

        name = re.sub(r"\(rev [^)]+\)", "", name)
        name = name.replace("Advanced Micro Devices, Inc. ", "")
        name = name.replace("AMD/ATI ", "")
        name = name.replace("NVIDIA Corporation ", "")
        name = name.replace("Intel Corporation ", "")
        name = name.replace("Corporation", "")
        name = name.replace("[AMD/ATI]", "")
        name = name.replace("[AMD]", "")
        name = re.sub(r"\s+", " ", name).strip(" -")

        if useful_bracket:
            if vendor_hint == "amd":
                return clean_field(f"AMD {useful_bracket}", 32)
            if vendor_hint == "nvidia":
                return clean_field(f"NVIDIA {useful_bracket}", 32)
            if vendor_hint == "intel":
                return clean_field(f"Intel {useful_bracket}", 32)
            return clean_field(useful_bracket, 32)

        name = re.sub(r"\[[^\]]+\]", "", name)
        name = re.sub(r"\s+", " ", name).strip(" -")
        if vendor_hint == "amd" and not name.startswith("AMD"):
            return clean_field(f"AMD {name}", 32)
        if vendor_hint == "nvidia" and not name.startswith("NVIDIA"):
            return clean_field(f"NVIDIA {name}", 32)
        if vendor_hint == "intel" and not name.startswith("Intel"):
            return clean_field(f"Intel {name}", 32)
        return clean_field(name, 32)
    return clean_field(f"{vendor_hint.title()} GPU", 32)


def get_nvidia_gpu_stats() -> Tuple[str, str, str, str, str, str, str]:
    if not cmd_exists("nvidia-smi"):
        return "0", "N/A", "0", "0", "0", "0", "No NVIDIA GPU"

    query = "utilization.gpu,temperature.gpu,memory.used,memory.total,clocks.current.graphics,name"
    out = run_cmd(["nvidia-smi", f"--query-gpu={query}", "--format=csv,noheader,nounits"], timeout=3)
    if not out:
        return "0", "N/A", "0", "0", "0", "0", "NVIDIA GPU"

    line = out.splitlines()[0]
    parts = [p.strip() for p in line.split(",", 5)]
    if len(parts) < 6:
        return "0", "N/A", "0", "0", "0", "0", "NVIDIA GPU"

    util, temp, mem_used, mem_total, clock, name = parts
    try:
        pct = int(round((float(mem_used) / float(mem_total)) * 100)) if float(mem_total) > 0 else 0
    except Exception:
        pct = 0
    temp_text = f"{extract_first_number(temp) or 0:.1f}C" if extract_first_number(temp) is not None else "N/A"
    return (
        str(int(extract_first_number(util) or 0)),
        temp_text,
        str(int(extract_first_number(mem_used) or 0)),
        str(int(extract_first_number(mem_total) or 0)),
        str(pct),
        str(int(extract_first_number(clock) or 0)),
        clean_field(name or parse_pretty_gpu_name("nvidia"), 32),
    )


def read_gpu_clock_mhz_amd(card: Path) -> str:
    pp = card / "device/pp_dpm_sclk"
    if pp.exists():
        try:
            for line in pp.read_text().splitlines():
                if "*" in line:
                    m = re.search(r"(\d+)Mhz", line, re.IGNORECASE)
                    if m:
                        return m.group(1)
        except Exception:
            pass
    return "0"


def get_amd_gpu_stats(card: Optional[Path] = None) -> Tuple[str, str, str, str, str, str, str]:
    card = card or next((c for c in enumerate_drm_cards() if card_vendor(c) == "AMD"), None)
    if not card:
        return "0", "N/A", "0", "0", "0", "0", "No AMD GPU"

    util = read_text(card / "device/gpu_busy_percent") or "0"
    temp = read_hwmon_temp_c(card) or "N/A"

    try:
        used = int(read_text(card / "device/mem_info_vram_used") or "0")
    except Exception:
        used = 0
    try:
        total = int(read_text(card / "device/mem_info_vram_total") or "0")
    except Exception:
        total = 0

    used_mb = max(0, int(round(used / (1024 * 1024))))
    total_mb = max(0, int(round(total / (1024 * 1024))))
    pct = int(round((used_mb / total_mb) * 100)) if total_mb > 0 else 0
    clock = read_gpu_clock_mhz_amd(card)
    name = parse_pretty_gpu_name("amd")
    return str(int(extract_first_number(util) or 0)), temp, str(used_mb), str(total_mb), str(pct), clock, name


def read_intel_clock_mhz(card: Path) -> str:
    for candidate in (card / "gt_cur_freq_mhz", card / "device/gt_cur_freq_mhz", card / "device/pp_cur_state"):
        val = read_text(candidate)
        num = extract_first_number(val)
        if num is not None:
            return str(int(num))
    return "0"


def get_intel_gpu_top_busy() -> Optional[int]:
    if not cmd_exists("intel_gpu_top"):
        return None
    for cmd in (["intel_gpu_top", "-J", "-s", "250", "-L"], ["intel_gpu_top", "-J", "-s", "250"]):
        out = run_cmd(cmd, timeout=2.5)
        if not out:
            continue
        nums = re.findall(r'"busy"\s*:\s*([0-9]+(?:\.[0-9]+)?)', out)
        if nums:
            try:
                vals = [float(x) for x in nums]
                return int(round(sum(vals) / max(len(vals), 1)))
            except Exception:
                pass
    return None


def get_intel_gpu_stats(card: Optional[Path] = None) -> Tuple[str, str, str, str, str, str, str]:
    card = card or next((c for c in enumerate_drm_cards() if card_vendor(c) == "Intel"), None)
    if not card:
        return "0", "N/A", "0", "0", "0", "0", "No Intel GPU"

    util = get_intel_gpu_top_busy()
    temp = read_hwmon_temp_c(card) or "N/A"
    used_mb = 0
    total_mb = 0
    pct = 0
    clock = read_intel_clock_mhz(card)
    name = parse_pretty_gpu_name("intel")
    return str(util or 0), temp, str(used_mb), str(total_mb), str(pct), clock, name


def get_generic_gpu_stats(card: Optional[Path]) -> Tuple[str, str, str, str, str, str, str]:
    vendor = card_vendor(card) if card else "GPU"
    temp = read_hwmon_temp_c(card) if card else None
    name = parse_pretty_gpu_name(vendor.lower()) if vendor != "GPU" else "Linux GPU"
    return "0", temp or "N/A", "0", "0", "0", "0", clean_field(name, 32)


def get_gpu_stats() -> Tuple[str, str, str, str, str, str, str]:
    if cmd_exists("nvidia-smi"):
        nvidia = get_nvidia_gpu_stats()
        if nvidia[-1] != "No NVIDIA GPU":
            return nvidia

    card = pick_primary_gpu_card()
    vendor = card_vendor(card) if card else "GPU"
    if vendor == "AMD":
        return get_amd_gpu_stats(card)
    if vendor == "Intel":
        return get_intel_gpu_stats(card)
    if vendor == "NVIDIA":
        nvidia = get_nvidia_gpu_stats()
        if nvidia[-1] != "No NVIDIA GPU":
            return nvidia
    return get_generic_gpu_stats(card)


def get_cached_temp() -> str:
    now = time.time()
    if _temp_cache["data"] is None or (now - _temp_cache["ts"]) >= TEMP_CACHE_TTL:
        _temp_cache["data"] = get_temp()
        _temp_cache["ts"] = now
    return _temp_cache["data"]


def smooth_gpu_utilization(raw_value: object) -> str:
    try:
        util = int(round(float(raw_value)))
    except Exception:
        util = 0
    util = max(0, min(100, util))

    if util == 0 and _gpu_util_history:
        recent_avg = sum(_gpu_util_history) / len(_gpu_util_history)
        if recent_avg >= 18:
            util = max(4, int(round(recent_avg * GPU_ZERO_FLOOR_RATIO)))

    _gpu_util_history.append(util)
    weighted_total = 0.0
    weight_sum = 0.0
    for idx, value in enumerate(_gpu_util_history, start=1):
        weighted_total += idx * value
        weight_sum += idx
    smoothed = int(round(weighted_total / weight_sum)) if weight_sum > 0 else util
    return str(max(0, min(100, smoothed)))


def get_cached_gpu_stats() -> Tuple[str, str, str, str, str, str, str]:
    now = time.time()
    if _gpu_cache["data"] is None or (now - _gpu_cache["ts"]) >= GPU_CACHE_TTL:
        stats = list(get_gpu_stats())
        if stats:
            stats[0] = smooth_gpu_utilization(stats[0])
        _gpu_cache["data"] = tuple(stats)
        _gpu_cache["ts"] = now
    return _gpu_cache["data"]


def get_cached_top_processes() -> List[Tuple[str, str, str]]:
    now = time.time()
    if _proc_cache["data"] is None or (now - _proc_cache["ts"]) >= PROC_CACHE_TTL:
        _proc_cache["data"] = get_top_processes(PROCESS_ROWS)
        _proc_cache["ts"] = now
    return _proc_cache["data"]


def get_cached_static() -> Dict[str, str]:
    now = time.time()
    if _static_cache["data"] is None or (now - _static_cache["ts"]) >= STATIC_CACHE_TTL:
        primary_gpu = pick_primary_gpu_card()
        secondary_mount = pick_secondary_mount()
        _static_cache["data"] = {
            "os_name": get_os_name(),
            "host_name": get_hostname(),
            "iface": get_network_iface(),
            "gpu_vendor": card_vendor(primary_gpu) if primary_gpu else "GPU",
            "secondary_mount": secondary_mount,
        }
        _static_cache["ts"] = now
    return _static_cache["data"]


def resolve_storage_percent(targets: List[Dict[str, Any]], configured_target_id: str, fallback_mount: str) -> int:
    if configured_target_id:
        for target in targets:
            if target.get("id") == configured_target_id:
                mountpoint = str(target.get("mountpoint") or "")
                if mountpoint:
                    return get_mount_percent(mountpoint)
                percent = target.get("percent")
                return int(percent) if isinstance(percent, int) else 0
    for target in targets:
        if str(target.get("mountpoint") or "") == fallback_mount:
            return get_mount_percent(fallback_mount)
    if fallback_mount:
        return get_mount_percent(fallback_mount)
    return 0


def collect_snapshot(last_net, last_time):
    cpu_total_val = psutil.cpu_percent(interval=CPU_SAMPLE_INTERVAL)
    per_core = psutil.cpu_percent(interval=None, percpu=True)
    while len(per_core) < CPU_THREADS_TO_SEND:
        per_core.append(0.0)
    per_core = per_core[:CPU_THREADS_TO_SEND]

    static = get_cached_static()
    storage_targets = collect_storage_targets()
    enabled_storage_targets = [target for target in storage_targets if not STORAGE_ENABLED_TARGET_SET or target["id"] in STORAGE_ENABLED_TARGET_SET]
    iface = static["iface"]
    now_time = time.time()
    elapsed = max(now_time - last_time, 0.001)

    net_stats = psutil.net_io_counters(pernic=True)
    now_net = net_stats.get(iface) or psutil.net_io_counters()
    down_bps = (now_net.bytes_recv - last_net.bytes_recv) / elapsed
    up_bps = (now_net.bytes_sent - last_net.bytes_sent) / elapsed

    if PAGE_GPU_ENABLED:
        gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = get_cached_gpu_stats()
    else:
        gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = ("0", "N/A", "0", "0", "0", "0", "GPU page disabled")

    if PAGE_PROCESSES_ENABLED:
        procs = get_cached_top_processes()
    else:
        procs = [("--", "--", "--") for _ in range(PROCESS_ROWS)]

    if PAGE_STORAGE_ENABLED:
        storage_lines = get_cached_storage_lines()
        storage_io = get_cached_storage_io_entries(enabled_storage_targets, now_time)
    else:
        storage_lines = ["Storage page disabled"]
        storage_io = []
    battery_pct, battery_state, battery_mode, extra_batteries = get_cached_battery_status()
    qbittorrent = get_cached_qbittorrent_status()

    snapshot = {
        "cpu_total": int(round(cpu_total_val)),
        "per_core": [int(round(x)) for x in per_core],
        "ram_pct": int(round(psutil.virtual_memory().percent)),
        "disk0_pct": resolve_storage_percent(enabled_storage_targets, STORAGE_DISK0_TARGET, ROOT_MOUNT),
        "disk1_pct": resolve_storage_percent(enabled_storage_targets, STORAGE_DISK1_TARGET, static["secondary_mount"]),
        "cpu_temp": clean_field(get_cached_temp(), 12),
        "os_name": clean_field(static["os_name"], 24),
        "host_name": clean_field(static["host_name"], 24),
        "ip": clean_field(get_ip(), 18),
        "uptime": clean_field(get_uptime(), 18),
        "down_rate": clean_field(format_speed(down_bps), 18),
        "up_rate": clean_field(format_speed(up_bps), 18),
        "down_total": clean_field(format_total_bytes(now_net.bytes_recv), 18),
        "up_total": clean_field(format_total_bytes(now_net.bytes_sent), 18),
        "cpu_freq": clean_field(get_cpu_freq(), 18),
        "ram_usage_text": clean_field(format_ram_usage_gb(), 18),
        "gpu_util": clean_field(gpu_util, 4),
        "gpu_temp": clean_field(gpu_temp, 12),
        "gpu_mem_used": clean_field(gpu_mem_used, 8),
        "gpu_mem_total": clean_field(gpu_mem_total, 8),
        "gpu_mem_pct": clean_field(gpu_mem_pct, 4),
        "gpu_clock": clean_field(gpu_clock, 8),
        "gpu_name": clean_field(gpu_name, 32),
        "procs": procs,
        "storage_lines": [clean_field(x, 30) for x in storage_lines],
        "storage_io": storage_io,
        "iface": clean_field(iface, 18),
        "secondary_mount": clean_field(static["secondary_mount"], 24),
        "battery_pct": clean_field(battery_pct, 6),
        "battery_state": clean_field(battery_state, 18),
        "battery_mode": clean_field(battery_mode, 10),
        "extra_batteries": extra_batteries,
        "qbittorrent": qbittorrent,
    }

    return snapshot, now_net, now_time


def build_snapshot(last_net, last_time):
    """Backward-compatible wrapper for older call sites."""
    return collect_snapshot(last_net, last_time)


def print_rayfetch(snapshot: Dict[str, object]) -> None:
    print("=== RayFetch :: Ray Co. Universal Arduino System Monitor ===")
    print(f"OS: {snapshot['os_name']}")
    print(f"Hostname: {snapshot['host_name']}")
    print(f"Uptime: {snapshot['uptime']}")
    print(f"Active Interface: {snapshot['iface']}")
    print(f"IP Address: {snapshot['ip']}")
    print(f"CPU Frequency: {snapshot['cpu_freq']}")
    print(f"CPU Usage: {snapshot['cpu_total']}%")
    print(f"CPU Temp: {snapshot['cpu_temp']}")
    print(f"RAM Usage: {snapshot['ram_pct']}% ({snapshot['ram_usage_text']})")
    print(f"GPU Name: {snapshot['gpu_name']}")
    print(f"GPU Usage: {snapshot['gpu_util']}%")
    print(f"GPU Temp: {snapshot['gpu_temp']}")
    print(f"GPU VRAM: {snapshot['gpu_mem_used']}/{snapshot['gpu_mem_total']} MB ({snapshot['gpu_mem_pct']}%)")
    print(f"Storage: Disk0 {snapshot['disk0_pct']}% | Disk1 {snapshot['disk1_pct']}%")
    if snapshot.get("storage_lines"):
        print("Storage Lines:")
        for line in snapshot["storage_lines"][:4]:
            print(f"  - {line}")
    if snapshot.get("procs"):
        print("Top Processes:")
        for name, cpu, ram in snapshot["procs"][:3]:
            print(f"  - {name} | CPU {cpu} | RAM {ram}")


def build_arduino_status_snapshot() -> Dict[str, object]:
    static = get_cached_static()
    candidate_ports = list_candidate_ports()
    listed_ports = []
    for port in list_ports.comports():
        listed_ports.append({
            "device": port.device,
            "description": port.description,
            "hwid": port.hwid,
        })
    return {
        "app_version": APP_VERSION,
        "os_name": static["os_name"],
        "host_name": static["host_name"],
        "active_interface": static["iface"],
        "ip": get_ip(),
        "preferred_port": PREFERRED_PORT or "AUTO",
        "explicit_ports": EXPLICIT_ARDUINO_PORTS,
        "candidate_ports": candidate_ports,
        "serial_ports_seen": listed_ports,
        "baud": BAUD,
        "wifi_enabled": WIFI_ENABLED,
        "wifi_host": WIFI_HOST,
        "wifi_port": WIFI_PORT,
        "wifi_auto_discovery": WIFI_AUTO_DISCOVERY,
        "program_mode": PROGRAM_MODE,
        "debug_mirror_enabled": DEBUG_MIRROR_ENABLED,
    }


def print_arduino_status() -> None:
    status = build_arduino_status_snapshot()
    print("=== Arduino Sender Status (No connection attempted) ===")
    print(f"Version: {status['app_version']}")
    print(f"OS: {status['os_name']} | Host: {status['host_name']}")
    print(f"Active Interface: {status['active_interface']} | IP: {status['ip']}")
    print(f"Preferred Port: {status['preferred_port']}")
    print(f"Explicit Ports: {status['explicit_ports']}")
    print(f"Candidate Ports: {status['candidate_ports']}")
    print(f"Baud: {status['baud']}")
    print(f"Wi-Fi: enabled={status['wifi_enabled']} host={status['wifi_host']} port={status['wifi_port']} auto_discovery={status['wifi_auto_discovery']}")
    print(f"Program Mode: {status['program_mode']}")
    print(f"Debug Mirror Enabled: {status['debug_mirror_enabled']}")
    print("Detected serial devices:")
    for entry in status["serial_ports_seen"]:
        print(f"  - {entry['device']} :: {entry['description']} :: {entry['hwid']}")
    if not status["serial_ports_seen"]:
        print("  - none")


def build_arduino_payload(snapshot) -> str:
    fields: List[str] = []
    fields.append(str(snapshot["cpu_total"]))
    fields.extend([str(x) for x in snapshot["per_core"]])
    fields.extend([
        str(snapshot["ram_pct"]),
        str(snapshot["disk0_pct"]),
        str(snapshot["disk1_pct"]),
        snapshot["cpu_temp"],
        snapshot["os_name"],
        snapshot["host_name"],
        snapshot["ip"],
        snapshot["uptime"],
        snapshot["down_rate"],
        snapshot["up_rate"],
        snapshot["down_total"],
        snapshot["up_total"],
        snapshot["cpu_freq"],
        snapshot["ram_usage_text"],
        snapshot["gpu_util"],
        snapshot["gpu_temp"],
        snapshot["gpu_mem_used"],
        snapshot["gpu_mem_total"],
        snapshot["gpu_mem_pct"],
        snapshot["gpu_clock"],
        snapshot["gpu_name"],
    ])
    for name, _, _ in snapshot["procs"]:
        fields.append(clean_field(name, 18))
    for _, cpu, _ in snapshot["procs"]:
        fields.append(clean_field(cpu, 6))
    for _, _, ramv in snapshot["procs"]:
        fields.append(clean_field(ramv, 8))
    fields.extend(snapshot["storage_lines"])
    fields.append(snapshot["battery_pct"])
    fields.append(snapshot["battery_state"])
    fields.append(snapshot["battery_mode"])
    for idx in range(EXTRA_BATTERY_SLOTS):
        if idx < len(snapshot["extra_batteries"]):
            entry = snapshot["extra_batteries"][idx]
            label = entry["label"]
            if entry["percent"] not in {"", "--", "N/A"}:
                label = f'{label}: {entry["percent"]}%'
            fields.append(clean_field(label, 28))
            fields.append(clean_field(entry["status"], 18))
        else:
            fields.append("--")
            fields.append("--")
    if len(fields) != ARDUINO_PAYLOAD_FIELD_COUNT:
        raise RuntimeError(
            f"Arduino payload field count mismatch: built {len(fields)} fields, "
            f"expected {ARDUINO_PAYLOAD_FIELD_COUNT}"
        )
    return "|".join(fields) + "\n"


def build_qbittorrent_payload(snapshot) -> str:
    qbt = snapshot["qbittorrent"]
    fields = [
        "QBT",
        clean_field(qbt.get("status", "qBittorrent unavailable"), 36),
        clean_field(qbt.get("active_downloads", "--"), 6),
        clean_field(qbt.get("active_seeding", "--"), 6),
        clean_field(qbt.get("down_speed", "--"), 18),
        clean_field(qbt.get("up_speed", "--"), 18),
        clean_field(qbt.get("top_torrent", "--"), 60),
        clean_field(qbt.get("top_state", "--"), 12),
        clean_field(qbt.get("progress", "--"), 10),
        clean_field(qbt.get("eta", "--"), 18),
    ]
    return "|".join(fields) + "\n"


def build_debug_payload(snapshot) -> str:
    pairs: List[str] = []

    def add(key: str, value: object, max_len: int = 64):
        pairs.append(f"{key}:{clean_field(value, max_len)}")

    add("HEADER", "Ray Co. Universal System Monitor", 48)
    add("CPU", snapshot["cpu_total"])
    add("RAM", snapshot["ram_pct"])
    add("DISK0", snapshot["disk0_pct"])
    add("DISK1", snapshot["disk1_pct"])
    add("FREQ", snapshot["cpu_freq"])
    add("RAMGB", snapshot["ram_usage_text"])
    add("TEMP", snapshot["cpu_temp"])
    add("UPTIME", snapshot["uptime"])
    add("OS", snapshot["os_name"])
    add("HOST", snapshot["host_name"])
    add("IP", snapshot["ip"])
    add("DOWN", snapshot["down_rate"])
    add("UPNET", snapshot["up_rate"])
    add("DNTOT", snapshot["down_total"])
    add("UPTOT", snapshot["up_total"])
    add("IFACE", snapshot["iface"])
    add("GPU", snapshot["gpu_util"])
    add("GPUTEMP", snapshot["gpu_temp"])
    add("VRAMUSED", f'{snapshot["gpu_mem_used"]}/{snapshot["gpu_mem_total"]}M')
    add("VRAMPCT", f'{snapshot["gpu_mem_pct"]}%')
    add("GPUCLK", f'{snapshot["gpu_clock"]}MHz')
    add("GPUNAME", snapshot["gpu_name"], 48)

    for i, core_val in enumerate(snapshot["per_core"]):
        add(f"C{i}", core_val)

    for idx, (name, cpu, ram) in enumerate(snapshot["procs"], start=1):
        add(f"P{idx}", name, 24)
        add(f"P{idx}CPU", cpu)
        add(f"P{idx}RAM", ram)

    for idx, line in enumerate(snapshot["storage_lines"], start=1):
        add(f"DRV{idx}", line, 40)
    storage_io_entries = snapshot.get("storage_io") or []
    for idx in range(STORAGE_IO_SLOTS):
        if idx < len(storage_io_entries):
            entry = storage_io_entries[idx]
            add(f"SIO{idx + 1}LBL", entry.get("label", "--"), 16)
            add(f"SIO{idx + 1}MNT", entry.get("mountpoint", "--"), 20)
            add(f"SIO{idx + 1}R", format_speed(entry.get("read_bps", 0.0)), 18)
            add(f"SIO{idx + 1}W", format_speed(entry.get("write_bps", 0.0)), 18)
            add(f"SIO{idx + 1}TOT", format_speed(entry.get("total_bps", 0.0)), 18)
            util_pct = entry.get("util_pct")
            add(f"SIO{idx + 1}UTIL", f"{util_pct}%" if isinstance(util_pct, int) else "--", 8)
        else:
            add(f"SIO{idx + 1}LBL", "--")
            add(f"SIO{idx + 1}MNT", "--")
            add(f"SIO{idx + 1}R", "0 B/s")
            add(f"SIO{idx + 1}W", "0 B/s")
            add(f"SIO{idx + 1}TOT", "0 B/s")
            add(f"SIO{idx + 1}UTIL", "--")
    add("BATTPCT", snapshot["battery_pct"])
    add("BATTSTATE", snapshot["battery_state"])
    add("BATTMODE", snapshot["battery_mode"])
    for idx in range(EXTRA_BATTERY_SLOTS):
        if idx < len(snapshot["extra_batteries"]):
            entry = snapshot["extra_batteries"][idx]
            add(f'BATTDEV{idx + 1}', entry["label"], 24)
            add(f"BATTDEV{idx + 1}STATE", entry["status"], 18)
        else:
            add(f"BATTDEV{idx + 1}", "--")
            add(f"BATTDEV{idx + 1}STATE", "--")

    return "|".join(pairs) + "\n"


def main_sender() -> None:
    if not acquire_single_instance_lock():
        print("Another instance is already running.")
        return

    static = get_cached_static()
    storage_targets = collect_storage_targets()
    enabled_storage_targets = [target for target in storage_targets if not STORAGE_ENABLED_TARGET_SET or target["id"] in STORAGE_ENABLED_TARGET_SET]
    print(f"Running Universal Arduino Monitor {APP_VERSION} for Linux")
    print("Originally tuned on Fedora; intended to work across Linux desktops.")
    print(f"Active network interface: {static['iface']}")
    print(f"OS: {static['os_name']}")
    print(f"Primary GPU vendor guess: {static['gpu_vendor']}")
    print(f"Primary disk mount: {ROOT_MOUNT}")
    print(f"Secondary disk mount: {static['secondary_mount']}")
    print(f"Transport mode: {'Simultaneous USB + Wi-Fi outputs' if WIFI_ENABLED else 'USB only'}")
    print(f"Program mode: {PROGRAM_MODE}")
    if PROGRAM_MODE == "Macro Mode":
        print(f"Macro mode staging: {len(MACRO_ENTRIES)} macro entries configured; trigger model='{MACRO_TRIGGER_MODEL}'.")
    if WIFI_ENABLED:
        print(f"Wi-Fi TCP port source: {WIFI_PORT_SOURCE} -> {WIFI_PORT}")
        if WIFI_HOST:
            print(f"Wi-Fi target (primary): direct fixed host/IP {WIFI_HOST}:{WIFI_PORT}")
        else:
            print("Wi-Fi target (primary): fixed host/IP is not configured yet (wifi_host is empty).")
        if WIFI_AUTO_DISCOVERY:
            fallback = " (optional fallback/debug path)" if WIFI_HOST else " (discovery-only until wifi_host is set)"
            filters: List[str] = []
            if WIFI_DEVICE_NAME and WIFI_DEVICE_NAME != "R4_WIFI35" and not WIFI_DISCOVERY_IGNORE_BOARD_FILTER:
                filters.append(f"board={WIFI_DEVICE_NAME}")
            if WIFI_TARGET_HOST:
                filters.append(f"target_host={WIFI_TARGET_HOST}")
            if WIFI_TARGET_HOSTNAME:
                filters.append(f"target_hostname={WIFI_TARGET_HOSTNAME}")
            filter_suffix = f" [{', '.join(filters)}]" if filters else ""
            print(f"Wi-Fi target (secondary): UDP auto-discovery on port {WIFI_DISCOVERY_PORT}{fallback}{filter_suffix}")
            if WIFI_DISCOVERY_DEBUG:
                print("Wi-Fi discovery debug mode enabled.")
            if WIFI_DISCOVERY_IGNORE_BOARD_FILTER:
                print("Wi-Fi discovery diagnostic override: board-name filter is temporarily bypassed.")
        else:
            print("Wi-Fi UDP auto-discovery disabled; monitor will use direct fixed host/IP only.")
    else:
        print("Wi-Fi transport disabled.")
    if DEBUG_MIRROR_ENABLED:
        print(f"Debug mirror enabled on: {DEBUG_MIRROR_PORT}")
    else:
        print("Debug mirror disabled (set debug_enabled + debug_port in config/monitor_config.json).")
    print(f"Arduino payload field count: {ARDUINO_PAYLOAD_FIELD_COUNT}")
    print(f"Arduino payload layout: {ARDUINO_PAYLOAD_LAYOUT}")

    psutil.cpu_percent(interval=None)
    psutil.cpu_percent(interval=None, percpu=True)
    prime_process_cpu()

    iface = static["iface"]
    net_stats = psutil.net_io_counters(pernic=True)
    last_net = net_stats.get(iface) or psutil.net_io_counters()
    last_time = time.time()

    arduino_serials: Dict[str, serial.Serial] = {}
    wifi_sock: Optional[socket.socket] = None
    debug_ser: Optional[serial.Serial] = connect_optional_serial(DEBUG_MIRROR_PORT, DEBUG_MIRROR_BAUD, "Debug mirror") if DEBUG_MIRROR_ENABLED else None

    monitor_start = time.time()
    last_send = 0.0
    last_discovery_attempt = 0.0
    last_wifi_attempt = 0.0
    last_wifi_success = 0.0
    last_wifi_error_log = 0.0
    wifi_error_suppressed = False
    no_transport_logged = False
    last_status = "NONE"
    wifi_host_active = WIFI_HOST
    wifi_port_active = WIFI_PORT
    wifi_name_active: Optional[str] = None
    last_wifi_discovery = 0.0

    try:
        while True:
            try:
                now = time.time()
                if now - last_send < SEND_INTERVAL:
                    time.sleep(IDLE_LOOP_SLEEP)
                    continue

                snapshot, last_net, last_time = collect_snapshot(last_net, last_time)
                payload = build_arduino_payload(snapshot)
                qbt_payload = build_qbittorrent_payload(snapshot)
                outbound_payload = payload + qbt_payload

                wifi_retry_delay = current_wifi_retry_delay(monitor_start, last_wifi_success, now)

                if WIFI_ENABLED and wifi_sock is None and (now - last_wifi_attempt) >= wifi_retry_delay:
                    last_wifi_attempt = now
                    quiet_wifi = wifi_error_suppressed and (now - last_wifi_error_log) < 30.0
                    candidate: Optional[socket.socket] = None
                    if WIFI_HOST:
                        if not quiet_wifi:
                            print(f"Wi-Fi connect path: direct fixed host/IP -> {WIFI_HOST}:{WIFI_PORT}")
                        candidate = connect_wifi_socket(
                            WIFI_HOST,
                            WIFI_PORT,
                            quiet=quiet_wifi,
                        )
                    if candidate is None and WIFI_AUTO_DISCOVERY:
                        if not quiet_wifi:
                            if WIFI_HOST:
                                print("Wi-Fi connect path: direct fixed host/IP failed, trying UDP discovery fallback.")
                            else:
                                print("Wi-Fi connect path: fixed host/IP not set, trying UDP discovery.")
                        wifi_host_active, wifi_port_active, last_wifi_discovery, discovered_record = resolve_wifi_endpoint(
                            "" if WIFI_HOST else wifi_host_active,
                            wifi_port_active,
                            last_wifi_discovery,
                        )
                        if discovered_record is not None:
                            wifi_name_active = discovered_record.name
                            if not quiet_wifi:
                                print(
                                    f"Wi-Fi connect path: discovery resolved board '{wifi_name_active}' "
                                    f"at {wifi_host_active}:{wifi_port_active}"
                                )
                        candidate = connect_wifi_socket(
                            wifi_host_active,
                            wifi_port_active,
                            quiet=quiet_wifi,
                            device_name=wifi_name_active,
                        )
                    if candidate is not None:
                        wifi_sock = candidate
                        last_wifi_success = now
                        wifi_error_suppressed = False
                        last_wifi_error_log = 0.0
                    else:
                        if WIFI_AUTO_DISCOVERY and not WIFI_HOST:
                            wifi_host_active = ""
                            wifi_port_active = WIFI_PORT
                            wifi_name_active = None
                        if not quiet_wifi:
                            last_wifi_error_log = now
                            wifi_error_suppressed = True

                if (now - last_discovery_attempt) >= RETRY_DELAY:
                    last_discovery_attempt = now
                    arduino_serials = connect_arduinos(arduino_serials)

                if arduino_serials:
                    arduino_serials = send_to_usb_devices(outbound_payload, arduino_serials)

                if wifi_sock is not None:
                    wifi_sock = send_to_wifi_device(outbound_payload, wifi_sock)
                    if wifi_sock is None:
                        last_wifi_attempt = now
                        if WIFI_AUTO_DISCOVERY and not WIFI_HOST:
                            wifi_host_active = ""
                            wifi_name_active = None

                active_transport = describe_active_transports(arduino_serials, wifi_sock)
                if active_transport != last_status:
                    print(f"Active transport: {active_transport}")
                    last_status = active_transport

                if active_transport == "NONE":
                    if not no_transport_logged:
                        if DEBUG_MIRROR_ENABLED:
                            print("No Arduino transport active. Debug mirror still running...")
                        else:
                            print("No Arduino transport active. Waiting for USB or Wi-Fi...")
                        no_transport_logged = True
                    time.sleep(1.0)
                else:
                    no_transport_logged = False

                if DEBUG_MIRROR_ENABLED:
                    debug_payload = build_debug_payload(snapshot)
                    debug_ser = write_optional_serial(debug_ser, debug_payload, DEBUG_MIRROR_PORT, DEBUG_MIRROR_BAUD, "Debug mirror")

                last_send = time.time()

            except KeyboardInterrupt:
                print("Exiting.")
                break

            except Exception as exc:
                print(f"Loop error: {exc}")
                time.sleep(1.0)
    finally:
        try:
            for ser in arduino_serials.values():
                try:
                    ser.close()
                except Exception:
                    pass
        except Exception:
            pass
        close_socket(wifi_sock)
        try:
            if debug_ser is not None:
                debug_ser.close()
        except Exception:
            pass
        release_single_instance_lock()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Ray Co. Universal Arduino System Monitor sender + RayFetch one-shot tools."
    )
    parser.add_argument("--rayfetch", action="store_true", help="Print one-shot terminal summary without connecting to Arduino.")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Print one-shot snapshot as JSON.")
    parser.add_argument(
        "--payload-preview",
        action="store_true",
        help="Print the exact Arduino payload generated from a one-shot snapshot without connecting.",
    )
    parser.add_argument(
        "--arduino-status",
        action="store_true",
        help="Print Arduino sender diagnostics (ports/interface/transport config) without connecting.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    one_shot_mode = args.rayfetch or args.as_json or args.payload_preview or args.arduino_status
    if one_shot_mode:
        if args.arduino_status:
            print_arduino_status()
        if args.rayfetch or args.as_json or args.payload_preview:
            static = get_cached_static()
            iface = static["iface"]
            net_stats = psutil.net_io_counters(pernic=True)
            last_net = net_stats.get(iface) or psutil.net_io_counters()
            last_time = time.time()
            snapshot, _, _ = collect_snapshot(last_net, last_time)
            if args.rayfetch:
                print_rayfetch(snapshot)
            if args.as_json:
                print(json.dumps(snapshot, indent=2))
            if args.payload_preview:
                print(build_arduino_payload(snapshot), end="")
        return

    main_sender()


if __name__ == "__main__":
    main()
