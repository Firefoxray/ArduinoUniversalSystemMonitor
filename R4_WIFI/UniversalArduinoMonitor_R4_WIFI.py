#!/usr/bin/env python3
"""Universal Linux Arduino system monitor sender v7.4.

Originally built on Fedora for an Arduino desktop monitor, but intended to run
across Linux desktops in general.

Highlights:
- Linux-focused (no Windows codepaths)
- Best-effort GPU support for NVIDIA, AMD, and Intel
- Auto-detects useful storage targets while still allowing overrides
- Handles Arduino disconnects/reconnects
- Preserves the positional payload shape expected by the matching Arduino sketch
- Optional debug mirror output for the Java fake display (KEY:VALUE stream)
- Configurable via monitor_config.json with environment variable overrides
"""

import json
import os
import re
import shutil
import socket
import subprocess
import time
import fcntl
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import psutil
import serial
from serial import SerialException
from serial.tools import list_ports

CONFIG_PATH = Path(__file__).with_name("monitor_config.json")
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


def load_config() -> Dict[str, object]:
    defaults: Dict[str, object] = {
        "arduino_port": "AUTO",
        "baud": 115200,
        "debug_enabled": False,
        "debug_port": "",
        "root_mount": "/",
        "secondary_mount": "/mnt/linux_storage",
        "send_interval": 1.0,
        "wifi_enabled": True,
        "wifi_host": "192.168.1.50",
        "wifi_port": 5000,
        "prefer_usb": True,
        "wifi_retry_delay": 5,
        "wifi_auto_discovery": True,
        "wifi_discovery_port": 5001,
        "wifi_discovery_timeout": 1.2,
        "wifi_discovery_refresh": 30,
        "wifi_discovery_magic": "UAM_DISCOVER",
    }
    try:
        if CONFIG_PATH.exists():
            raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                defaults.update(raw)
    except Exception as exc:
        print(f"Config load warning ({CONFIG_PATH}): {exc}")
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
GPU_CACHE_TTL = 0.75
PROC_CACHE_TTL = 1.0
STATIC_CACHE_TTL = 30.0
TEMP_CACHE_TTL = 1.0
STORAGE_CACHE_TTL = 10.0

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
STORAGE_LINES = 7
PROCESS_ROWS = 6
CPU_THREADS_TO_SEND = 16

DEBUG_MIRROR_PORT = str(os.environ.get("ARDUINO_MONITOR_DEBUG_PORT") or CONFIG.get("debug_port") or "").strip()
DEBUG_MIRROR_BAUD = to_int(os.environ.get("ARDUINO_MONITOR_DEBUG_BAUD"), BAUD)
DEBUG_MIRROR_ENABLED = to_bool(CONFIG.get("debug_enabled"), False) and bool(DEBUG_MIRROR_PORT)

