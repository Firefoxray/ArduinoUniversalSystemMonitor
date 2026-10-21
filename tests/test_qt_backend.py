"""Backend regression tests; no Qt libraries or attached Arduino required."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from uasm_qt.backend import ConfigError, ConfigStore, readable_rate, version, _temperature


class ConfigStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "config").mkdir()
        (self.root / "VERSION").write_text("v12.1\n")
        (self.root / "config/monitor_config.default.json").write_text(
            json.dumps({"wifi_port": 5000, "prefer_usb": True})
        )
        (self.root / "config/monitor_config.json").write_text(
            json.dumps({"wifi_port": 5050, "qbittorrent_enabled": True})
        )
        (self.root / "config/monitor_config.local.json").write_text(
            json.dumps({"wifi_host": "192.168.0.159", "custom_setting": "keep"})
        )
        self.store = ConfigStore(self.root)

    def test_merge_order(self):
        merged = self.store.load()
        self.assertEqual(merged["wifi_port"], 5050)
        self.assertTrue(merged["prefer_usb"])
        self.assertEqual(merged["wifi_host"], "192.168.0.159")

    def test_local_save_preserves_unknown_keys_and_shared_file(self):
        shared = self.store.shared.read_text()
        self.store.save_local({"wifi_port": 5051, "prefer_usb": False})
        local = json.loads(self.store.local.read_text())
        self.assertEqual(local["custom_setting"], "keep")
        self.assertEqual(local["wifi_port"], 5051)
        self.assertFalse(self.store.load()["prefer_usb"])
        self.assertEqual(self.store.shared.read_text(), shared)
        self.assertEqual(self.store.local.stat().st_mode & 0o777, 0o600)

    def test_invalid_port_does_not_write(self):
        original = self.store.local.read_text()
        for value in (0, 65536, "5000", True):
            with self.assertRaises(ConfigError):
                self.store.save_local({"wifi_port": value})
        self.assertEqual(self.store.local.read_text(), original)

    def test_disallow_unexpected_fields(self):
        with self.assertRaises(ConfigError):
            self.store.save_local({"qbittorrent_password": "not from GUI"})

    def test_invalid_json_is_visible(self):
        self.store.local.write_text("[oops")
        with self.assertRaises(ConfigError):
            self.store.load()
        with self.assertRaises(ConfigError):
            self.store.save_local({"wifi_enabled": False})

    def test_readonly_version(self):
        self.assertEqual(version(self.root), "v12.1")

    def test_rate_formatting(self):
        self.assertEqual(readable_rate(0), "0 B/s")
        self.assertEqual(readable_rate(1024**2), "1.0 MiB/s")

    def test_temperature_ignores_non_cpu_sensors(self):
        temp = lambda label, current: SimpleNamespace(label=label, current=current)
        with patch("uasm_qt.backend.psutil.sensors_temperatures", return_value={
            "amdgpu": [temp("junction", 85)],
            "nvme": [temp("Composite", 46)],
            "coretemp": [temp("Core 0", 54), temp("Package id 0", 49)],
        }):
            self.assertEqual(_temperature(), 49.0)
        with patch("uasm_qt.backend.psutil.sensors_temperatures", return_value={
            "amdgpu": [temp("edge", 45)],
        }):
            self.assertIsNone(_temperature())



if __name__ == "__main__":
    unittest.main()
