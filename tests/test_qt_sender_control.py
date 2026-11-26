"""Test GUI-owned sender lifecycle with an isolated dummy child, not hardware."""
import tempfile
import time
import unittest

from PySide6.QtCore import QCoreApplication
from uasm_qt.sender_control import SenderControl


class DummySender(SenderControl):
    def arguments(self, port):
        return [
            "-u", "-c",
            "import sys; print('USB frames written: 1'); "
            "sys.stdin.readline(); print('Dummy sender stopped')",
        ]


class SenderControlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QCoreApplication.instance() or QCoreApplication([])

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.sender = DummySender(self.directory.name)
        self.states = []
        self.messages = []
        self.sender.status.connect(self.states.append)
        self.sender.output.connect(self.messages.append)
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(self.sender.shutdown)

    def wait_until(self, condition):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            self.app.processEvents()
            if condition():
                return
            time.sleep(0.01)
        self.fail(f"Timed out; states={self.states}, output={self.messages}")

    def test_start_restart_stop(self):
        self.sender.start("COM3")
        self.wait_until(lambda: self.sender.state == "Sending USB")
        self.assertTrue(self.sender.running)
        self.sender.restart("COM3")
        self.wait_until(lambda: self.states.count("Sending USB") >= 2)
        self.assertEqual(self.messages.count("Dummy sender stopped"), 1)
        self.sender.stop()
        self.wait_until(lambda: self.sender.state == "Stopped" and not self.sender.running)
        self.assertEqual(self.messages.count("Dummy sender stopped"), 2)

    def test_shutdown_while_running(self):
        self.sender.start("COM3")
        self.wait_until(lambda: self.sender.state == "Sending USB")
        self.sender.shutdown()
        self.wait_until(lambda: not self.sender.running)
        self.assertFalse(self.sender.running)


if __name__ == "__main__":
    unittest.main()
