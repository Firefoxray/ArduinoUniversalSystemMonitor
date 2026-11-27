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


def route_ipv4(address, port):
    """Resolve the interface used for this destination, without transmitting data."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect((address, port))
        return sock.getsockname()[0]


def assignment(board, local_ip=None, hostname=None):
    """Match firmware semantics: every nonempty advertised target must match."""
    host = board.target_host.strip().casefold()
    name = board.target_hostname.strip().casefold()
    if not host and not name:
        return "Unassigned"
    actual_name = (hostname if hostname is not None else socket.gethostname()).strip().casefold()
    if name and name != actual_name:
        return "Other PC"
    if host:
        try:
            actual_ip = local_ip if local_ip is not None else route_ipv4(board.address, board.tcp_port)
        except OSError:
            return "Unknown"
        if not actual_ip:
            return "Unknown"
        if host != actual_ip.casefold():
            return "Other PC"
    return "This PC"


class PairingRejected(RuntimeError):
    """Do not retry a refused or invalid firmware handshake."""


def pair_socket(sock, hostname=None):
    """Use the exact firmware protocol and the TCP socket's local IP."""
    hostname = (hostname if hostname is not None else socket.gethostname()).strip()
    local_ip = sock.getsockname()[0]
    if not hostname or any(ch in hostname for ch in "|\r\n") or len(hostname.encode("utf-8")) > 63:
        raise PairingRejected("Invalid local hostname")
    hello = f"UAM_PAIR|{hostname}|{local_ip}\n".encode("utf-8")
    if len(hello) > 96:
        raise PairingRejected("Local identity exceeds firmware handshake limit")
    sock.sendall(hello)
    response = bytearray()
    deadline = time.monotonic() + 3.0
    while b"\n" not in response and len(response) < 128:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Firmware pairing response timed out")
        sock.settimeout(remaining)
        chunk = sock.recv(128 - len(response))
        if not chunk:
            raise ConnectionError("Board disconnected during pairing")
        response.extend(chunk)
    line = bytes(response).split(b"\n", 1)[0].strip()
    if line != b"PAIR_OK" or b"\n" not in response:
        raise PairingRejected(f"Firmware response: {line.decode('utf-8', errors='replace')}")
    sock.settimeout(2.0)
    return local_ip


def verified_board(address, port, discovery_port=5001):
    """Refuse to claim an unassigned or foreign board, even on reconnection."""
    boards = discover(port=discovery_port)
    board = next((b for b in boards if b.address == address and b.tcp_port == port), None)
    if board is None:
        raise ConnectionError("Selected board did not answer discovery; check power and USB activity")
    state = assignment(board)
    if state != "This PC":
        raise PairingRejected(f"{board.name}: {state}; no pairing changes made")
    return board
