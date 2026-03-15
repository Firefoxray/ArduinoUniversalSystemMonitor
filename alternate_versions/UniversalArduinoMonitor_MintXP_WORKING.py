#!/usr/bin/env python3
# Arduino Monitor 4.4.2

import os
import time
import socket
import psutil
import serial
import subprocess
import platform
import json
import re
import tempfile
import shutil
from functools import lru_cache
from urllib.request import urlopen
from urllib.error import URLError
from pathlib import Path
from serial import SerialException
from serial.tools import list_ports

BAUD = 115200
RETRY_DELAY = 10
SERIAL_SETTLE_DELAY = 2
PREFERRED_PORT = None

IS_WINDOWS = platform.system().lower() == "windows"
IS_LINUX = platform.system().lower() == "linux"
WINDOWS_RELEASE = platform.release().strip() if IS_WINDOWS else ""
IS_WINDOWS_7 = IS_WINDOWS and WINDOWS_RELEASE == "7"
IS_WINDOWS_10_OR_11 = IS_WINDOWS and WINDOWS_RELEASE in ("10", "11")

# Per-platform timing tuned for one script across Win7/10/11 and Linux.
# Win7 gets a gentler cadence so it does not flicker or hammer serial.
# Win10/11 gets a much snappier cadence.
CPU_SAMPLE_INTERVAL = 0.22 if IS_WINDOWS_7 else (0.03 if IS_WINDOWS_10_OR_11 else 0.4)
SEND_INTERVAL = 0.16 if IS_WINDOWS_7 else (0.03 if IS_WINDOWS_10_OR_11 else 1.5)
IDLE_LOOP_SLEEP = 0.004 if IS_WINDOWS_10_OR_11 else 0.02

LHM_URL_CANDIDATES = [
    "http://127.0.0.1:8085/data.json",
    "http://localhost:8085/data.json",
    "http://127.0.0.1:8085/",
    "http://localhost:8085/",
]

# Caches to avoid re-running expensive sensor/process discovery every loop.
GPU_CACHE_TTL = 1.2 if IS_WINDOWS_7 else (0.40 if IS_WINDOWS_10_OR_11 else 0.50)
PROC_CACHE_TTL = 1.4 if IS_WINDOWS_7 else (0.75 if IS_WINDOWS_10_OR_11 else 0.90)
STATIC_INFO_CACHE_TTL = 30.0
TEMP_CACHE_TTL = 1.5 if IS_WINDOWS else 0.8

_gpu_cache = {"ts": 0.0, "data": None}
_proc_cache = {"ts": 0.0, "data": None}
_static_cache = {"ts": 0.0, "data": None}
_temp_cache = {"ts": 0.0, "data": None}


def clean_field(text: str, max_len: int = 18) -> str:
    text = str(text).replace("|", "/").replace("\n", " ").replace("\r", " ").strip()
    if not text:
        return "--"
    return text[:max_len]


