"""Legacy v12.1 wire format, copied verbatim from the Linux sender.
Keep parity with UniversalArduinoMonitor.py until the shared runtime migration.
"""
from typing import List

ARDUINO_PAYLOAD_FIELD_COUNT = 73
EXTRA_BATTERY_SLOTS = 3

def clean_field(text: object, max_len: int = 32) -> str:
    value = str(text).replace("|", "/").replace("\n", " ").replace("\r", " ").strip()
    if not value:
        return "--"
    return value[:max_len]


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
