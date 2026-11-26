"""Read-only local LibreHardwareMonitor JSON sensor adapter.

No driver installation, web server startup, remote access, or sensor writes.
"""
from __future__ import annotations

import json
import math
import os
import re
from urllib import request
from .hardware import GPU


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _reading(value):
    match = re.fullmatch(r"\s*(-?\d+(?:[.,]\d+)?)\s*(.*?)\s*", str(value))
    if not match:
        return None, ""
    number = float(match.group(1).replace(",", "."))
    return (number, match.group(2).casefold()) if math.isfinite(number) else (None, "")


def _bytes(value):
    number, unit = _reading(value)
    scale = {"b": 1, "kb": 1024, "kib": 1024, "mb": 1024**2, "mib": 1024**2,
             "gb": 1024**3, "gib": 1024**3}.get(unit)
    return round(number * scale) if scale and number is not None and number >= 0 else None


def parse_tree(tree):
    """Return CPU package temperature and one GPU, grouped by sensor ID."""
    cpu = []
    gpus = {}

    def walk(node, parents=()):
        if not isinstance(node, dict):
            return
        name = str(node.get("Text", ""))
        kind = str(node.get("Type", "")).casefold()
        identifier = str(node.get("SensorId", "")).strip("/").casefold().split("/")
        family = identifier[0]
        value = node.get("Value")
        number, unit = _reading(value)
        normalized = name.casefold()
        if kind == "temperature" and number is not None and 0 <= number <= 150:
            if family in ("intelcpu", "amdcpu", "cpu"):
                priority = {"cpu package": 0, "cpu (tctl/tdie)": 1, "cpu tctl/tdie": 1,
                            "cpu (tdie)": 2, "core max": 3}.get(normalized)
                if priority is not None:
                    cpu.append((priority, number))
        if family.startswith("gpu-") and len(identifier) >= 3:
            key = "/".join(identifier[:2])
            hardware = parents[-2] if len(parents) >= 2 else family
            group = gpus.setdefault(key, {"name": hardware, "source": "LibreHardwareMonitor (local HTTP)"})
            if normalized == "gpu core":
                if kind == "load" and number is not None and 0 <= number <= 100:
                    group["load"] = number
                elif kind == "temperature" and number is not None and 0 <= number <= 150:
                    group["temperature"] = number
            if kind in ("data", "smalldata"):
                # Keep matching used/total from the same memory source.
                mem = _bytes(value)
                if mem is not None:
                    keys = {"gpu memory used": "vram_used", "gpu memory total": "vram_total",
                            "d3d dedicated memory used": "d3d_used", "d3d dedicated memory total": "d3d_total"}
                    if normalized in keys:
                        group[keys[normalized]] = mem
        for child in node.get("Children") or []:
            walk(child, parents + (name,))

    walk(tree)
    cpu_temp = min(cpu, default=(999, None))[1]
    if not gpus:
        return cpu_temp, None
    candidates = []
    for group in gpus.values():
        d3d_used = group.pop("d3d_used", None)
        d3d_total = group.pop("d3d_total", None)
        if d3d_used is not None and d3d_total is not None:
            group["vram_used"], group["vram_total"] = d3d_used, d3d_total
        candidates.append(GPU(**group))
    return cpu_temp, max(candidates, key=lambda gpu: (gpu.vram_total or 0, gpu.load is not None))


def read_lhm_status():
    """Poll loopback and report whether the endpoint or sensor mapping failed."""
    try:
        port = int(os.environ.get("UASM_LHM_PORT", "8085"))
        if not 1 <= port <= 65535:
            return None, None, "Invalid UASM_LHM_PORT"
        opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
        with opener.open(f"http://127.0.0.1:{port}/data.json", timeout=1.2) as response:
            cpu, gpu = parse_tree(json.loads(response.read(2_000_000)))
            status = f"Local LHM endpoint reachable on port {port}; CPU temperature: {'found' if cpu is not None else 'missing'}; GPU: {gpu.name if gpu else 'missing'}"
            return cpu, gpu, status
    except (OSError, ValueError, TypeError) as exc:
        return None, None, f"LHM unavailable: {exc}"


def read_lhm():
    cpu, gpu, _status = read_lhm_status()
    return cpu, gpu
