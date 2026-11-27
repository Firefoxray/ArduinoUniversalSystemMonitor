"""Explicit USB or assigned Wi-Fi test sender for v13 (no service or flashing)."""
from __future__ import annotations

import argparse
import ipaddress
import socket
import sys
import time
import threading
import psutil
import serial
from serial.tools import list_ports

from .backend import ConfigError, ConfigStore, TelemetrySampler, disk_assignments, disk_percent, drives
from .protocol import build_arduino_payload, clean_field
from .wifi import PairingRejected, pair_socket, verified_board, assignment


def packet(snapshot, config=None, local_ip=None):
    def integer(value):
        return "0" if value is None else str(round(value))
    def temperature(value):
        return "N/A" if value is None else f"{value:.1f}C"
    def rate(value):
        if value >= 1024**2:
            return f"{value / 1024**2:.1f} MB/s"
        if value >= 1024:
            return f"{value / 1024:.0f} KB/s"
        return f"{value:.0f} B/s"
    def total(value):
        return f"{value / 1024**3:.1f} GB"
    def host_ip():
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "--"

    gpu = snapshot.gpu
    freq = psutil.cpu_freq()
    net = psutil.net_io_counters()
    processes = [(p.name, f"{p.cpu:.0f}%", f"{p.memory:.1f}%") for p in snapshot.top_processes[:6]]
    processes += [("--", "--", "--")] * (6 - len(processes))
    cores = list(snapshot.per_cpu[:16])
    cores += [0] * (16 - len(cores))
    mounts = drives()
    disk0, disk1 = disk_assignments(config or {}, mounts)
    # Resolve against ALL mounted volumes, not just the eight display rows.
    # Preserve the legacy numeric format: unavailable/unassigned is 0 on old TFT firmware.
    lines = [clean_field(f"{mount} {percent:.0f}%", 30) for mount, _, _, _, percent in mounts[:8]]
    lines += ["--"] * (8 - len(lines))
    data = {
        "cpu_total": round(snapshot.cpu), "per_core": [round(x) for x in cores],
        "ram_pct": round(snapshot.ram), "disk0_pct": integer(disk_percent(disk0, mounts)),
        "disk1_pct": integer(disk_percent(disk1, mounts)), "cpu_temp": temperature(snapshot.temperature),
        "os_name": clean_field("Windows" if sys.platform == "win32" else snapshot.os_name, 24),
        "host_name": clean_field(snapshot.hostname, 24), "ip": clean_field(local_ip or host_ip(), 18),
        "uptime": f"{snapshot.uptime_seconds // 3600}h {(snapshot.uptime_seconds // 60) % 60}m",
        "down_rate": rate(snapshot.down_bytes_per_sec), "up_rate": rate(snapshot.up_bytes_per_sec),
        "down_total": total(net.bytes_recv) if net else "--", "up_total": total(net.bytes_sent) if net else "--",
        "cpu_freq": f"{freq.current:.0f} MHz" if freq else "--",
        "ram_usage_text": f"{snapshot.used_gb:.1f}/{snapshot.total_gb:.1f}GB",
        "gpu_util": integer(gpu.load), "gpu_temp": temperature(gpu.temperature),
        "gpu_mem_used": integer(gpu.vram_used / 1024**2 if gpu.vram_used is not None else None),
        "gpu_mem_total": integer(gpu.vram_total / 1024**2 if gpu.vram_total is not None else None),
        "gpu_mem_pct": integer(100 * gpu.vram_used / gpu.vram_total if gpu.vram_used is not None and gpu.vram_total else None),
        "gpu_clock": "0", "gpu_name": clean_field(gpu.name, 32),
        "procs": processes, "storage_lines": lines, "battery_pct": "--",
        "battery_state": "--", "battery_mode": "--", "extra_batteries": [],
    }
    return build_arduino_payload(data).encode("ascii", errors="replace")


def arduino_usb_ports():
    """Only ports that meet the preview sender's explicit vendor check."""
    return [p.device for p in list_ports.comports() if p.vid == 0x2341]


