"""Non-hardware tests for R4 discovery protocol and GPU sysfs collector."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from uasm_qt.hardware import GPU, gpu_amd, read_gpu
from uasm_qt.wifi import WifiBoard, broadcast_targets, parse_reply, discover


class R4WifiTests(unittest.TestCase):
    def test_real_r4_wire_format(self):
        value = parse_reply(
            b"UAM_HERE|192.168.0.47|5000|DESKTOP_R4_WIFI35|192.168.0.22|FIRE-FEDORA-DESKTOP",
            "192.168.0.47",
        )
        self.assertEqual(value, WifiBoard(
            address="192.168.0.47", tcp_port=5000,
            name="DESKTOP_R4_WIFI35", target_host="192.168.0.22",
            target_hostname="FIRE-FEDORA-DESKTOP",
            advertised_address="192.168.0.47",
        ))

    def test_source_address_wins_over_advertised_ip(self):
        value = parse_reply(b"UAM_HERE|1.2.3.4|5050|R4", "192.168.0.47")
        self.assertEqual(value.address, "192.168.0.47")
        self.assertEqual(value.advertised_address, "1.2.3.4")

    def test_reject_invalid_payloads(self):
        for data in (b"garbage", b"UAM_HERE|ip|0", b"UAM_HERE|ip|65536", b"UAM_HERE|ip|abc", b"\xff"):
            with self.subTest(data=data):
                self.assertIsNone(parse_reply(data, "192.168.0.47"))

    def test_port_validation(self):
        with self.assertRaises(ValueError):
            discover(port=65536)
        with self.assertRaises(ValueError):
            discover(port=0)

    def test_broadcast_filters_tunnel_and_docker(self):
        import socket
        from types import SimpleNamespace
        iface = lambda up: SimpleNamespace(isup=up)
        ip = lambda addr, mask: SimpleNamespace(family=socket.AF_INET, address=addr, netmask=mask)
        with patch("uasm_qt.wifi.psutil.net_if_stats", return_value={
            "enp9s0": iface(True), "wg-home": iface(True), "docker0": iface(True)
        }), patch("uasm_qt.wifi.psutil.net_if_addrs", return_value={
            "enp9s0": [ip("192.168.0.22", "255.255.255.0")],
            "wg-home": [ip("10.66.66.3", "255.255.255.0")],
            "docker0": [ip("172.20.0.1", "255.255.0.0")],
        }):
            result = broadcast_targets()
        self.assertIn("192.168.0.255", result)
        self.assertIn("255.255.255.255", result)
        self.assertNotIn("10.66.66.255", result)
        self.assertNotIn("172.20.255.255", result)


class GPUTests(unittest.TestCase):
    def test_amd_sysfs_fields(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            device = root / "card1" / "device"
            hwmon = device / "hwmon" / "hwmon0"
            hwmon.mkdir(parents=True)
            for key, value in {
                "vendor": "0x1002",
                "boot_vga": "1",
                "gpu_busy_percent": "37",
                "mem_info_vram_used": str(4 * 1024**3),
                "mem_info_vram_total": str(16 * 1024**3),
                "product_name": "AMD Radeon test",
            }.items():
                (device / key).write_text(value)
            (hwmon / "temp1_input").write_text("57000")
            gpu = gpu_amd(root)
            self.assertEqual(gpu.name, "AMD Radeon test")
            self.assertEqual(gpu.load, 37.0)
            self.assertEqual(gpu.temperature, 57.0)
            self.assertEqual(gpu.vram_used, 4 * 1024**3)
            self.assertEqual(gpu.vram_total, 16 * 1024**3)

    def test_missing_sensor_never_means_zero_gpu(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertIsNone(gpu_amd(Path(temp)))
        with patch("uasm_qt.hardware.gpu_amd", return_value=None), patch(
            "uasm_qt.hardware.gpu_nvidia", return_value=None
        ):
            value = read_gpu()
        self.assertEqual(value, GPU())
        self.assertIsNone(value.load)


if __name__ == "__main__":
    unittest.main()
