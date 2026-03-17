#!/usr/bin/env python3
"""Fedora-specific Arduino system monitor sender.

Tuned for a single Fedora desktop and an Arduino display.
Removes Windows-specific codepaths and sends a richer payload:
- 16 logical CPU threads
- Fedora root disk + linux_share disk usage on the home page
- 7 storage inventory lines for the storage page
- Linux/AMD GPU stats for RX 6600 via sysfs
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
PREFERRED_PORT = None

CPU_SAMPLE_INTERVAL = 0.20
SEND_INTERVAL = 1.0
IDLE_LOOP_SLEEP = 0.03
GPU_CACHE_TTL = 0.75
PROC_CACHE_TTL = 1.0
STATIC_CACHE_TTL = 30.0
TEMP_CACHE_TTL = 1.0

ROOT_MOUNT = "/"
SHARE_MOUNT = "/mnt/linux_storage"
STORAGE_LINES = 7
PROCESS_ROWS = 6
CPU_THREADS_TO_SEND = 16

_gpu_cache = {"ts": 0.0, "data": None}
_proc_cache = {"ts": 0.0, "data": None}
_static_cache = {"ts": 0.0, "data": None}
_temp_cache = {"ts": 0.0, "data": None}

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


def run_cmd(cmd: List[str], timeout: float = 2.0) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
        return (result.stdout or "").strip()
    except Exception:
        return ""


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
            name = data.get("NAME", "Fedora Linux")
            version = data.get("VERSION_ID", "")
            if version:
                return clean_field(f"{name} {version}", 28)
            return clean_field(name, 28)
    except Exception:
        pass
    return "Fedora Linux"


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
            if name.startswith(("en", "eth")):
                return name
            if preferred is None:
                preferred = name
    return preferred or "lo"


def get_temp() -> str:
    try:
        temps = psutil.sensors_temperatures(fahrenheit=False)
        preferred_keys = ("coretemp", "k10temp", "cpu_thermal", "zenpower")
        for key in preferred_keys:
            for entry in temps.get(key, []):
                label = (entry.label or "").lower()
                if any(tok in label for tok in ("package", "tctl", "tdie")) or not label:
                    if entry.current is not None:
                        return f"{entry.current:.1f}C"
        for _, entries in temps.items():
            for entry in entries:
                label = (entry.label or "").lower()
                if any(tok in label for tok in ("package", "core", "tctl", "tdie")):
                    if entry.current is not None:
                        return f"{entry.current:.1f}C"
    except Exception:
        pass

    sensors_out = run_cmd(["sensors"], timeout=2)
    for line in sensors_out.splitlines():
        lower = line.lower()
        if any(tok in lower for tok in ("package id 0", "package", "tctl", "tdie")):
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
        model = clean_field(disk.get("model") or disk.get("name") or "disk", 12)
        size = clean_field(disk.get("size") or "", 8)
        children = disk.get("children") or []
        interesting_child = None
        for child in children:
            if child.get("fstype") in ("ext4", "btrfs", "ntfs", "exfat", "vfat"):
                interesting_child = child
                if child.get("mountpoint") in (ROOT_MOUNT, SHARE_MOUNT):
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


def find_amd_gpu_card() -> Optional[Path]:
    drm_root = Path("/sys/class/drm")
    for card in sorted(drm_root.glob("card[0-9]*")):
        vendor = (card / "device/vendor")
        if vendor.exists():
            try:
                if vendor.read_text().strip().lower() == "0x1002":
                    return card
            except Exception:
                pass
    return None


def get_amd_gpu_name(card=None):
    try:
        out = subprocess.check_output(
            ["lspci"], stderr=subprocess.DEVNULL
        ).decode(errors="ignore")

        for line in out.splitlines():
            if "VGA" in line or "Display" in line:
                if "AMD" in line or "ATI" in line:
                    name = line.split(":", 2)[-1].strip()

                    # Extract proper Radeon name
                    match = re.search(r"\[([^\]]*Radeon[^\]]*)\]", name, re.IGNORECASE)
                    if match:
                        pretty = match.group(1).strip()

                        # Force clean branding for your GPU specifically
                        if "6600" in pretty:
                            return "AMD Radeon RX 6600"

                        return clean_field(f"AMD {pretty}", 32)

                    # fallback cleanup
                    name = re.sub(r"\(rev [^)]+\)", "", name)
                    name = re.sub(r"\[[^\]]+\]", "", name)
                    name = name.replace("Advanced Micro Devices, Inc. ", "")
                    name = name.replace("AMD/ATI ", "")
                    name = re.sub(r"\s+", " ", name).strip()

                    if "6600" in name:
                        return "AMD Radeon RX 6600"

                    return clean_field(f"AMD {name}", 32)

    except Exception:
        pass

    return "AMD GPU"


def read_hwmon_temp_c(device_path: Path) -> Optional[str]:
    hwmon_root = device_path / "device/hwmon"
    if not hwmon_root.exists():
        return None
    for hwmon in sorted(hwmon_root.glob("hwmon*")):
        for temp_file in sorted(hwmon.glob("temp*_input")):
            try:
                milli = int(temp_file.read_text().strip())
                if milli > 0:
                    return f"{milli / 1000.0:.1f}C"
            except Exception:
                continue
    return None


def read_gpu_clock_mhz(device_path: Path) -> str:
    pp = device_path / "device/pp_dpm_sclk"
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


def get_gpu_stats() -> Tuple[str, str, str, str, str, str, str]:
    card = find_amd_gpu_card()
    if not card:
        return "0", "N/A", "0", "0", "0", "0", "No AMD GPU"

    util = clean_field((card / "device/gpu_busy_percent").read_text().strip() if (card / "device/gpu_busy_percent").exists() else "0", 4)
    temp = read_hwmon_temp_c(card) or "N/A"

    used = 0
    total = 0
    try:
        used = int((card / "device/mem_info_vram_used").read_text().strip())
    except Exception:
        used = 0
    try:
        total = int((card / "device/mem_info_vram_total").read_text().strip())
    except Exception:
        total = 0

    used_mb = max(0, int(round(used / (1024 * 1024))))
    total_mb = max(0, int(round(total / (1024 * 1024))))
    pct = int(round((used_mb / total_mb) * 100)) if total_mb > 0 else 0
    clock = read_gpu_clock_mhz(card)
    name = get_amd_gpu_name(card)
    return str(int(float(util or 0))), temp, str(used_mb), str(total_mb), str(pct), clock, name


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
        _static_cache["data"] = {
            "os_name": get_os_name(),
            "host_name": get_hostname(),
            "iface": get_network_iface(),
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
    fedora_disk_pct = str(get_mount_percent(ROOT_MOUNT))
    share_disk_pct = str(get_mount_percent(SHARE_MOUNT))

    static = get_cached_static()
    iface = static["iface"]
    net_stats = psutil.net_io_counters(pernic=True)
    now_net = net_stats.get(iface) or psutil.net_io_counters()
    now_time = time.time()
    elapsed = max(now_time - last_time, 0.001)

    down_bps = (now_net.bytes_recv - last_net.bytes_recv) / elapsed
    up_bps = (now_net.bytes_sent - last_net.bytes_sent) / elapsed

    gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = get_cached_gpu_stats()
    procs = get_cached_top_processes()
    storage_lines = build_storage_lines(STORAGE_LINES)
    optical = clean_field(get_optical_status(), 18)

    fields: List[str] = []
    fields.append(f"{cpu_total_val:.0f}")
    fields.extend([f"{x:.0f}" for x in per_core])
    fields.extend([
        ram,
        fedora_disk_pct,
        share_disk_pct,
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
    print("Running Fedora-only Arduino monitor sender")
    static = get_cached_static()
    print(f"Active network interface: {static['iface']}")
    print(f"OS: {static['os_name']}")

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