def extract_first_number(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    match = re.search(r"-?\d+(?:\.\d+)?", str(value))
    return float(match.group(0)) if match else None


def iter_lhm_nodes(node, parents=None):
    if parents is None:
        parents = []

    if isinstance(node, dict):
        yield node, parents
        node_name = str(node.get("Text", node.get("Name", "")))
        next_parents = parents + [node_name] if node_name else parents
        for key in ("Children", "children", "Sensors", "sensors", "SubHardware", "Hardware"):
            children = node.get(key)
            if isinstance(children, list):
                for child in children:
                    yield from iter_lhm_nodes(child, next_parents)
    elif isinstance(node, list):
        for item in node:
            yield from iter_lhm_nodes(item, parents)


def lhm_node_text(node, parents=None):
    parts = []
    if parents:
        parts.extend([str(x) for x in parents if x])
    for key in ("Text", "Name", "Identifier", "SensorType", "Type"):
        val = node.get(key)
        if val:
            parts.append(str(val))
    return " | ".join(parts).lower()


def _read_text_file(path_str):
    try:
        return Path(path_str).read_text(encoding="utf-8", errors="ignore").strip()
    except Exception:
        return None


def _read_int_file(path_str):
    raw = _read_text_file(path_str)
    if raw is None:
        return None
    try:
        return int(raw)
    except Exception:
        num = extract_first_number(raw)
        return int(num) if num is not None else None


def _scale_milli_or_micro_to_c(value):
    if value is None:
        return None
    if value > 1000:
        return value / 1000.0
    return float(value)


def _scale_hz_to_mhz(value):
    if value is None:
        return None
    if value > 1000000:
        return int(round(value / 1000000.0))
    if value > 1000:
        return int(round(value / 1000.0))
    return int(round(value))


def _read_symlink_name(path_str):
    try:
        return Path(path_str).resolve().name.lower()
    except Exception:
        return ''


def get_linux_gpu_vendor_kind(card=None):
    card = card or get_preferred_linux_gpu_card()
    if not card:
        return 'unknown'

    vendor = (card.get('vendor') or '').lower()
    driver = (card.get('driver') or '').lower()
    driver_name = _read_symlink_name(card['device_path'] / 'driver')
    combined = ' '.join([vendor, driver, driver_name])

    if '0x10de' in combined or 'nvidia' in combined:
        return 'nvidia'
    if '0x1002' in combined or '0x1022' in combined or 'amdgpu' in combined or 'radeon' in combined:
        return 'amd'
    if '0x8086' in combined or 'i915' in combined or 'xe' in combined or 'intel' in combined:
        return 'intel'
    return 'unknown'


def _find_first_existing(device_path, names):
    for name in names:
        path = device_path / name
        if path.exists():
            return path
    return None


def _parse_json_objects_from_stream(raw_text):
    objects = []
    if not raw_text:
        return objects

    depth = 0
    start = None
    in_string = False
    escape = False

    for i, ch in enumerate(raw_text):
        if in_string:
            if escape:
                escape = False
            elif ch == '\\':
                escape = True
            elif ch == '"':
                in_string = False
            continue

        if ch == '"':
            in_string = True
            continue
        if ch == '{':
            if depth == 0:
                start = i
            depth += 1
        elif ch == '}':
            if depth > 0:
                depth -= 1
                if depth == 0 and start is not None:
                    snippet = raw_text[start:i + 1]
                    try:
                        objects.append(json.loads(snippet))
                    except Exception:
                        pass
                    start = None
    return objects


def _flatten_json_leaves(node, path=''):
    if isinstance(node, dict):
        for key, value in node.items():
            next_path = f'{path}.{key}' if path else str(key)
            yield from _flatten_json_leaves(value, next_path)
    elif isinstance(node, list):
        for idx, value in enumerate(node):
            next_path = f'{path}[{idx}]'
            yield from _flatten_json_leaves(value, next_path)
    else:
        yield path.lower(), node


def _pick_intel_gpu_top_util(sample):
    candidates = []
    preferred_tokens = ('render', '3d', 'compute', 'video', 'copy', 'blitter', 'busy', 'sema', 'wait')
    bad_tokens = ('imc', 'rapl', 'power', 'energy', 'frequency requested')

    for path, value in _flatten_json_leaves(sample):
        lower_path = path.lower()
        if any(tok in lower_path for tok in bad_tokens):
            continue
        num = extract_first_number(value)
        if num is None:
            continue
        val_text = str(value).lower()
        if '%' in val_text or 'busy' in lower_path or any(tok in lower_path for tok in preferred_tokens):
            candidates.append(float(num))

    if not candidates:
        return None
    util = max(candidates)
    return max(0, min(100, int(round(util))))


def _run_intel_gpu_top_sample(card=None, timeout_sec=1.2):
    exe = shutil.which('intel_gpu_top')
    if not exe:
        return None

    cmd = [exe, '-J', '-s', '100', '-o', '-']
    if card and card.get('card'):
        drm_node = f"/dev/dri/{card['card']}"
        if Path(drm_node).exists():
            cmd.extend(['-d', f'drm:{drm_node}'])

    proc = None
    raw = ''
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            stdout, _stderr = proc.communicate(timeout=timeout_sec)
            raw = stdout or ''
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, _stderr = proc.communicate(timeout=1)
            raw = stdout or ''
    except Exception:
        if proc is not None:
            try:
                proc.kill()
            except Exception:
                pass
        return None

    objects = _parse_json_objects_from_stream(raw)
    return objects[-1] if objects else None


def get_linux_drm_cards():
    cards = []
    drm_root = Path('/sys/class/drm')
    if not drm_root.exists():
        return cards

    for card_path in sorted(drm_root.glob('card[0-9]*')):
        device_path = card_path / 'device'
        if not device_path.exists():
            continue

        vendor = (_read_text_file(device_path / 'vendor') or '').lower()
        driver = (_read_text_file(device_path / 'driver' / 'module' / 'drivers') or '')
        uevent = _read_text_file(device_path / 'uevent') or ''
        slot = None
        for line in uevent.splitlines():
            if line.startswith('PCI_SLOT_NAME='):
                slot = line.split('=', 1)[1].strip()
                break

        cards.append({
            'card': card_path.name,
            'card_path': card_path,
            'device_path': device_path,
            'vendor': vendor,
            'driver': driver.lower(),
            'slot': slot,
        })

    return cards


def get_preferred_linux_gpu_card():
    cards = get_linux_drm_cards()
    if not cards:
        return None

    def score(card):
        vendor = card.get('vendor', '')
        driver = card.get('driver', '')
        device_path = card['device_path']
        amdgpu_bonus = 100 if ('0x1002' in vendor or 'amdgpu' in driver) else 0
        nvidia_bonus = 90 if ('0x10de' in vendor or 'nvidia' in driver) else 0
        intel_bonus = 10 if ('0x8086' in vendor or 'i915' in driver or 'xe' in driver) else 0
        util_bonus = 5 if (device_path / 'gpu_busy_percent').exists() else 0
        vram_bonus = 5 if (device_path / 'mem_info_vram_total').exists() else 0
        return amdgpu_bonus + nvidia_bonus + intel_bonus + util_bonus + vram_bonus

    return max(cards, key=score)


def get_linux_amd_gpu_stats(card=None):
    card = card or get_preferred_linux_gpu_card()
    if not card:
        return None

    device_path = card['device_path']
    vendor = card.get('vendor', '')
    driver = card.get('driver', '')
    if '0x1002' not in vendor and 'amdgpu' not in driver and 'radeon' not in driver:
        return None

    util = _read_int_file(device_path / 'gpu_busy_percent')

    temp_c = None
    for hwmon in sorted((device_path / 'hwmon').glob('hwmon*')):
        temp_raw = _read_int_file(hwmon / 'temp1_input')
        if temp_raw is not None:
            temp_c = _scale_milli_or_micro_to_c(temp_raw)
            break

    mem_used = _read_int_file(device_path / 'mem_info_vram_used')
    mem_total = _read_int_file(device_path / 'mem_info_vram_total')
    if mem_used is not None:
        mem_used = int(round(mem_used / (1024 * 1024)))
    if mem_total is not None:
        mem_total = int(round(mem_total / (1024 * 1024)))

    clock_mhz = None
    for hwmon in sorted((device_path / 'hwmon').glob('hwmon*')):
        freq_raw = _read_int_file(hwmon / 'freq1_input')
        if freq_raw is not None:
            clock_mhz = _scale_hz_to_mhz(freq_raw)
            break

    if clock_mhz is None:
        pp_dpm_sclk = _read_text_file(device_path / 'pp_dpm_sclk')
        if pp_dpm_sclk:
            active = None
            for line in pp_dpm_sclk.splitlines():
                if '*' in line:
                    active = line
                    break
            if active is None and pp_dpm_sclk.splitlines():
                active = pp_dpm_sclk.splitlines()[-1]
            num = extract_first_number(active)
            if num is not None:
                clock_mhz = int(round(num))

    mem_pct = int(round((mem_used / mem_total) * 100)) if mem_used is not None and mem_total else 0

    return {
        'gpu_util': str(max(0, min(100, int(util or 0)))),
        'gpu_temp': f"{int(round(temp_c))}C" if temp_c is not None else 'N/A',
        'gpu_mem_used': str(max(0, int(mem_used or 0))),
        'gpu_mem_total': str(max(0, int(mem_total or 0))),
        'gpu_mem_pct': str(max(0, min(100, int(mem_pct)))),
        'gpu_clock': str(max(0, int(clock_mhz or 0))),
    }


def get_linux_intel_gpu_stats(card=None):
    card = card or get_preferred_linux_gpu_card()
    if not card:
        return None

    if get_linux_gpu_vendor_kind(card) != 'intel':
        return None

    device_path = card['device_path']

    util = _read_int_file(device_path / 'gpu_busy_percent')
    if util is None:
        sample = _run_intel_gpu_top_sample(card)
        if sample is not None:
            util = _pick_intel_gpu_top_util(sample)

    temp_c = None
    for hwmon in sorted((device_path / 'hwmon').glob('hwmon*')):
        temp_raw = _read_int_file(hwmon / 'temp1_input')
        if temp_raw is not None:
            temp_c = _scale_milli_or_micro_to_c(temp_raw)
            break

    clock_raw = None
    freq_path = _find_first_existing(device_path, ['gt_cur_freq_mhz', 'gt_act_freq_mhz'])
    if freq_path is not None:
        clock_raw = _read_int_file(freq_path)
    if clock_raw is None:
        for hwmon in sorted((device_path / 'hwmon').glob('hwmon*')):
            freq_raw = _read_int_file(hwmon / 'freq1_input')
            if freq_raw is not None:
                clock_raw = _scale_hz_to_mhz(freq_raw)
                break
    clock_mhz = _scale_hz_to_mhz(clock_raw) if clock_raw is not None else 0

    mem_total = None
    mem_used = None
    total_path = _find_first_existing(device_path, ['lmem_total_bytes', 'mem_info_vram_total'])
    used_path = _find_first_existing(device_path, ['lmem_used_bytes', 'mem_info_vram_used'])
    if total_path is not None:
        mem_total = _read_int_file(total_path)
    if used_path is not None:
        mem_used = _read_int_file(used_path)

    if mem_total is not None:
        mem_total = int(round(mem_total / (1024 * 1024)))
    else:
        mem_total = 0
    if mem_used is not None:
        mem_used = int(round(mem_used / (1024 * 1024)))
    else:
        mem_used = 0

    mem_pct = int(round((mem_used / mem_total) * 100)) if mem_total > 0 else 0

    return {
        'gpu_util': str(max(0, min(100, int(util or 0)))),
        'gpu_temp': f"{int(round(temp_c))}C" if temp_c is not None else 'N/A',
        'gpu_mem_used': str(max(0, int(mem_used or 0))),
        'gpu_mem_total': str(max(0, int(mem_total or 0))),
        'gpu_mem_pct': str(max(0, min(100, int(mem_pct)))),
        'gpu_clock': str(max(0, int(clock_mhz or 0))),
    }


def get_linux_gpu_name_from_slot(slot):
    if not slot:
        return None
    try:
        result = subprocess.run(
            ['lspci', '-s', slot],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip():
            pretty = result.stdout.strip()
            pretty = re.sub(r'^.*?:\s*', '', pretty)
            pretty = re.sub(r'\s*\(rev.*?\)$', '', pretty)
            return clean_field(pretty, 32)
    except Exception:
        pass
    return None


def get_lhm_json():
    if not IS_WINDOWS:
        return None

    for url in LHM_URL_CANDIDATES:
        try:
            with urlopen(url, timeout=1.5) as resp:
                raw = resp.read().decode("utf-8", errors="ignore")
            data = json.loads(raw)
            if isinstance(data, dict):
                return data
        except (URLError, ValueError, OSError):
            continue
    return None


def get_lhm_cpu_temp():
    data = get_lhm_json()
    if not data:
        return None

    best = None
    fallback = None

    for node, parents in iter_lhm_nodes(data):
        lower = lhm_node_text(node, parents)
        value = extract_first_number(node.get("Value"))
        if value is None:
            continue

        if not any(x in lower for x in ("temperature", "temp", "°c")):
            continue
        if not any(x in lower for x in ("cpu", "intelcpu", "amdcpu", "package", "tdie", "tctl", "ccd", "core #")):
            continue

        if any(x in lower for x in ("cpu package", "package", "tdie", "tctl")):
            best = value if best is None else max(best, value)
        else:
            fallback = value if fallback is None else max(fallback, value)

    chosen = best if best is not None else fallback
    if chosen is None:
        return None
    return f"{chosen:.0f}C"


def get_lhm_gpu_stats():
    data = get_lhm_json()
    if not data:
        return None

    gpus = {}

    for node, parents in iter_lhm_nodes(data):
        lower = lhm_node_text(node, parents)
        value = extract_first_number(node.get("Value"))
        if value is None:
            continue

        if not any(x in lower for x in (
            "gpu", "radeon", "geforce", "rtx", "gtx", "rx ", "amd radeon",
            "nvidia", "intel graphics", "gpud3d", "gpuamd", "gpunvidia", "gpuintel", "/gpu-"
        )):
            continue

        key = None
        for part in parents or []:
            part_l = str(part).lower()
            if any(x in part_l for x in ("gpu", "radeon", "geforce", "rtx", "gtx", "rx ", "amd", "nvidia", "intel")):
                key = str(part)
                break
        if key is None:
            key = "GPU"

        bucket = gpus.setdefault(key, {
            "name": key,
            "util": None,
            "temp": None,
            "mem_used": None,
            "mem_total": None,
            "clock": None,
        })

        if any(x in lower for x in ("temperature", "temp", "°c")):
            bucket["temp"] = value if bucket["temp"] is None else max(bucket["temp"], value)
            continue

        if any(x in lower for x in ("core clock", "gpu clock", "clock")):
            bucket["clock"] = value if bucket["clock"] is None else max(bucket["clock"], value)
            continue

        if any(x in lower for x in ("d3d dedicated memory used", "gpu memory used", "memory used", "dedicated memory used")):
            bucket["mem_used"] = value if bucket["mem_used"] is None else max(bucket["mem_used"], value)
            continue

        if any(x in lower for x in ("d3d dedicated memory total", "gpu memory total", "memory total", "dedicated memory total")):
            bucket["mem_total"] = value if bucket["mem_total"] is None else max(bucket["mem_total"], value)
            continue

        if any(x in lower for x in ("load", "core load", "gpu core load", "d3d 3d", "3d", "gpu d3d")):
            if not any(x in lower for x in ("memory controller", "video engine", "bus interface")):
                bucket["util"] = value if bucket["util"] is None else max(bucket["util"], value)
            continue

    if not gpus:
        return None

    best = max(
        gpus.values(),
        key=lambda g: (
            float(g["util"] or 0),
            float(g["mem_used"] or 0),
            float(g["clock"] or 0),
            float(g["temp"] or 0),
        )
    )

    mem_used_i = int(round(best["mem_used"] or 0))
    mem_total_i = int(round(best["mem_total"] or 0))
    mem_pct = int(round((mem_used_i / mem_total_i) * 100)) if mem_total_i > 0 else 0

    return {
        "gpu_util": str(max(0, min(100, int(round(best["util"] or 0))))),
        "gpu_temp": f"{int(round(best['temp']))}C" if best["temp"] is not None else "N/A",
        "gpu_mem_used": str(mem_used_i),
        "gpu_mem_total": str(mem_total_i),
        "gpu_mem_pct": str(max(0, min(100, mem_pct))),
        "gpu_clock": str(int(round(best["clock"] or 0))),
        "gpu_name": clean_field(best["name"], 32),
    }


def find_nvidia_smi():
    candidates = []
    if IS_WINDOWS:
        candidates.extend([
            r"C:\Program Files\NVIDIA Corporation\NVSMI\nvidia-smi.exe",
            r"C:\Windows\System32\nvidia-smi.exe",
        ])
    else:
        candidates.extend([
            "/usr/bin/nvidia-smi",
            "/bin/nvidia-smi",
            "nvidia-smi",
        ])

    for path in candidates:
        if path == "nvidia-smi":
            return path
        if Path(path).exists():
            return path
    return "nvidia-smi"


NVIDIA_SMI = find_nvidia_smi()


def find_arduino_port(preferred_port=PREFERRED_PORT):
    if preferred_port:
        return preferred_port

    ports = list(list_ports.comports())

    preferred_matches = []
    fallback_matches = []

    for port in ports:
        desc = (port.description or "").lower()
        manufacturer = (port.manufacturer or "").lower() if port.manufacturer else ""
        hwid = (port.hwid or "").lower()

        text = f"{desc} {manufacturer} {hwid}"

        if any(x in text for x in ["arduino", "ch340", "wch", "usb serial", "cp210", "ttyacm", "ttyusb"]):
            preferred_matches.append(port.device)
        else:
            fallback_matches.append(port.device)

    if preferred_matches:
        return sorted(preferred_matches)[0]
    if len(fallback_matches) == 1:
        return fallback_matches[0]
    return None


def wait_for_arduino(delay=RETRY_DELAY):
    while True:
        port = find_arduino_port()
        if port:
            print(f"Arduino found on {port}")
            return port
        print(f"No Arduino found. Retrying in {delay} seconds...")
        time.sleep(delay)


def connect_arduino():
    while True:
        port = wait_for_arduino()
        try:
            ser = serial.Serial(port, BAUD, timeout=1, write_timeout=2)
            time.sleep(SERIAL_SETTLE_DELAY)
            try:
                ser.reset_input_buffer()
                ser.reset_output_buffer()
            except Exception:
                pass
            print(f"Connected to Arduino on {port}")
            return ser
        except (SerialException, OSError) as e:
            print(f"Failed to open {port}: {e}")
            time.sleep(RETRY_DELAY)


def get_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "No IP"


def get_temp_linux():
    thermal_path = "/sys/class/thermal/thermal_zone0/temp"
    try:
        with open(thermal_path, "r") as f:
            temp_c = int(f.read().strip()) / 1000.0
        return f"{temp_c:.1f}C"
    except Exception:
        pass

    try:
        temps = psutil.sensors_temperatures()
        for _, entries in temps.items():
            for entry in entries:
                label = (entry.label or "").lower()
                if "package" in label or "core" in label or not label:
                    if entry.current is not None:
                        return f"{entry.current:.1f}C"
    except Exception:
        pass

    return "N/A"


def get_temp_windows():
    lhm_temp = get_lhm_cpu_temp()
    if lhm_temp:
        return lhm_temp

    try:
        import wmi  # type: ignore
        namespaces = [r"root\OpenHardwareMonitor", r"root\LibreHardwareMonitor"]
        for namespace in namespaces:
            try:
                w = wmi.WMI(namespace=namespace)
                temps = []
                for sensor in w.Sensor():
                    sensor_type = str(getattr(sensor, "SensorType", ""))
                    if sensor_type != "Temperature":
                        continue

                    name = str(getattr(sensor, "Name", "")).lower()
                    identifier = str(getattr(sensor, "Identifier", "")).lower()
                    if (
                        "cpu package" in name
                        or "cpu core" in name
                        or "/intelcpu/" in identifier
                        or "/amdcpu/" in identifier
                    ):
                        value = getattr(sensor, "Value", None)
                        if value is not None:
                            temps.append(float(value))

                if temps:
                    return f"{max(temps):.0f}C"
            except Exception:
                continue
    except Exception:
        pass

    try:
        import wmi  # type: ignore
        w = wmi.WMI(namespace=r"root\wmi")
        for sensor in w.MSAcpi_ThermalZoneTemperature():
            temp_c = (sensor.CurrentTemperature / 10.0) - 273.15
            return f"{temp_c:.0f}C"
    except Exception:
        pass

    return "N/A"


def get_temp():
    return get_temp_windows() if IS_WINDOWS else get_temp_linux()


def get_uptime():
    uptime_seconds = int(time.time() - psutil.boot_time())
    h = uptime_seconds // 3600
    m = (uptime_seconds % 3600) // 60
    return f"{h}h {m}m"


def format_speed(bytes_per_sec):
    if bytes_per_sec >= 1024 * 1024:
        return f"{bytes_per_sec / (1024 * 1024):.1f} MB/s"
    if bytes_per_sec >= 1024:
        return f"{bytes_per_sec / 1024:.0f} KB/s"
    return f"{bytes_per_sec:.0f} B/s"


def format_total_bytes(num_bytes):
    if num_bytes >= 1024 ** 4:
        return f"{num_bytes / (1024 ** 4):.2f} TB"
    if num_bytes >= 1024 ** 3:
        return f"{num_bytes / (1024 ** 3):.2f} GB"
    if num_bytes >= 1024 ** 2:
        return f"{num_bytes / (1024 ** 2):.1f} MB"
    if num_bytes >= 1024:
        return f"{num_bytes / 1024:.0f} KB"
    return f"{num_bytes:.0f} B"


def get_cpu_freq():
    try:
        freq = psutil.cpu_freq()
        if not freq or not freq.current:
            return "N/A"
        if freq.current >= 1000:
            return f"{freq.current / 1000:.2f} GHz"
        return f"{freq.current:.0f} MHz"
    except Exception:
        return "N/A"


def _run_powershell_json(script, timeout=5):
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if result.returncode != 0:
            return None
        output = result.stdout.strip()
        if not output:
            return None
        return json.loads(output)
    except Exception:
        return None


def _group_gpu_instance(instance_name: str) -> str:
    if not instance_name:
        return "unknown"
    match = re.search(r"(luid_0x[0-9A-Fa-f]+_0x[0-9A-Fa-f]+_phys_\d+)", instance_name)
    if match:
        return match.group(1)
    return instance_name.split("_engtype_")[0]


@lru_cache(maxsize=1)
def get_windows_dxdiag_memory_info_mb():
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".txt") as f:
            tmp = f.name

        result = subprocess.run(["dxdiag", "/whql:off", "/t", tmp], capture_output=True, text=True, timeout=15)
        if result.returncode != 0 or not os.path.exists(tmp):
            return {"display": 0, "dedicated": 0, "shared": 0, "total": 0}

        with open(tmp, "r", encoding="utf-8", errors="ignore") as f:
            dxdiag_text = f.read()

        def collect(pattern):
            values = []
            for match in re.finditer(pattern, dxdiag_text, re.IGNORECASE):
                try:
                    mb = int(match.group(1).replace(',', ''))
                    if mb >= 0:
                        values.append(mb)
                except Exception:
                    pass
            return max(values) if values else 0

        return {
            "display": collect(r"Display Memory \(VRAM\):\s*([\d,]+)\s*MB"),
            "dedicated": collect(r"Dedicated Memory:\s*([\d,]+)\s*MB"),
            "shared": collect(r"Shared Memory:\s*([\d,]+)\s*MB"),
            "total": collect(r"Approx\. Total Memory:\s*([\d,]+)\s*MB"),
        }
    except Exception:
        return {"display": 0, "dedicated": 0, "shared": 0, "total": 0}
    finally:
        if tmp and os.path.exists(tmp):
            try:
                os.remove(tmp)
            except Exception:
                pass



def get_windows_perf_gpu_stats(prefer_shared_memory=False):
    gpu_percent = 0
    dxdiag_info = get_windows_dxdiag_memory_info_mb()

    display_mb = int(dxdiag_info.get('display') or 0)
    dedicated_mb = int(dxdiag_info.get('dedicated') or 0)
    shared_total_mb = int(dxdiag_info.get('shared') or 0)

    adapter_ram_mb = 0
    try:
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                "Get-CimInstance Win32_VideoController | "
                "Select-Object Name,AdapterRAM | ConvertTo-Json -Compress"
            ],
            capture_output=True,
            text=True,
            timeout=6,
        )
        if result.returncode == 0 and result.stdout.strip():
            data = json.loads(result.stdout.strip())
            entries = data if isinstance(data, list) else [data]

            best_mb = 0
            for entry in entries:
                name = str(entry.get("Name", "") or "").lower()
                if any(x in name for x in ("microsoft basic display", "remote display", "parsec", "virtual")):
                    continue
                try:
                    ram_bytes = int(entry.get("AdapterRAM") or 0)
                except Exception:
                    ram_bytes = 0
                ram_mb = int(round(ram_bytes / (1024 * 1024))) if ram_bytes > 0 else 0
                if ram_mb > best_mb:
                    best_mb = ram_mb

            adapter_ram_mb = best_mb
    except Exception:
        pass

    # Prefer sane dedicated VRAM values for discrete GPUs.
    sane_candidates = [mb for mb in (dedicated_mb, display_mb, adapter_ram_mb) if 256 <= mb <= 12288]
    if sane_candidates:
        vram_total_mb = max(sane_candidates)
    else:
        positive_candidates = [mb for mb in (dedicated_mb, display_mb, adapter_ram_mb) if mb > 0]
        vram_total_mb = max(positive_candidates) if positive_candidates else 0

    usage_script = r"""
    $data = Get-Counter '\GPU Engine(*)\Utilization Percentage'
    $data.CounterSamples |
      Where-Object { $_.InstanceName -match 'engtype_' } |
      Select-Object InstanceName, CookedValue |
      ConvertTo-Json -Compress
    """
    usage_samples = _run_powershell_json(usage_script, timeout=6)

    if usage_samples:
        if isinstance(usage_samples, dict):
            usage_samples = [usage_samples]
        grouped_usage = {}
        for sample in usage_samples:
            try:
                name = sample.get("InstanceName", "")
                value = float(sample.get("CookedValue", 0.0) or 0.0)
                if value != value or value in (float("inf"), float("-inf")):
                    continue
                value = max(0.0, min(100.0, value))
                key = _group_gpu_instance(name)
                grouped_usage[key] = max(grouped_usage.get(key, 0.0), value)
            except Exception:
                continue
        if grouped_usage:
            gpu_percent = int(round(max(grouped_usage.values())))

    memory_scripts = {
        'dedicated': r"""
    $data = Get-Counter '\GPU Adapter Memory(*)\Dedicated Usage'
    $data.CounterSamples |
      Select-Object InstanceName, CookedValue |
      ConvertTo-Json -Compress
    """,
        'shared': r"""
    $data = Get-Counter '\GPU Adapter Memory(*)\Shared Usage'
    $data.CounterSamples |
      Select-Object InstanceName, CookedValue |
      ConvertTo-Json -Compress
    """,
    }

    memory_totals = {}
    for mem_kind, memory_script in memory_scripts.items():
        mem_samples = _run_powershell_json(memory_script, timeout=6)
        if not mem_samples:
            continue
        if isinstance(mem_samples, dict):
            mem_samples = [mem_samples]

        grouped_mem = {}
        for sample in mem_samples:
            try:
                name = sample.get("InstanceName", "")
                value_bytes = float(sample.get("CookedValue", 0.0) or 0.0)
                if value_bytes != value_bytes or value_bytes in (float("inf"), float("-inf")):
                    continue
                key = _group_gpu_instance(name)
                grouped_mem[key] = max(grouped_mem.get(key, 0.0), value_bytes)
            except Exception:
                continue

        if grouped_mem:
            memory_totals[mem_kind] = int(round(max(grouped_mem.values()) / (1024 * 1024)))

    dedicated_used_mb = max(0, int(memory_totals.get('dedicated', 0)))
    shared_used_mb = max(0, int(memory_totals.get('shared', 0)))
    gpu_percent = max(0, min(100, gpu_percent))

    mem_used_mb = dedicated_used_mb
    mem_total_mb = vram_total_mb

    # Only use shared totals for iGPU/shared-memory cases.
    if prefer_shared_memory and shared_total_mb > 0:
        if mem_total_mb <= 0:
            mem_total_mb = shared_total_mb
        if shared_used_mb >= dedicated_used_mb:
            mem_used_mb = shared_used_mb

    if mem_total_mb > 12288 and sane_candidates:
        mem_total_mb = max(sane_candidates)
    if mem_total_mb > 0 and mem_used_mb > mem_total_mb:
        mem_used_mb = min(mem_used_mb, mem_total_mb)

    mem_pct = int(round((mem_used_mb / mem_total_mb) * 100)) if mem_total_mb > 0 else 0
    mem_pct = max(0, min(100, mem_pct))

    return {
        "gpu_util": str(gpu_percent),
        "gpu_temp": "N/A",
        "gpu_mem_used": str(max(0, mem_used_mb)),
        "gpu_mem_total": str(max(0, mem_total_mb)),
        "gpu_mem_pct": str(mem_pct),
        "gpu_clock": "0",
    }


