"""UNO R4 WiFi UDP discovery using the same wire format as v12 firmware.

This is an explicit read-only network scan. It never alters board pairing,
sends passwords, or mistakes an open TCP port for a connected Arduino stream.
"""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import socket
import time
import psutil


@dataclass(frozen=True)
class WifiBoard:
    address: str
    tcp_port: int
    name: str
    target_host: str
    target_hostname: str
    advertised_address: str


def parse_reply(payload: bytes, source_ip: str) -> WifiBoard | None:
    try:
        message = payload.decode("utf-8").strip()
        parts = message.split("|")
        if len(parts) < 3 or parts[0] != "UAM_HERE":
            return None
        port = int(parts[2].strip())
        if not 1 <= port <= 65535:
            return None
        # Probe the actual reply address, never trust an unverified advertised IP.
        ipaddress.ip_address(source_ip)
        return WifiBoard(
            address=source_ip, tcp_port=port,
            name=(parts[3].strip() if len(parts) > 3 else "") or "Arduino R4 WiFi",
            target_host=parts[4].strip() if len(parts) > 4 else "",
            target_hostname=parts[5].strip() if len(parts) > 5 else "",
            advertised_address=parts[1].strip(),
        )
    except (ValueError, UnicodeError):
        return None


def broadcast_targets() -> list[str]:
    targets = []
    stats = psutil.net_if_stats()
    addrs = psutil.net_if_addrs()
    for name, state in stats.items():
        if not state.isup or name == "lo" or name.startswith(
            ("wg", "tun", "tap", "tailscale", "docker", "podman", "veth", "virbr", "br-")
        ):
            continue
        for addr in addrs.get(name, []):
            if addr.family != socket.AF_INET or not addr.address or not addr.netmask:
                continue
            try:
                target = str(ipaddress.IPv4Interface(
                    f"{addr.address}/{addr.netmask}"
                ).network.broadcast_address)
                if target not in targets:
                    targets.append(target)
            except ValueError:
                continue
    if "255.255.255.255" not in targets:
        targets.append("255.255.255.255")
    return targets


def discover(timeout: float = 1.5, port: int = 5001, magic: str = "UAM_DISCOVER") -> list[WifiBoard]:
    if not 1 <= port <= 65535:
        raise ValueError("Discovery port out of range")
    timeout = max(0.2, min(float(timeout), 3.0))
    found: dict[tuple[str, int], WifiBoard] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("", 0))
        sock.settimeout(0.3)
        sent = False
        for dest in broadcast_targets():
            try:
                sock.sendto(magic.encode("utf-8"), (dest, port))
                sent = True
            except OSError:
                continue
        if not sent:
            return []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, source = sock.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                break
            board = parse_reply(data, source[0])
            if board is not None:
                found[(board.address, board.tcp_port)] = board
    return list(found.values())
