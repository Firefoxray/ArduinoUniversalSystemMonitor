"""Best-effort Linux hardware telemetry for the Qt preview.

No sensors are synthesized: missing data is represented by None. One expensive
GPU/process poll is cached by the caller, never run on every frame.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
import time
import psutil


@dataclass(frozen=True)
class GPU:
    name: str = "Unavailable"
    load: float | None = None
    temperature: float | None = None
    vram_used: int | None = None
    vram_total: int | None = None
    source: str = "No supported GPU telemetry"


@dataclass(frozen=True)
class ProcessRow:
    name: str
    cpu: float
    memory: float


def _number(path: Path) -> int | None:
    try:
        return int(path.read_text(encoding="ascii").strip())
    except (OSError, ValueError):
        return None


def _amd_cards(drm_root: Path = Path("/sys/class/drm")):
    """Return actual AMD render cards, excluding connectors and non-GPU entries."""
    cards = []
    for card in drm_root.glob("card[0-9]*"):
        if not card.name[4:].isdigit():
            continue
        device = card / "device"
        try:
            vendor = (device / "vendor").read_text().strip().lower()
        except OSError:
            continue
        if vendor == "0x1002":
            cards.append(card)
    return cards


def gpu_amd(drm_root: Path = Path("/sys/class/drm")) -> GPU | None:
    cards = _amd_cards(drm_root)
    if not cards:
        return None
    # If multiple GPUs exist, favor the boot VGA or the larger VRAM board.
    def score(card: Path) -> tuple[int, int]:
        return (_number(card / "device/boot_vga") or 0, _number(card / "device/mem_info_vram_total") or 0)
    card = max(cards, key=score)
    device = card / "device"
    busy = _number(device / "gpu_busy_percent")
    used = _number(device / "mem_info_vram_used")
    total = _number(device / "mem_info_vram_total")
    temp = None
    for path in sorted(device.glob("hwmon/hwmon*/temp1_input")):
        milli = _number(path)
        if milli is not None and 0 <= milli <= 150000:
            temp = milli / 1000
            break
    name = "AMD Radeon GPU"
    try:
        text = (device / "product_name").read_text().strip()
        if text:
            name = text
    except OSError:
        pass
    return GPU(
        name=name,
        load=float(busy) if busy is not None and 0 <= busy <= 100 else None,
        temperature=temp,
        vram_used=used,
        vram_total=total,
        source=f"amdgpu sysfs · {card.name}",
    )


def gpu_nvidia() -> GPU | None:
    if not shutil.which("nvidia-smi"):
        return None
    try:
        run = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=1.5, check=False,
        )
        if run.returncode != 0 or not run.stdout.strip():
            return None
        name, usage, temperature, mem_used, mem_total = [
            part.strip() for part in run.stdout.splitlines()[0].split(",", 4)
        ]
        def number(value: str) -> float | None:
            try:
                return float(value)
            except ValueError:
                return None
        load, temp = number(usage), number(temperature)
        used, total = number(mem_used), number(mem_total)
        return GPU(
            name=name, load=load, temperature=temp,
            vram_used=int(used * 1048576) if used is not None else None,
            vram_total=int(total * 1048576) if total is not None else None,
            source="nvidia-smi",
        )
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None


def read_gpu() -> GPU:
    return gpu_amd() or gpu_nvidia() or GPU()


def top_processes(limit: int = 6) -> tuple[ProcessRow, ...]:
    rows: list[ProcessRow] = []
    for process in psutil.process_iter(["name", "memory_percent"]):
        try:
            # Nonblocking; first poll initializes per-process CPU delta.
            cpu = max(0.0, process.cpu_percent(interval=None))
            ram = float(process.info.get("memory_percent") or 0)
            if cpu or ram:
                rows.append(ProcessRow(str(process.info.get("name") or process.pid), cpu, ram))
        except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
            continue
    rows.sort(key=lambda row: (row.cpu, row.memory), reverse=True)
    return tuple(rows[:limit])


def uptime_seconds() -> int:
    return max(0, int(time.time() - psutil.boot_time()))