@lru_cache(maxsize=1)
def get_gpu_name():
    return get_gpu_name_windows() if IS_WINDOWS else get_gpu_name_linux()


@lru_cache(maxsize=1)
def get_gpu_name_windows():
    try:
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name | ConvertTo-Json -Compress",
            ],
            capture_output=True,
            text=True,
            timeout=6,
        )
        if result.returncode == 0 and result.stdout.strip():
            data = json.loads(result.stdout.strip())
            names = data if isinstance(data, list) else [data]
            cleaned = [clean_field(name, 32) for name in names if name and str(name).strip()]
            preferred = [n for n in cleaned if not any(x in n.lower() for x in ("microsoft basic display", "remote display", "parsec", "virtual"))]
            if preferred:
                discrete = [n for n in preferred if any(x in n.lower() for x in ("nvidia", "geforce", "amd", "radeon", "rx", "rtx", "gtx"))]
                return discrete[0] if discrete else preferred[0]
    except Exception:
        pass

    lhm_stats = get_lhm_gpu_stats()
    if lhm_stats and lhm_stats.get("gpu_name"):
        return clean_field(lhm_stats["gpu_name"], 32)

    return "Unknown GPU"


@lru_cache(maxsize=1)
def get_gpu_name_linux():
    card = get_preferred_linux_gpu_card()
    if card:
        from_slot = get_linux_gpu_name_from_slot(card.get('slot'))
        if from_slot:
            return from_slot

    try:
        result = subprocess.run(["bash", "-lc", "lspci | grep -iE 'vga|3d|display'"], capture_output=True, text=True, timeout=3)
        if result.returncode == 0 and result.stdout.strip():
            lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
            preferred = []
            for line in lines:
                lower = line.lower()
                if any(x in lower for x in ("nvidia", "amd", "radeon", "intel")):
                    pretty = re.sub(r"^.*?:\s*", "", line)
                    pretty = re.sub(r"\s*\(rev.*?\)$", "", pretty)
                    preferred.append(clean_field(pretty, 32))
            if preferred:
                discrete = [n for n in preferred if any(x in n.lower() for x in ("nvidia", "geforce", "amd", "radeon", "rx", "rtx", "gtx"))]
                return discrete[0] if discrete else preferred[0]
    except Exception:
        pass
    return "Unknown GPU"



