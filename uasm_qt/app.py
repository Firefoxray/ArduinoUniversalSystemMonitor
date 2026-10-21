"""UASM v13 Qt preview: an optional GUI, never part of the sender service."""
from __future__ import annotations

import argparse
import subprocess
import sys
from collections import deque

from PySide6.QtCore import QObject, QPointF, QRectF, Qt, QProcess, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtNetwork import QAbstractSocket, QTcpSocket
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QFrame, QGridLayout, QHBoxLayout, QLabel,
    QLineEdit, QMessageBox, QPlainTextEdit, QPushButton, QScrollArea,
    QSpinBox, QStackedWidget, QTableWidget, QTableWidgetItem,
    QVBoxLayout, QWidget,
)

from .backend import (
    ConfigError, ConfigStore, ROOT, TelemetrySampler, drives, readable_rate,
    serial_devices, service_state, version,
)

STYLE = """
QWidget { background: #101722; color: #e8f0fa; font-family: "Noto Sans", "Segoe UI", sans-serif; font-size: 13px; }
QFrame#sidebar { background: #152131; border-right: 1px solid #2e4159; }
QFrame#card { background: #1a293a; border: 1px solid #304258; border-radius: 12px; }
QLabel#heading { font-size: 24px; font-weight: bold; }
QLabel#muted { color: #a1b4cb; }
QLabel#value { font-size: 29px; font-weight: bold; color: white; }
QPushButton { background: #293c53; border: 1px solid #40576e; border-radius: 8px; padding: 9px 12px; }
QPushButton:hover { background: #36526d; }
QPushButton#primary { background: #287db3; border-color: #4999d1; font-weight: bold; }
QPushButton#nav { background: transparent; border: none; text-align: left; padding: 12px; }
QPushButton#nav:checked { background: #285277; border-left: 3px solid #7bcaff; }
QLineEdit, QSpinBox, QPlainTextEdit, QTableWidget { background: #121e2c; border: 1px solid #3a5068; border-radius: 7px; padding: 7px; }
QHeaderView::section { background: #24374c; padding: 8px; border: none; }
QScrollArea { border: none; }
"""


def text(content, muted=False):
    widget = QLabel(content)
    widget.setWordWrap(True)
    if muted:
        widget.setObjectName("muted")
    return widget


def action(name, callback, primary=False):
    widget = QPushButton(name)
    if primary:
        widget.setObjectName("primary")
    widget.clicked.connect(callback)
    return widget


def card(title):
    widget = QFrame()
    widget.setObjectName("card")
    layout = QVBoxLayout(widget)
    layout.setContentsMargins(18, 16, 18, 16)
    layout.addWidget(text(title, muted=True))
    return widget, layout


def page(title, subtitle):
    widget = QWidget()
    layout = QVBoxLayout(widget)
    layout.setContentsMargins(18, 20, 18, 20)
    layout.setSpacing(14)
    heading = text(title)
    heading.setObjectName("heading")
    layout.addWidget(heading)
    layout.addWidget(text(subtitle, muted=True))
    return widget, layout


def scroll(widget):
    outer = QScrollArea()
    outer.setWidgetResizable(True)
    outer.setWidget(widget)
    return outer


class Hub(QObject):
    sample = Signal(object)
    error = Signal(str)

    def __init__(self):
        super().__init__()
        self.sampler = TelemetrySampler()
        self.timer = QTimer(self)
        self.timer.setInterval(1300)
        self.timer.timeout.connect(self.poll)

    def poll(self):
        try:
            self.sample.emit(self.sampler.sample())
        except Exception as exc:
            self.error.emit(str(exc))

    def start(self):
        self.timer.start()
        QTimer.singleShot(100, self.poll)


