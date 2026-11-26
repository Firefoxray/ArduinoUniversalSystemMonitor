"""GUI-owned foreground sender. Never controls other processes or system services."""
from __future__ import annotations

import sys
from PySide6.QtCore import QObject, QProcess, QTimer, Signal


class SenderControl(QObject):
    status = Signal(str)
    output = Signal(str)
    changed = Signal()

    def __init__(self, root, parent=None):
        super().__init__(parent)
        self.root = root
        self.process = QProcess(self)
        self.process.setWorkingDirectory(str(root))
        self.process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.process.readyReadStandardOutput.connect(self._read)
        self.process.started.connect(self._started)
        self.process.finished.connect(self._finished)
        self.process.errorOccurred.connect(self._error)
        self.timeout = QTimer(self)
        self.timeout.setSingleShot(True)
        self.timeout.timeout.connect(self._kill_if_running)
        self.port = ""
        self.restart_port = ""
        self.stopping = False
        self.state = "Stopped"
        self.buffer = ""

    @property
    def running(self):
        return self.process.state() != QProcess.ProcessState.NotRunning

    def _status(self, value):
        self.state = value
        self.status.emit(value)
        self.changed.emit()

    def start(self, port):
        if self.running or self.stopping or not port:
            return
        self.port = port
        self.buffer = ""
        self._status("Starting")
        self.output.emit(f"\nStarting preview USB sender on {port}…")
        self.process.start(sys.executable, self.arguments(port))
        self.changed.emit()

    def arguments(self, port):
        return ["-u", "-m", "uasm_qt.sender", "--serial", port, "--managed"]

    def _started(self):
        if not self.stopping:
            self._status("Opening USB")

    def _read(self):
        chunk = bytes(self.process.readAllStandardOutput()).decode("utf-8", errors="replace")
        self.buffer += chunk
        while "\n" in self.buffer:
            line, self.buffer = self.buffer.split("\n", 1)
            line = line.rstrip("\r")
            self.output.emit(line)
            if not self.stopping and line.startswith("USB frames written:"):
                self._status("Sending USB")
        if len(self.buffer) > 8192:
            self.output.emit(self.buffer[:8192])
            self.buffer = ""

    def _request_stop(self):
        if not self.running:
            return
        self.stopping = True
        self._status("Stopping")
        self.process.write(b"stop\n")
        self.process.closeWriteChannel()
        self.timeout.start(6000)

    def stop(self):
        self.restart_port = ""
        self._request_stop()

    def restart(self, port):
        if not port:
            return
        if self.running:
            self.restart_port = port
            self._request_stop()
        else:
            self.start(port)

    def _kill_if_running(self):
        if self.running:
            self.output.emit("Sender did not stop in time; terminating only the GUI-owned child.")
            self.process.kill()

    def _finished(self, code, exit_status):
        self.timeout.stop()
        self._read()
        if self.buffer:
            self.output.emit(self.buffer)
            self.buffer = ""
        requested = self.stopping
        self.stopping = False
        port, self.restart_port = self.restart_port, ""
        self._status("Stopped" if requested or code == 0 else f"Exited ({code})")
        if port:
            self.start(port)

    def _error(self, error):
        self.output.emit(self.process.errorString())
        if error == QProcess.ProcessError.FailedToStart:
            self.timeout.stop()
            self.stopping = False
            self.restart_port = ""
            self._status("Start failed")

    def shutdown(self):
        """Release the serial port before destroying the owning Qt window."""
        self.stop()
        if self.running and not self.process.waitForFinished(6000):
            self.process.kill()
            self.process.waitForFinished(2000)