def parse_wifi_endpoint(value):
    try:
        host, port = value.rsplit(":", 1)
        address = str(ipaddress.IPv4Address(host))
        number = int(port)
        if not 1 <= number <= 65535:
            raise ValueError()
        return address, number
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Use the discovered IPv4 address:TCP port") from exc


def run_wifi(endpoint, discovery_port, sampler, config, stop, interval):
    address, port = endpoint
    while not stop.is_set():
        try:
            print(f"Verifying Wi-Fi board {address}:{port}…", flush=True)
            board = verified_board(address, port, discovery_port)
            if stop.is_set():
                return 0
            with socket.create_connection(endpoint, timeout=3.0) as sock:
                # Recheck against the actual TCP source before sending identity.
                if assignment(board, local_ip=sock.getsockname()[0]) != "This PC":
                    raise PairingRejected("TCP route does not match the advertised assignment")
                ip = pair_socket(sock)
                print(f"Wi-Fi pairing accepted: {board.name} ({address}:{port})", flush=True)
                sampler.sample()
                if stop.wait(interval):
                    return 0
                frames = 0
                while not stop.is_set():
                    sock.sendall(packet(sampler.sample(), config, local_ip=ip))
                    frames += 1
                    if frames == 1 or frames % 10 == 0:
                        print(f"Wi-Fi frames written: {frames} (display acceptance not verified)", flush=True)
                    stop.wait(interval)
        except PairingRejected as exc:
            print(f"Wi-Fi pairing blocked: {exc}", file=sys.stderr, flush=True)
            return 1
        except OSError as exc:
            print(f"Wi-Fi retry: {exc}", flush=True)
            stop.wait(3)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    transport = parser.add_mutually_exclusive_group(required=True)
    transport.add_argument("--serial", help="Explicit Arduino USB port (e.g. COM3)")
    transport.add_argument("--wifi", type=parse_wifi_endpoint, help="Explicit discovered and assigned IPv4:port")
    parser.add_argument("--discovery-port", type=int, default=5001)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--managed", action="store_true", help="Stop when the GUI closes stdin or sends stop")
    args = parser.parse_args(argv)
    if not 0.5 <= args.interval <= 10:
        parser.error("Interval must be between 0.5 and 10 seconds.")
    if not 1 <= args.discovery_port <= 65535:
        parser.error("Discovery port out of range")
    if args.serial and not any(port.casefold() == args.serial.casefold() for port in arduino_usb_ports()):
        parser.error("The selected port was not detected with Arduino USB vendor ID 2341.")
    store = ConfigStore()
    try:
        config = store.load()
    except ConfigError as exc:
        parser.error(str(exc))
    disk0, disk1 = disk_assignments(config, [])
    print(f"Disk0: {disk0 or 'unassigned'} | Disk1: {disk1 or 'unassigned'}", flush=True)
    stop = threading.Event()
    if args.managed:
        def watch_parent():
            try:
                for line in sys.stdin:
                    if line.strip().lower() == "stop":
                        break
            finally:
                stop.set()
        threading.Thread(target=watch_parent, daemon=True).start()
    sampler = TelemetrySampler()
    try:
        if args.wifi:
            return run_wifi(args.wifi, args.discovery_port, sampler, config, stop, args.interval)
        with serial.Serial(args.serial, 115200, timeout=0.5, write_timeout=2) as board:
            print(f"Opened {args.serial} at 115200; Ctrl+C to stop.", flush=True)
            if stop.wait(2):
                return 0
            sampler.sample()
            if stop.wait(args.interval):
                return 0
            frames = 0
            while not stop.is_set():
                payload = packet(sampler.sample(), config)
                written = board.write(payload)
                if written != len(payload):
                    raise OSError("Incomplete serial write")
                frames += 1
                if frames == 1 or frames % 10 == 0:
                    print(f"USB frames written: {frames} (display acceptance not verified)", flush=True)
                stop.wait(args.interval)
    except KeyboardInterrupt:
        print("Sender stopped.")
        return 0
    except (OSError, serial.SerialException) as exc:
        print(f"USB sender error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