def get_gpu_stats():
    gpu_name = get_gpu_name()
    gpu_name_l = gpu_name.lower()
    prefer_shared_windows_memory = "intel" in gpu_name_l and "arc" not in gpu_name_l

    try:
        result = subprocess.run(
            [
                NVIDIA_SMI,
                "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total,clocks.current.graphics",
                "--format=csv,noheader,nounits"
            ],
            capture_output=True,
            text=True,
            timeout=2,
            check=True,
        )
        line = result.stdout.strip().splitlines()[0]
        gpu_name_raw, gpu_util, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_clock = [x.strip() for x in line.split(",", 5)]
        mem_used_i = int(float(gpu_mem_used))
        mem_total_i = int(float(gpu_mem_total))
        mem_pct = int((mem_used_i / mem_total_i) * 100) if mem_total_i > 0 else 0
        mem_pct = max(0, min(100, mem_pct))
        return (
            str(max(0, min(100, int(float(gpu_util))))),
            f"{gpu_temp}C",
            str(mem_used_i),
            str(mem_total_i),
            str(mem_pct),
            gpu_clock,
            clean_field(gpu_name_raw or gpu_name, 32),
        )
    except Exception:
        pass

    if IS_WINDOWS:
        lhm_stats = get_lhm_gpu_stats() or {}
        win_stats = get_windows_perf_gpu_stats(prefer_shared_memory=prefer_shared_windows_memory)

        win_total_i = int(float(win_stats.get('gpu_mem_total', '0') or 0))
        win_used_i = int(float(win_stats.get('gpu_mem_used', '0') or 0))
        win_util_i = int(float(win_stats.get('gpu_util', '0') or 0))

        if lhm_stats:
            gpu_util = lhm_stats.get('gpu_util', '0')
            gpu_temp = lhm_stats.get('gpu_temp', 'N/A')
            gpu_mem_used = lhm_stats.get('gpu_mem_used', '0')
            gpu_mem_total = lhm_stats.get('gpu_mem_total', '0')
            gpu_clock = lhm_stats.get('gpu_clock', '0')
            gpu_name = clean_field(lhm_stats.get('gpu_name', gpu_name), 32)

            total_i = int(float(gpu_mem_total or 0))
            used_i = int(float(gpu_mem_used or 0))

            # If LHM gives nonsense totals, prefer Windows AdapterRAM/DXDiag totals instead.
            if (
                total_i <= 0
                or total_i > 12288
                or (win_total_i > 0 and total_i > win_total_i * 1.5)
            ):
                total_i = win_total_i

            if used_i < 0:
                used_i = 0
            if total_i > 0 and used_i > total_i:
                if win_used_i > 0 and win_used_i <= total_i:
                    used_i = win_used_i
                else:
                    used_i = total_i

            if int(float(gpu_util or 0)) <= 0 and win_util_i > 0:
                gpu_util = str(win_util_i)

            gpu_mem_pct = str(int(round((used_i / total_i) * 100)) if total_i > 0 else 0)

            return (
                str(max(0, min(100, int(float(gpu_util or 0))))),
                gpu_temp,
                str(max(0, used_i)),
                str(max(0, total_i)),
                str(max(0, min(100, int(float(gpu_mem_pct or 0))))),
                str(max(0, int(float(gpu_clock or 0)))),
                gpu_name,
            )

        return (
            str(max(0, min(100, int(float(win_stats.get('gpu_util', '0') or 0))))),
            'N/A',
            win_stats.get('gpu_mem_used', '0'),
            win_stats.get('gpu_mem_total', '0'),
            str(max(0, min(100, int(float(win_stats.get('gpu_mem_pct', '0') or 0))))),
            '0',
            gpu_name,
        )

    linux_amd_stats = get_linux_amd_gpu_stats()
    if linux_amd_stats:
        return (
            linux_amd_stats.get("gpu_util", "0"),
            linux_amd_stats.get("gpu_temp", "N/A"),
            linux_amd_stats.get("gpu_mem_used", "0"),
            linux_amd_stats.get("gpu_mem_total", "0"),
            linux_amd_stats.get("gpu_mem_pct", "0"),
            linux_amd_stats.get("gpu_clock", "0"),
            gpu_name,
        )

    linux_intel_stats = get_linux_intel_gpu_stats()
    if linux_intel_stats:
        return (
            linux_intel_stats.get("gpu_util", "0"),
            linux_intel_stats.get("gpu_temp", "N/A"),
            linux_intel_stats.get("gpu_mem_used", "0"),
            linux_intel_stats.get("gpu_mem_total", "0"),
            linux_intel_stats.get("gpu_mem_pct", "0"),
            linux_intel_stats.get("gpu_clock", "0"),
            gpu_name,
        )

    lhm_stats = get_lhm_gpu_stats()
    if lhm_stats:
        return (
            lhm_stats.get("gpu_util", "0"),
            lhm_stats.get("gpu_temp", "N/A"),
            lhm_stats.get("gpu_mem_used", "0"),
            lhm_stats.get("gpu_mem_total", "0"),
            lhm_stats.get("gpu_mem_pct", "0"),
            lhm_stats.get("gpu_clock", "0"),
            clean_field(lhm_stats.get("gpu_name", gpu_name), 32),
        )

    return "0", "N/A", "0", "0", "0", "0", gpu_name