class History(QWidget):
    def __init__(self):
        super().__init__()
        self.values = deque(maxlen=100)
        self.setMinimumHeight(190)

    def add(self, value):
        self.values.append(max(0.0, min(100.0, value)))
        self.update()

    def paintEvent(self, _event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = QRectF(self.rect()).adjusted(10, 10, -10, -14)
        if rect.width() <= 0:
            return
        painter.setPen(QPen(QColor("#334b65"), 1))
        for i in range(1, 4):
            y = rect.top() + rect.height() * i / 4
            painter.drawLine(QPointF(rect.left(), y), QPointF(rect.right(), y))
        points = list(self.values)
        if len(points) < 2:
            return
        painter.setPen(QPen(QColor("#6ecaff"), 2.5))
        for i in range(1, len(points)):
            x1 = rect.left() + rect.width() * (i - 1) / (len(points) - 1)
            x2 = rect.left() + rect.width() * i / (len(points) - 1)
            y1 = rect.bottom() - rect.height() * points[i - 1] / 100
            y2 = rect.bottom() - rect.height() * points[i] / 100
            painter.drawLine(QPointF(x1, y1), QPointF(x2, y2))


class DesktopMonitor(QWidget):
    """Independent pop-out window sharing a single telemetry sampler."""
    def __init__(self, hub):
        super().__init__()
        self.setWindowTitle("UASM • Desktop Monitor")
        self.resize(900, 620)
        outer = QVBoxLayout(self)
        body, layout = page("Desktop Monitor", "Live statistics • separate Qt window")
        outer.addWidget(scroll(body))
        grid = QGridLayout()
        self.cpu, self.ram, self.down, self.up = (QLabel("—") for _ in range(4))
        for i, (title, metric) in enumerate((
            ("CPU", self.cpu), ("Memory", self.ram),
            ("Download", self.down), ("Upload", self.up),
        )):
            box, inner = card(title)
            metric.setObjectName("value")
            inner.addWidget(metric)
            grid.addWidget(box, i // 2, i % 2)
        layout.addLayout(grid)
        box, inner = card("CPU history")
        self.graph = History()
        inner.addWidget(self.graph)
        layout.addWidget(box)
        self.extra = text("Waiting for telemetry…", muted=True)
        layout.addWidget(self.extra)
        layout.addStretch()
        hub.sample.connect(self.update_stats)

    def update_stats(self, snapshot):
        self.cpu.setText(f"{snapshot.cpu:.0f}%")
        self.ram.setText(f"{snapshot.ram:.0f}%")
        self.down.setText(readable_rate(snapshot.down_bytes_per_sec))
        self.up.setText(readable_rate(snapshot.up_bytes_per_sec))
        self.graph.add(snapshot.cpu)
        temperature = "unavailable" if snapshot.temperature is None else f"{snapshot.temperature:.0f}°C"
        self.extra.setText(
            f"{snapshot.hostname}  •  RAM {snapshot.used_gb:.1f}/{snapshot.total_gb:.1f} GiB"
            f"  •  CPU temperature {temperature}  •  {len(snapshot.per_cpu)} logical threads"
        )


class ControlCenter(QWidget):
    def __init__(self, hub):
        super().__init__()
        self.hub = hub
        self.store = ConfigStore()
        self.dashboard = None
        self.processes = []
        self.setWindowTitle("UASM • Qt Control Center (v13 Preview)")
        self.resize(1110, 750)
        root = QHBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(220)
        nav_layout = QVBoxLayout(sidebar)
        nav_layout.setContentsMargins(14, 23, 14, 17)
        brand = text("UASM")
        brand.setObjectName("heading")
        nav_layout.addWidget(brand)
        nav_layout.addWidget(text("v13 Qt Preview", muted=True))
        nav_layout.addSpacing(20)
        self.stack = QStackedWidget()
        self.nav_buttons = []
        for title, builder in (
            ("Overview", self.overview),
            ("Devices", self.devices),
            ("Connections", self.connections),
            ("Storage", self.storage),
            ("Logs", self.logs),
            ("Diagnostics", self.diagnostics),
        ):
            index = self.stack.count()
            nav = QPushButton(title)
            nav.setObjectName("nav")
            nav.setCheckable(True)
            nav.clicked.connect(lambda _checked=False, index=index: self.select(index))
            self.nav_buttons.append(nav)
            nav_layout.addWidget(nav)
            self.stack.addWidget(builder())
        nav_layout.addStretch()
        nav_layout.addWidget(text(f"Stable runtime: {version()}", muted=True))
        root.addWidget(sidebar)
        container = QWidget()
        main = QVBoxLayout(container)
        main.setContentsMargins(20, 16, 20, 16)
        header = QHBoxLayout()
        header.addWidget(text("Ray Co. Universal System Monitor", muted=True))
        header.addStretch()
        header.addWidget(action("Open Desktop Monitor", self.open_monitor, primary=True))
        main.addLayout(header)
        main.addWidget(self.stack, 1)
        self.footer = text("The sender and firmware are unchanged in this preview.", muted=True)
        main.addWidget(self.footer)
        root.addWidget(container, 1)
        self.select(0)
        hub.sample.connect(self.update_stats)
        hub.error.connect(lambda error: self.footer.setText(f"Telemetry error: {error}"))
        self.health_timer = QTimer(self)
        self.health_timer.timeout.connect(self.refresh_status)
        self.health_timer.start(12000)
        QTimer.singleShot(200, self.refresh_status)

    def select(self, index):
        self.stack.setCurrentIndex(index)
        for position, widget in enumerate(self.nav_buttons):
            widget.setChecked(index == position)

    def overview(self):
        body, layout = page("Overview", "Live telemetry and device transport at a glance.")
        grid = QGridLayout()
        self.metrics = [QLabel("—") for _ in range(4)]
        for i, (heading, metric) in enumerate(zip(
            ("CPU usage", "RAM usage", "Network download", "Monitor service"), self.metrics,
        )):
            box, inner = card(heading)
            metric.setObjectName("value")
            inner.addWidget(metric)
            grid.addWidget(box, i // 2, i % 2)
        layout.addLayout(grid)
        box, inner = card("Arduino USB detection")
        self.board_line = text("Scanning…")
        inner.addWidget(self.board_line)
        inner.addWidget(text(
            "Device detected ≠ live connection. Wi-Fi pairing status requires protocol verification.",
            muted=True,
        ))
        layout.addWidget(box)
        row = QHBoxLayout()
        row.addWidget(action("Refresh", self.refresh_status))
        row.addWidget(action("Legacy Flash & Profiles", self.open_legacy))
        row.addStretch()
        layout.addLayout(row)
        layout.addStretch()
        return scroll(body)

    def devices(self):
        body, layout = page("Arduino Devices", "USB candidates; port detection does not open the serial device.")
        layout.addWidget(action("Refresh Devices", self.refresh_devices))
        self.device_label = text("Scanning…", muted=True)
        layout.addWidget(self.device_label)
        self.device_table = QTableWidget(0, 3)
        self.device_table.setHorizontalHeaderLabels(["Port", "Description", "Hardware ID"])
        self.device_table.horizontalHeader().setStretchLastSection(True)
        self.device_table.setMinimumHeight(250)
        layout.addWidget(self.device_table)
        box, inner = card("Firmware & board page profiles")
        inner.addWidget(text("Use the existing Java interface while native Qt flashing, profile editing and TFT previews are being migrated."))
        inner.addWidget(action("Open Legacy Control Center", self.open_legacy))
        layout.addWidget(box)
        layout.addStretch()
        QTimer.singleShot(300, self.refresh_devices)
        return scroll(body)

    def connections(self):
        body, layout = page("Connections", "Safer local transport settings. No firmware credentials are modified.")
        box, inner = card("Arduino Wi-Fi / USB transport")
        self.wifi = QCheckBox("Wi-Fi enabled")
        self.discover = QCheckBox("UDP discovery enabled")
        self.usb = QCheckBox("Prefer USB if present")
        for checkbox in (self.wifi, self.discover, self.usb):
            inner.addWidget(checkbox)
        grid = QGridLayout()
        self.host = QLineEdit()
        self.host.setPlaceholderText("Board IP or hostname; blank for discovery")
        self.port = QSpinBox()
        self.port.setRange(1, 65535)
        self.discovery_port = QSpinBox()
        self.discovery_port.setRange(1, 65535)
        for i, (field, widget) in enumerate((
            ("Fixed Arduino host", self.host),
            ("TCP port", self.port),
            ("UDP discovery port", self.discovery_port),
        )):
            grid.addWidget(text(field), i, 0)
            grid.addWidget(widget, i, 1)
        inner.addLayout(grid)
        row = QHBoxLayout()
        row.addWidget(action("Reload", self.load_connections))
        row.addWidget(action("Test TCP", self.test_tcp))
        row.addWidget(action("Save Local Settings", self.save_connections, primary=True))
        inner.addLayout(row)
        self.connection_result = text("", muted=True)
        inner.addWidget(self.connection_result)
        layout.addWidget(box)
        layout.addWidget(text(
            "Saves only config/monitor_config.local.json. Restart the monitor service after saving. "
            "TCP testing checks reachability, not pairing or a successful Arduino handshake.",
            muted=True,
        ))
        layout.addStretch()
        self.socket = QTcpSocket(self)
        self.socket.connected.connect(self.tcp_connected)
        self.socket.errorOccurred.connect(self.tcp_failed)
        QTimer.singleShot(100, self.load_connections)
        return scroll(body)

    def storage(self):
        body, layout = page("Storage", "Local filesystem capacity; this page doesn't change Arduino disk assignments.")
        layout.addWidget(action("Refresh Mounts", self.refresh_drives))
        self.drive_table = QTableWidget(0, 4)
        self.drive_table.setHorizontalHeaderLabels(["Mount", "Device", "Used / Total", "Use"])
        self.drive_table.horizontalHeader().setStretchLastSection(True)
        self.drive_table.setMinimumHeight(380)
        layout.addWidget(self.drive_table)
        layout.addStretch()
        QTimer.singleShot(400, self.refresh_drives)
        return scroll(body)

    def logs(self):
        body, layout = page("Service Logs", "Latest journalctl entries, read only.")
        layout.addWidget(action("Refresh Logs", self.refresh_logs))
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        layout.addWidget(self.log_view, 1)
        return body

    def diagnostics(self):
        body, layout = page("Diagnostics", "Use existing UASM doctor without installing or modifying anything.")
        layout.addWidget(action("Run Doctor", self.run_diagnostics))
        self.doctor_view = QPlainTextEdit()
        self.doctor_view.setReadOnly(True)
        layout.addWidget(self.doctor_view, 1)
        layout.addWidget(text(f"Repository: {ROOT}", muted=True))
        return body

    def update_stats(self, snapshot):
        self.metrics[0].setText(f"{snapshot.cpu:.0f}%")
        self.metrics[1].setText(f"{snapshot.ram:.0f}%")
        self.metrics[2].setText(readable_rate(snapshot.down_bytes_per_sec))

    def refresh_status(self):
        # This quick systemd query runs only every 12 seconds, not every frame.
        state, enabled = service_state()
        self.metrics[3].setText(state)
        self.footer.setText(f"Monitor: {state} ({enabled})  •  Runtime: {version()}  •  Qt preview")
        self.refresh_devices()

    def refresh_devices(self):
        found = serial_devices()
        self.device_label.setText(f"{len(found)} USB serial candidate(s)")
        self.board_line.setText(", ".join(item.port for item in found) if found else "No USB serial board found")
        self.device_table.setRowCount(len(found))
        for row, entry in enumerate(found):
            for col, value in enumerate((entry.port, entry.description, entry.hardware_id)):
                self.device_table.setItem(row, col, QTableWidgetItem(value))

    def refresh_drives(self):
        rows = drives()
        self.drive_table.setRowCount(len(rows))
        for row, (mount, device, used, total, percent) in enumerate(rows):
            cells = (mount, device, f"{used / 1024**3:.1f} / {total / 1024**3:.1f} GiB", f"{percent:.0f}%")
            for col, value in enumerate(cells):
                self.drive_table.setItem(row, col, QTableWidgetItem(value))

    def load_connections(self):
        try:
            config = self.store.load()
            self.wifi.setChecked(bool(config.get("wifi_enabled", True)))
            self.discover.setChecked(bool(config.get("wifi_auto_discovery", False)))
            self.usb.setChecked(bool(config.get("prefer_usb", True)))
            self.host.setText(str(config.get("wifi_host") or ""))
            self.port.setValue(int(config.get("wifi_port") or 5000))
            self.discovery_port.setValue(int(config.get("wifi_discovery_port") or 5001))
            self.connection_result.setText("Loaded defaults → shared config → local override.")
        except (ConfigError, ValueError) as exc:
            self.connection_result.setText(f"Configuration error: {exc}")

    def save_connections(self):
        changes = {
            "wifi_enabled": self.wifi.isChecked(),
            "wifi_auto_discovery": self.discover.isChecked(),
            "prefer_usb": self.usb.isChecked(),
            "wifi_host": self.host.text().strip(),
            "wifi_port": self.port.value(),
            "wifi_discovery_port": self.discovery_port.value(),
        }
        if changes["wifi_enabled"] and not (changes["wifi_host"] or changes["wifi_auto_discovery"]):
            QMessageBox.warning(self, "Wi-Fi", "Enter the board address or enable UDP discovery.")
            return
        try:
            self.store.save_local(changes)
        except (ConfigError, OSError) as exc:
            QMessageBox.critical(self, "Save failed", str(exc))
            return
        self.connection_result.setText("Saved local overrides. Restart arduino-monitor.service to apply.")

    def test_tcp(self):
        host = self.host.text().strip()
        if not host:
            self.connection_result.setText("Enter a fixed IP/hostname to test TCP.")
            return
        self.socket.abort()
        self.connection_result.setText(f"Testing {host}:{self.port.value()}…")
        self.socket.connectToHost(host, self.port.value())
        QTimer.singleShot(2200, self.tcp_timeout)

    def tcp_timeout(self):
        if self.socket.state() == QAbstractSocket.SocketState.ConnectingState:
            self.socket.abort()
            self.connection_result.setText("TCP test timed out.")

    def tcp_connected(self):
        self.connection_result.setText("TCP reachable. Arduino pairing not verified.")
        self.socket.disconnectFromHost()

    def tcp_failed(self, _error):
        self.connection_result.setText(f"TCP failed: {self.socket.errorString()}")

    def run_command(self, program, arguments, output):
        process = QProcess(self)
        self.processes.append(process)
        process.setProgram(program)
        process.setArguments(arguments)
        process.setWorkingDirectory(str(ROOT))
        process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        output.setPlainText("Running…")

        def done(_exit, _status):
            output.setPlainText(bytes(process.readAllStandardOutput()).decode(errors="replace"))
            self.processes.remove(process)
            process.deleteLater()

        process.finished.connect(done)
        process.errorOccurred.connect(lambda _error: output.setPlainText(process.errorString()))
        process.start()

    def refresh_logs(self):
        self.run_command("journalctl", ["--no-pager", "-n", "120", "-u", "arduino-monitor.service"], self.log_view)

    def run_diagnostics(self):
        self.run_command(sys.executable, [str(ROOT / "UniversalArduinoMonitor.py"), "doctor"], self.doctor_view)

    def open_legacy(self):
        path = ROOT / "UniversalMonitorControlCenter.sh"
        try:
            subprocess.Popen([str(path)], cwd=ROOT, start_new_session=True)
        except OSError as exc:
            QMessageBox.warning(self, "Java Control Center", str(exc))

    def open_monitor(self):
        if self.dashboard is None:
            self.dashboard = DesktopMonitor(self.hub)
        self.dashboard.show()
        self.dashboard.raise_()
        self.dashboard.activateWindow()


def main(argv=None):
    parser = argparse.ArgumentParser(description="UASM v13 Qt preview")
    parser.add_argument("--dashboard-only", action="store_true")
    parser.add_argument("--smoke", action="store_true", help="Show windows briefly for CI")
    args = parser.parse_args(argv)
    application = QApplication(sys.argv[:1])
    application.setStyle("Fusion")
    application.setStyleSheet(STYLE)
    hub = Hub()
    window = DesktopMonitor(hub) if args.dashboard_only else ControlCenter(hub)
    window.show()
    hub.start()
    if args.smoke:
        QTimer.singleShot(450, application.quit)
    return application.exec()
