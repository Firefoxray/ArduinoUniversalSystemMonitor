#!/usr/bin/env python3
"""Universal Linux Arduino system monitor sender v7.2.

Originally built on Fedora for an Arduino desktop monitor, but intended to run
across Linux desktops in general.

Highlights:
- Linux-focused (no Windows codepaths)
- Best-effort GPU support for NVIDIA, AMD, and Intel
- Auto-detects useful storage targets while still allowing overrides
- Handles Arduino disconnects/reconnects
- Preserves the payload shape expected by the matching Arduino display sketch
"""

import json
import os
import re
import shutil
import socket
import subprocess
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import psutil
import serial
from serial import SerialException
from serial.tools import list_ports

BAUD = 115200
RETRY_DELAY = 10
SERIAL_SETTLE_DELAY = 2
PREFERRED_PORT = os.environ.get("ARDUINO_MONITOR_PORT") or None

CPU_SAMPLE_INTERVAL = 0.20
SEND_INTERVAL = 1.0
IDLE_LOOP_SLEEP = 0.03
GPU_CACHE_TTL = 0.75
PROC_CACHE_TTL = 1.0
STATIC_CACHE_TTL = 30.0
TEMP_CACHE_TTL = 1.0
STORAGE_CACHE_TTL = 10.0

ROOT_MOUNT = os.environ.get("ARDUINO_MONITOR_ROOT", "/")
SECONDARY_MOUNT_CANDIDATES = [
    os.environ.get("ARDUINO_MONITOR_STORAGE"),
    "/mnt/linux_storage",
    "/mnt/storage",
    "/mnt/data",
    "/home",
]
STORAGE_LINES = 7
PROCESS_ROWS = 6
CPU_THREADS_TO_SEND = 16

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
                return clean_field(pretty, 28)
            name = data.get("NAME", "Linux")
            version = data.get("VERSION_ID", "")
            return clean_field(f"{name} {version}".strip(), 28)
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
    if PREFERRED_PORT:
        ports.append(PREFERRED_PORT)
    for port in list_ports.comports():
        device = port.device
        if device not in ports and ("ttyACM" in device or "ttyUSB" in device):
            ports.append(device)
    return ports



def connect_arduino() -> serial.Serial:
    while True:
        for port in list_candidate_ports():
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
                print(f"Failed to open {port}: {exc}")
        print("Waiting for Arduino to reconnect...")
        time.sleep(RETRY_DELAY)



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
    lookup = {
        "0x10de": "NVIDIA",
        "0x1002": "AMD",
        "0x8086": "Intel",
    }
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
        name = re.sub(r"\(rev [^)]+\)", "", name)
        name = re.sub(r"\[[^\]]+\]", "", name)
        name = name.replace("Advanced Micro Devices, Inc. ", "")
        name = name.replace("AMD/ATI ", "")
        name = name.replace("Corporation", "")
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
    out = run_cmd([
        "nvidia-smi",
        f"--query-gpu={query}",
        "--format=csv,noheader,nounits",
    ], timeout=3)
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
    for candidate in (
        card / "gt_cur_freq_mhz",
        card / "device/gt_cur_freq_mhz",
        card / "device/pp_cur_state",
    ):
        val = read_text(candidate)
        num = extract_first_number(val)
        if num is not None:
            return str(int(num))
    return "0"



def get_intel_gpu_top_busy() -> Optional[int]:
    if not cmd_exists("intel_gpu_top"):
        return None

    # Newer intel_gpu_top builds can emit JSON, but support is weird across distros,
    # because Linux likes a little chaos as a treat.
    for cmd in (
        ["intel_gpu_top", "-J", "-s", "250", "-L"],
        ["intel_gpu_top", "-J", "-s", "250"],
    ):
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
    # Intel iGPU memory is shared system RAM, so VRAM reporting is fuzzy soup.
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