def prime_process_cpu():
    for proc in psutil.process_iter(["name"]):
        try:
            proc.cpu_percent(None)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass


def get_top_processes(limit=6):
    procs = []
    for proc in psutil.process_iter(["name", "memory_percent"]):
        try:
            cpu = proc.cpu_percent(None)
            ram = proc.info.get("memory_percent", 0.0)
            name = clean_field(proc.info.get("name", "--"), 14)
            if name and name != "--":
                procs.append((cpu, ram, name))
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue

    procs.sort(key=lambda x: x[0], reverse=True)
    rows = []
    seen = set()
    for cpu, ram, name in procs:
        if name in seen:
            continue
        seen.add(name)
        rows.append((name, f"{cpu:.0f}%", f"{ram:.1f}%"))
        if len(rows) >= limit:
            break

    while len(rows) < limit:
        rows.append(("--", "--", "--"))
    return rows


def get_disk_candidates_linux():
    candidates = []
    for part in psutil.disk_partitions(all=True):
        device = part.device
        mount = part.mountpoint
        fstype = part.fstype.lower()
        if not device.startswith("/dev/"):
            continue
        if fstype in ("squashfs", "tmpfs", "devtmpfs", "overlay", "proc", "sysfs", "cgroup2fs"):
            continue
        candidates.append((device, mount))

    seen = set()
    unique = []
    for device, mount in candidates:
        if device in seen:
            continue
        seen.add(device)
        unique.append((device, mount))
    return unique


