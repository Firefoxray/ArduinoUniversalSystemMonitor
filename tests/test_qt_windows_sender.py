import ast
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from uasm_qt.hardware import GPU
from uasm_qt.protocol import build_arduino_payload
from uasm_qt.sender import packet
from uasm_qt.windows_sensors import parse_tree


def sensor(name, kind, identifier, value):
    return {"Text": name, "Type": kind, "SensorId": identifier, "Value": value, "Children": []}


class WindowsSenderTests(unittest.TestCase):
    def test_sensor_tree(self):
        tree = {"Children": [
            {"Text": "Intel CPU", "Children": [{"Text": "Temperatures", "Children": [
                sensor("CPU Package", "Temperature", "/intelcpu/0/temperature/0", "45,2 °C")
            ]}]},
            {"Text": "Radeon RX 9070 XT", "Children": [{"Text": "Sensors", "Children": [
                sensor("GPU Core", "Load", "/gpu-amd/0/load/0", "27.0 %"),
                sensor("GPU Core", "Temperature", "/gpu-amd/0/temperature/0", "52.0 °C"),
                sensor("GPU Memory Used", "Data", "/gpu-amd/0/data/0", "4.0 GB"),
                sensor("GPU Memory Total", "Data", "/gpu-amd/0/data/2", "16.0 GB")
            ]}]}
        ]}
        cpu, gpu = parse_tree(tree)
        self.assertEqual(cpu, 45.2)
        self.assertEqual(gpu.name, "Radeon RX 9070 XT")
        self.assertEqual(gpu.temperature, 52)
        self.assertEqual(gpu.load, 27)
        self.assertEqual(gpu.vram_used, 4 * 1024**3)
        self.assertEqual(gpu.vram_total, 16 * 1024**3)

    def test_wire_encoder_matches_legacy_source(self):
        import inspect
        root = Path(__file__).resolve().parents[1]
        legacy = ast.parse((root / "UniversalArduinoMonitor.py").read_text(encoding="utf-8"))
        original = next(node for node in legacy.body if isinstance(node, ast.FunctionDef) and node.name == "build_arduino_payload")
        actual = ast.parse(inspect.getsource(build_arduino_payload)).body[0]
        self.assertEqual(ast.dump(original), ast.dump(actual))

    def test_packet_field_count(self):
        data = SimpleNamespace(cpu=27, per_cpu=(1, 2), ram=40, root_used_percent=62,
            temperature=45.2, hostname="TEST|PC", os_name="Test", uptime_seconds=4000,
            down_bytes_per_sec=1000, up_bytes_per_sec=200, used_gb=4, total_gb=8,
            gpu=GPU(), top_processes=())
        with patch("uasm_qt.sender.drives", return_value=[]):
            encoded = packet(data).decode("ascii")
        fields = encoded.rstrip("\n").split("|")
        self.assertEqual(len(fields), 73)
        self.assertEqual(fields[0], "27")
        self.assertEqual(fields[17], "40")
        self.assertEqual(fields[22], "TEST/PC")
        self.assertTrue(encoded.endswith("\n"))