def build_payload(last_net, last_time):
    cpu_total_val = psutil.cpu_percent(interval=CPU_SAMPLE_INTERVAL)
    per_core = psutil.cpu_percent(interval=None, percpu=True)
    while len(per_core) < CPU_THREADS_TO_SEND:
        per_core.append(0.0)
    per_core = per_core[:CPU_THREADS_TO_SEND]

    ram = f"{psutil.virtual_memory().percent:.0f}"
    static = get_cached_static()
    primary_disk_pct = str(get_mount_percent(ROOT_MOUNT))
    secondary_disk_pct = str(get_mount_percent(static["secondary_mount"]))

    iface = static["iface"]
    net_stats = psutil.net_io_counters(pernic=True)
    now_net = net_stats.get(iface) or psutil.net_io_counters()
    now_time = time.time()
    elapsed = max(now_time - last_time, 0.001)

    down_bps = (now_net.bytes_recv - last_net.bytes_recv) / elapsed
    up_bps = (now_net.bytes_sent - last_net.bytes_sent) / elapsed

    gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = get_cached_gpu_stats()
    procs = get_cached_top_processes()
    storage_lines = get_cached_storage_lines()
    optical = clean_field(get_optical_status(), 18)

    fields: List[str] = []
    fields.append(f"{cpu_total_val:.0f}")
    fields.extend([f"{x:.0f}" for x in per_core])
    fields.extend([
        ram,
        primary_disk_pct,
        secondary_disk_pct,
        clean_field(get_cached_temp(), 12),
        clean_field(static["os_name"], 24),
        clean_field(static["host_name"], 24),
        clean_field(get_ip(), 18),
        clean_field(get_uptime(), 18),
        clean_field(format_speed(down_bps), 18),
        clean_field(format_speed(up_bps), 18),
        clean_field(format_total_bytes(now_net.bytes_recv), 18),
        clean_field(format_total_bytes(now_net.bytes_sent), 18),
        clean_field(get_cpu_freq(), 18),
        clean_field(gpu_util, 4),
        clean_field(gpu_temp, 12),
        clean_field(gpu_mem_used, 8),
        clean_field(gpu_mem_total, 8),
        clean_field(gpu_mem_pct, 4),
        clean_field(gpu_clock, 8),
        clean_field(gpu_name, 32),
    ])
    for name, _, _ in procs:
        fields.append(clean_field(name, 18))
    for _, cpu, _ in procs:
        fields.append(clean_field(cpu, 6))
    for _, _, ramv in procs:
        fields.append(clean_field(ramv, 8))
    fields.extend([clean_field(x, 30) for x in storage_lines])
    fields.append(optical)
    payload = "|".join(fields) + "\n"
    return payload, now_net, now_time



def main() -> None:
    static = get_cached_static()
    print("Running Universal Arduino Monitor 7.2 for Linux")
    print("Originally tuned on Fedora; intended to work across Linux desktops.")
    print(f"Active network interface: {static['iface']}")
    print(f"OS: {static['os_name']}")
    print(f"Primary GPU vendor guess: {static['gpu_vendor']}")
    print(f"Primary disk mount: {ROOT_MOUNT}")
    print(f"Secondary disk mount: {static['secondary_mount']}")

    psutil.cpu_percent(interval=None)
    psutil.cpu_percent(interval=None, percpu=True)
    prime_process_cpu()

    iface = static["iface"]
    net_stats = psutil.net_io_counters(pernic=True)
    last_net = net_stats.get(iface) or psutil.net_io_counters()
    last_time = time.time()
    ser = connect_arduino()
    last_send = 0.0

    while True:
        try:
            now = time.time()
            if now - last_send < SEND_INTERVAL:
                time.sleep(IDLE_LOOP_SLEEP)
                continue
            payload, last_net, last_time = build_payload(last_net, last_time)
            ser.write(payload.encode("utf-8"))
            last_send = time.time()
        except (SerialException, OSError) as exc:
            print(f"Arduino disconnected or serial write failed: {exc}")
            try:
                ser.close()
            except Exception:
                pass
            time.sleep(RETRY_DELAY)
            ser = connect_arduino()
        except KeyboardInterrupt:
            print("Exiting.")
            break
        except Exception as exc:
            print(f"Loop error: {exc}")
            time.sleep(1.0)


if __name__ == "__main__":
    main()