def get_disk_candidates_windows():
    candidates = []
    for part in psutil.disk_partitions(all=False):
        device = (part.device or "").strip()
        mount = part.mountpoint
        opts = (part.opts or "").lower()
        if not device:
            continue
        if "cdrom" in opts:
            continue
        candidates.append((device, mount))

    seen = set()
    unique = []
    for device, mount in candidates:
        key = device.upper()
        if key in seen:
            continue
        seen.add(key)
        unique.append((device, mount))
    return unique


def get_disk_candidates():
    return get_disk_candidates_windows() if IS_WINDOWS else get_disk_candidates_linux()


def get_disk_info_slots(max_slots=4):
    slots = []
    for device, mount in get_disk_candidates():
        try:
            usage = psutil.disk_usage(mount)
            used_pct = int(usage.percent)
            free_str = format_total_bytes(usage.free)
            total_str = format_total_bytes(usage.total)
            label = clean_field(device.replace("\\", "") if IS_WINDOWS else os.path.basename(device), 8)
            slots.append(f"{label}:{used_pct}% {free_str}F/{total_str}")
        except Exception:
            label = clean_field(device.replace("\\", "") if IS_WINDOWS else os.path.basename(device), 8)
            slots.append(f"{label}:N/A")
        if len(slots) >= max_slots:
            break

    while len(slots) < max_slots:
        slots.append(f"Disk{len(slots)}:N/A")
    return slots[:max_slots]