WIFI_ENABLED = to_bool(os.environ.get("ARDUINO_MONITOR_WIFI_ENABLED", CONFIG.get("wifi_enabled")), True)
WIFI_HOST = str(os.environ.get("ARDUINO_MONITOR_WIFI_HOST") or CONFIG.get("wifi_host") or "").strip()
WIFI_PORT = to_int(os.environ.get("ARDUINO_MONITOR_WIFI_PORT", CONFIG.get("wifi_port")), 5000)
PREFER_USB = to_bool(os.environ.get("ARDUINO_MONITOR_PREFER_USB", CONFIG.get("prefer_usb")), True)
WIFI_RETRY_DELAY = max(2, to_int(os.environ.get("ARDUINO_MONITOR_WIFI_RETRY_DELAY", CONFIG.get("wifi_retry_delay")), 5))
WIFI_AUTO_DISCOVERY = to_bool(os.environ.get("ARDUINO_MONITOR_WIFI_AUTO_DISCOVERY", CONFIG.get("wifi_auto_discovery")), True)
WIFI_DISCOVERY_PORT = max(1, to_int(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_PORT", CONFIG.get("wifi_discovery_port")), 5001))
WIFI_DISCOVERY_TIMEOUT = max(0.2, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_TIMEOUT", CONFIG.get("wifi_discovery_timeout")), 1.2))
WIFI_DISCOVERY_REFRESH = max(5.0, to_float(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_REFRESH", CONFIG.get("wifi_discovery_refresh")), 30.0))
WIFI_DISCOVERY_MAGIC = str(os.environ.get("ARDUINO_MONITOR_WIFI_DISCOVERY_MAGIC") or CONFIG.get("wifi_discovery_magic") or "UAM_DISCOVER").strip()

_gpu_cache = {"ts": 0.0, "data": None}
_proc_cache = {"ts": 0.0, "data": None}
_static_cache = {"ts": 0.0, "data": None}
_temp_cache = {"ts": 0.0, "data": None}
_storage_cache = {"ts": 0.0, "data": None}


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


def get_os_name() -> str:
    def normalize_os_name(value: str) -> str:
        cleaned = re.sub(r"\s*\([^)]*\)", "", value).strip()
        if cleaned.lower().startswith("fedora linux"):
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
    for name, meta in stats.items():
        if not meta.isup or name == "lo":
            continue
        iface_addrs = addrs.get(name, [])
        if any(a.family == socket.AF_INET for a in iface_addrs):
            if name.startswith(("en", "eth", "wlan", "wl")):
                return name
            if preferred is None:
                preferred = name
    return preferred or "lo"


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



def parse_discovery_response(message: str, addr: Tuple[str, int]) -> Optional[Tuple[str, int, str]]:
    text = (message or "").strip()
    parts = text.split("|")
    if len(parts) < 3 or parts[0] != "UAM_HERE":
        return None
    host = parts[1].strip() or addr[0]
    try:
        port = int(parts[2].strip())
    except Exception:
        return None
    name = parts[3].strip() if len(parts) > 3 and parts[3].strip() else host
    return host, port, name


def discover_wifi_monitor(timeout: Optional[float] = None) -> Optional[Tuple[str, int, str]]:
    if not WIFI_ENABLED or not WIFI_AUTO_DISCOVERY:
        return None
    wait_time = WIFI_DISCOVERY_TIMEOUT if timeout is None else max(0.2, float(timeout))
    sock: Optional[socket.socket] = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(wait_time)
        sock.bind(("", 0))
        sock.sendto(WIFI_DISCOVERY_MAGIC.encode("utf-8"), ("255.255.255.255", WIFI_DISCOVERY_PORT))
        deadline = time.time() + wait_time
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(512)
            except socket.timeout:
                break
            parsed = parse_discovery_response(data.decode("utf-8", errors="ignore"), addr)
            if parsed is not None:
                return parsed
    except OSError:
        return None
    finally:
        try:
            if sock is not None:
                sock.close()
        except Exception:
            pass
    return None


def resolve_wifi_endpoint(cached_host: str, cached_port: int, last_discovery_ts: float) -> Tuple[str, int, float, Optional[str]]:
    host = cached_host
    port = cached_port if cached_port > 0 else WIFI_PORT
    discovered_name: Optional[str] = None
    should_refresh = WIFI_AUTO_DISCOVERY and (
        not host or (time.time() - last_discovery_ts) >= WIFI_DISCOVERY_REFRESH
    )
    if should_refresh:
        found = discover_wifi_monitor()
        if found is not None:
            host, port, discovered_name = found
            last_discovery_ts = time.time()
    if not host:
        host = WIFI_HOST
        port = WIFI_PORT
    return host, port, last_discovery_ts, discovered_name


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


def build_storage_lines(max_lines: int = STORAGE_LINES) -> List[str]:
    data = load_lsblk()
    lines: List[str] = []

    def add_line(label: str, mountpoint: str, size: str, percent: Optional[int]) -> None:
        base = clean_field(label, 12)
        mount_short = mountpoint or "unmnt"
        if mount_short.startswith("/mnt/"):
            mount_short = mount_short[5:]
        elif mount_short.startswith("/media/"):
            mount_short = mount_short[7:]
        elif mount_short.startswith("/run/media/"):
            mount_short = mount_short[11:]
        if mount_short == "/":
            mount_short = "root"
        mount_short = clean_field(mount_short, 8)
        if percent is None:
            line = f"{base} {size} {mount_short}"
        else:
            line = f"{base} {percent}% {mount_short}"
        lines.append(clean_field(line, 30))

    for disk in data.get("blockdevices", []):
        if disk.get("type") != "disk":
            continue
        model = clean_field(disk.get("label") or disk.get("model") or disk.get("name") or "disk", 12)
        size = clean_field(disk.get("size") or "", 8)
        children = disk.get("children") or []
        interesting_child = None
        for child in children:
            if child.get("fstype") in ("ext4", "btrfs", "xfs", "ntfs", "exfat", "vfat"):
                interesting_child = child
                if child.get("mountpoint") in (ROOT_MOUNT, pick_secondary_mount()):
                    break
        if interesting_child:
            label = interesting_child.get("label") or model
            mountpoint = interesting_child.get("mountpoint") or ""
            percent = get_mount_percent(mountpoint) if mountpoint else None
            add_line(label, mountpoint, size, percent)
        else:
            add_line(model, "", size, None)
        if len(lines) >= max_lines:
            break

    while len(lines) < max_lines:
        lines.append(f"Disk{len(lines)+1}: --")
    return lines[:max_lines]


def get_cached_storage_lines() -> List[str]:
    now = time.time()
    if _storage_cache["data"] is None or (now - _storage_cache["ts"]) >= STORAGE_CACHE_TTL:
        _storage_cache["data"] = build_storage_lines(STORAGE_LINES)
        _storage_cache["ts"] = now
    return _storage_cache["data"]


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


def get_cached_gpu_stats() -> Tuple[str, str, str, str, str, str, str]:
    now = time.time()
    if _gpu_cache["data"] is None or (now - _gpu_cache["ts"]) >= GPU_CACHE_TTL:
        _gpu_cache["data"] = get_gpu_stats()
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


def build_snapshot(last_net, last_time):
    cpu_total_val = psutil.cpu_percent(interval=CPU_SAMPLE_INTERVAL)
    per_core = psutil.cpu_percent(interval=None, percpu=True)
    while len(per_core) < CPU_THREADS_TO_SEND:
        per_core.append(0.0)
    per_core = per_core[:CPU_THREADS_TO_SEND]

    static = get_cached_static()
    iface = static["iface"]
    now_time = time.time()
    elapsed = max(now_time - last_time, 0.001)

    net_stats = psutil.net_io_counters(pernic=True)
    now_net = net_stats.get(iface) or psutil.net_io_counters()
    down_bps = (now_net.bytes_recv - last_net.bytes_recv) / elapsed
    up_bps = (now_net.bytes_sent - last_net.bytes_sent) / elapsed

    gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = get_cached_gpu_stats()
    procs = get_cached_top_processes()
    storage_lines = get_cached_storage_lines()

    snapshot = {
        "cpu_total": int(round(cpu_total_val)),
        "per_core": [int(round(x)) for x in per_core],
        "ram_pct": int(round(psutil.virtual_memory().percent)),
        "disk0_pct": get_mount_percent(ROOT_MOUNT),
        "disk1_pct": get_mount_percent(static["secondary_mount"]),
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
        "gpu_util": clean_field(gpu_util, 4),
        "gpu_temp": clean_field(gpu_temp, 12),
        "gpu_mem_used": clean_field(gpu_mem_used, 8),
        "gpu_mem_total": clean_field(gpu_mem_total, 8),
        "gpu_mem_pct": clean_field(gpu_mem_pct, 4),
        "gpu_clock": clean_field(gpu_clock, 8),
        "gpu_name": clean_field(gpu_name, 32),
        "procs": procs,
        "storage_lines": [clean_field(x, 30) for x in storage_lines],
        "optical": clean_field(get_optical_status(), 18),
        "iface": clean_field(iface, 18),
        "ram_usage_text": clean_field(format_ram_usage_gb(), 18),
        "secondary_mount": clean_field(static["secondary_mount"], 24),
    }

    return snapshot, now_net, now_time


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
    fields.append(snapshot["optical"])
    fields.append(snapshot["ram_usage_text"])
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
    add("OPTICAL", snapshot["optical"])
    add("OPT", snapshot["optical"])
    add("RAMGB", snapshot["ram_usage_text"])

    return "|".join(pairs) + "\n"


def main() -> None:
    if not acquire_single_instance_lock():
        print("Another instance is already running.")
        return

    static = get_cached_static()
    print("Running Universal Arduino Monitor 7.4 for Linux")
    print("Originally tuned on Fedora; intended to work across Linux desktops.")
    print(f"Active network interface: {static['iface']}")
    print(f"OS: {static['os_name']}")
    print(f"Primary GPU vendor guess: {static['gpu_vendor']}")
    print(f"Primary disk mount: {ROOT_MOUNT}")
    print(f"Secondary disk mount: {static['secondary_mount']}")
    print(f"Preferred transport: {'USB + Wi-Fi simultaneous' if WIFI_ENABLED else 'USB only'}")
    if WIFI_ENABLED:
        if WIFI_AUTO_DISCOVERY:
            fallback = f" (fallback {WIFI_HOST}:{WIFI_PORT})" if WIFI_HOST else ""
            print(f"Wi-Fi target: auto-discovery on UDP {WIFI_DISCOVERY_PORT}{fallback}")
        else:
            print(f"Wi-Fi target: {WIFI_HOST}:{WIFI_PORT}")
    else:
        print("Wi-Fi transport disabled.")
    if DEBUG_MIRROR_ENABLED:
        print(f"Debug mirror enabled on: {DEBUG_MIRROR_PORT}")
    else:
        print("Debug mirror disabled (set debug_enabled + debug_port in monitor_config.json).")

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

    last_send = 0.0
    last_discovery_attempt = 0.0
    last_wifi_attempt = 0.0
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

                snapshot, last_net, last_time = build_snapshot(last_net, last_time)
                payload = build_arduino_payload(snapshot)

                if (now - last_discovery_attempt) >= RETRY_DELAY:
                    last_discovery_attempt = now
                    arduino_serials = connect_arduinos(arduino_serials)

                if WIFI_ENABLED and wifi_sock is None and (now - last_wifi_attempt) >= WIFI_RETRY_DELAY:
                    last_wifi_attempt = now
                    wifi_host_active, wifi_port_active, last_wifi_discovery, discovered_name = resolve_wifi_endpoint(
                        wifi_host_active,
                        wifi_port_active,
                        last_wifi_discovery,
                    )
                    if discovered_name:
                        wifi_name_active = discovered_name
                    quiet_wifi = wifi_error_suppressed and (now - last_wifi_error_log) < 30.0
                    candidate = connect_wifi_socket(
                        wifi_host_active,
                        wifi_port_active,
                        quiet=quiet_wifi,
                        device_name=wifi_name_active,
                    )
                    if candidate is not None:
                        wifi_sock = candidate
                        wifi_error_suppressed = False
                        last_wifi_error_log = 0.0
                    else:
                        if WIFI_AUTO_DISCOVERY:
                            wifi_host_active = ""
                            wifi_port_active = WIFI_PORT
                            wifi_name_active = None
                        if not quiet_wifi:
                            last_wifi_error_log = now
                            wifi_error_suppressed = True

                if arduino_serials:
                    arduino_serials = send_to_usb_devices(payload, arduino_serials)

                if wifi_sock is not None:
                    wifi_sock = send_to_wifi_device(payload, wifi_sock)
                    if wifi_sock is None:
                        last_wifi_attempt = now
                        if WIFI_AUTO_DISCOVERY:
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


if __name__ == "__main__":
    main()