def _extract_windows_reg_value(raw_text, value_name="ProductName"):
    if not raw_text:
        return None

    for line in raw_text.splitlines():
        line = line.strip()
        if not line or value_name.lower() not in line.lower():
            continue
        if "REG_SZ" in line:
            value = line.split("REG_SZ", 1)[1].strip()
            if value:
                return value
        match = re.search(rf"{re.escape(value_name)}\s+REG_\w+\s+(.+)$", line, re.IGNORECASE)
        if match:
            value = match.group(1).strip()
            if value:
                return value
    return None


def get_os_name():
    try:
        if IS_WINDOWS:
            registry_cmds = (
                ['reg', 'query', r'HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion', '/v', 'ProductName'],
                ['powershell', '-NoProfile', '-Command', r"(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').ProductName"],
            )

            for cmd in registry_cmds:
                try:
                    result = subprocess.run(cmd, capture_output=True, text=True, timeout=4, check=False)
                    raw_text = (result.stdout or '').strip()
                    if result.returncode != 0 or not raw_text:
                        continue

                    value = _extract_windows_reg_value(raw_text, 'ProductName') or raw_text.strip()
                    value = value.splitlines()[-1].strip()
                    if value:
                        return clean_field(value, 32)
                except Exception:
                    pass

            try:
                result = subprocess.run(
                    ['powershell', '-NoProfile', '-Command', '(Get-CimInstance Win32_OperatingSystem).Caption'],
                    capture_output=True, text=True, timeout=4, check=False
                )
                text = (result.stdout or '').strip()
                if result.returncode == 0 and text:
                    return clean_field(text.splitlines()[-1].strip(), 32)
            except Exception:
                pass

            release = platform.release()
            version = platform.version()
            text = f"Windows {release}"
            if ('10' in release) and ('22000' in version or '226' in version):
                text = 'Windows 11'
            return clean_field(text, 32)

        os_release = Path('/etc/os-release')
        if os_release.exists():
            data = {}
            for raw_line in os_release.read_text(encoding='utf-8', errors='ignore').splitlines():
                if '=' not in raw_line:
                    continue
                k, v = raw_line.split('=', 1)
                data[k.strip()] = v.strip().strip('"')

            distro_name = data.get('NAME', '')
            version_id = data.get('VERSION_ID', '')
            if distro_name and version_id:
                return clean_field(f"{distro_name} {version_id}", 32)

            pretty = data.get('PRETTY_NAME', '')
            if pretty:
                pretty = re.sub(r'\s+\([^)]*\)$', '', pretty).strip()
                return clean_field(pretty, 32)

        return clean_field(f"{platform.system()} {platform.release()}", 32)
    except Exception:
        return '--'


def get_hostname():
    try:
        return clean_field(socket.gethostname(), 40)
    except Exception:
        return '--'


def get_disk_percent_slots(max_slots=2):
    slots = []
    for device, mount in get_disk_candidates():
        try:
            usage = psutil.disk_usage(mount)
            used_pct = int(usage.percent)
        except Exception:
            used_pct = 0
        slots.append((clean_field(device.replace('\\', '') if IS_WINDOWS else os.path.basename(device), 8), used_pct))
        if len(slots) >= max_slots:
            break
    while len(slots) < max_slots:
        slots.append((f"DSK{len(slots)}", 0))
    return slots[:max_slots]


def get_root_disk_percent():
    try:
        if IS_WINDOWS:
            system_drive = os.environ.get("SystemDrive", "C:")
            return str(int(psutil.disk_usage(system_drive + "\\").percent))
        return str(int(psutil.disk_usage("/").percent))
    except Exception:
        slots = get_disk_info_slots(1)
        m = re.search(r":(\d+)%", slots[0])
        return m.group(1) if m else "0"


def get_optical_status():
    if IS_WINDOWS:
        try:
            for part in psutil.disk_partitions(all=True):
                opts = (part.opts or "").lower()
                if "cdrom" in opts:
                    try:
                        psutil.disk_usage(part.mountpoint)
                        return "Media"
                    except Exception:
                        return "Closed/Empty"
            return "N/A"
        except Exception:
            return "N/A"

    try:
        if os.path.exists("/dev/sr0"):
            result = subprocess.run(["bash", "-lc", "cat /proc/sys/dev/cdrom/info 2>/dev/null"], capture_output=True, text=True, timeout=1)
            info = result.stdout.lower()
            if "drive name:" in info and "sr0" in info:
                blk = subprocess.run(["bash", "-lc", "blkid /dev/sr0 >/dev/null 2>&1 && echo MEDIA || echo NOMEDIA"], capture_output=True, text=True, timeout=2)
                if "MEDIA" in blk.stdout:
                    return "Media"
                return "Closed/Empty"
        return "N/A"
    except Exception:
        return "N/A"



def get_cached_static_info():
    now = time.time()
    if _static_cache["data"] is None or (now - _static_cache["ts"]) >= STATIC_INFO_CACHE_TTL:
        _static_cache["data"] = {
            "os_name": get_os_name(),
            "host_name": get_hostname(),
        }
        _static_cache["ts"] = now
    return _static_cache["data"]


def get_cached_temp():
    now = time.time()
    if _temp_cache["data"] is None or (now - _temp_cache["ts"]) >= TEMP_CACHE_TTL:
        _temp_cache["data"] = get_temp()
        _temp_cache["ts"] = now
    return _temp_cache["data"]


def get_cached_gpu_stats():
    now = time.time()
    if _gpu_cache["data"] is None or (now - _gpu_cache["ts"]) >= GPU_CACHE_TTL:
        _gpu_cache["data"] = get_gpu_stats()
        _gpu_cache["ts"] = now
    return _gpu_cache["data"]


def get_cached_top_processes(limit=6):
    now = time.time()
    if _proc_cache["data"] is None or (now - _proc_cache["ts"]) >= PROC_CACHE_TTL:
        _proc_cache["data"] = get_top_processes(limit)
        _proc_cache["ts"] = now
    return _proc_cache["data"]


def build_payload(last_net, last_time):
    cpu_total_val = psutil.cpu_percent(interval=CPU_SAMPLE_INTERVAL)
    per_core = psutil.cpu_percent(interval=None, percpu=True)
    while len(per_core) < 4:
        per_core.append(0.0)

    cpu_total = f"{cpu_total_val:.0f}"
    cpu0 = f"{per_core[0]:.0f}"
    cpu1 = f"{per_core[1]:.0f}"
    cpu2 = f"{per_core[2]:.0f}"
    cpu3 = f"{per_core[3]:.0f}"
    ram = f"{psutil.virtual_memory().percent:.0f}"

    disk_percent_slots = get_disk_percent_slots(2)
    disk_pct = str(disk_percent_slots[0][1])
    disk1_pct = str(disk_percent_slots[1][1])

    static_info = get_cached_static_info()
    temp = get_cached_temp()
    os_name = static_info["os_name"]
    host_name = static_info["host_name"]
    ip = clean_field(get_ip(), 18)
    uptime = clean_field(get_uptime(), 18)
    cpu_freq = get_cpu_freq()

    now_net = psutil.net_io_counters()
    now_time = time.time()
    elapsed = now_time - last_time
    if elapsed <= 0:
        elapsed = 1

    down_bps = (now_net.bytes_recv - last_net.bytes_recv) / elapsed
    up_bps = (now_net.bytes_sent - last_net.bytes_sent) / elapsed
    down = clean_field(format_speed(down_bps), 18)
    up = clean_field(format_speed(up_bps), 18)
    down_total = clean_field(format_total_bytes(now_net.bytes_recv), 18)
    up_total = clean_field(format_total_bytes(now_net.bytes_sent), 18)

    gpu, gpu_temp, gpu_mem_used, gpu_mem_total, gpu_mem_pct, gpu_clock, gpu_name = get_cached_gpu_stats()
    procs = get_cached_top_processes(6)
    disk0, disk1, disk2, disk3 = [clean_field(x, 26) for x in get_disk_info_slots(4)]
    dvd_state = clean_field(get_optical_status(), 18)

    payload = (
        f"{cpu_total}|{cpu0}|{cpu1}|{cpu2}|{cpu3}|{ram}|{disk_pct}|{disk1_pct}|"
        f"{clean_field(temp, 12)}|{os_name}|{host_name}|{ip}|{uptime}|"
        f"{down}|{up}|{down_total}|{up_total}|{clean_field(cpu_freq, 18)}|"
        f"{clean_field(gpu, 4)}|{clean_field(gpu_temp, 12)}|{clean_field(gpu_mem_used, 8)}|{clean_field(gpu_mem_total, 8)}|"
        f"{clean_field(gpu_mem_pct, 4)}|{clean_field(gpu_clock, 8)}|{clean_field(gpu_name, 32)}|"
        f"{procs[0][0]}|{procs[1][0]}|{procs[2][0]}|{procs[3][0]}|{procs[4][0]}|{procs[5][0]}|"
        f"{procs[0][1]}|{procs[0][2]}|{procs[1][1]}|{procs[1][2]}|{procs[2][1]}|{procs[2][2]}|"
        f"{procs[3][1]}|{procs[3][2]}|{procs[4][1]}|{procs[4][2]}|{procs[5][1]}|{procs[5][2]}|"
        f"{disk0}|{disk1}|{disk2}|{disk3}|{dvd_state}\n"
    )
    return payload, now_net, now_time


def main():
    print(f"Running on: {platform.system()} {platform.release()}")
    print(f"Using nvidia-smi path: {NVIDIA_SMI}")
    if IS_WINDOWS:
        print("LibreHardwareMonitor JSON fallback enabled (localhost:8085).")
    elif IS_LINUX:
        print("Linux GPU fallback enabled: NVIDIA via nvidia-smi, AMD via /sys/class/drm, Intel via intel_gpu_top/sysfs.")

    psutil.cpu_percent(interval=None)
    psutil.cpu_percent(interval=None, percpu=True)
    prime_process_cpu()

    last_net = psutil.net_io_counters()
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
        except (SerialException, OSError) as e:
            print(f"Arduino disconnected or serial write failed: {e}")
            try:
                ser.close()
            except Exception:
                pass
            print("Waiting for Arduino to reconnect...")
            time.sleep(RETRY_DELAY)
            ser = connect_arduino()
        except KeyboardInterrupt:
            print("Stopping monitor.")
            try:
                ser.close()
            except Exception:
                pass
            break
        except Exception as e:
            print(f"Unexpected error: {e}")
            time.sleep(RETRY_DELAY)


if __name__ == "__main__":
    main()
