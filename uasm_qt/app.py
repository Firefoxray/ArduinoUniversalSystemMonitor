"""UASM v13 Qt preview: an optional GUI, never part of the sender service."""
from __future__ import annotations

import argparse
import subprocess
import sys
import socket
from collections import deque
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtCore import QObject, QPointF, QRectF, Qt, QProcess, QThread, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtNetwork import QAbstractSocket, QTcpSocket
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QFrame, QGridLayout, QHBoxLayout, QLabel,
    QLineEdit, QMessageBox, QPlainTextEdit, QPushButton, QProgressBar, QScrollArea,
    QSpinBox, QStackedWidget, QTableWidget, QTableWidgetItem,
    QVBoxLayout, QWidget,
)

from .backend import (
    ConfigError, ConfigStore, ROOT, TelemetrySampler, drives, readable_rate,
    serial_devices, service_state, version,
)
from .wifi import discover as discover_wifi

STYLE = """
/* Neutral graphite theme with restrained silver/steel accents. */
QWidget { background: #181c20; color: #e4e8eb; font-family: "Noto Sans", "Segoe UI", sans-serif; font-size: 13px; }
QLabel, QCheckBox { background: transparent; }
QFrame#sidebar { background: #20252b; border-right: 1px solid #3a424a; }
QFrame#card { background: #242a30; border: 1px solid #3c454e; border-radius: 9px; }
QLabel#heading { font-size: 23px; font-weight: 700; }
QLabel#muted { color: #aeb8c1; }
QLabel#value { font-size: 21px; font-weight: 700; color: #f4f5f6; }
QLabel#compactValue { font-size: 19px; font-weight: 700; color: #f4f5f6; }
QPushButton { background: #353e47; border: 1px solid #505b65; border-radius: 6px; padding: 8px 11px; }
QPushButton:hover { background: #46535e; }
QPushButton#primary { background: #596d7d; border-color: #8297a7; font-weight: 700; }
QPushButton#primary:hover { background: #6d8395; }
QPushButton#nav { background: transparent; border: none; text-align: left; padding: 11px; }
QPushButton#nav:checked { background: #3b4752; border-left: 3px solid #a9becd; }
QLineEdit, QSpinBox, QPlainTextEdit, QTableWidget {
 background: #1c2228; border: 1px solid #46515a; border-radius: 5px; padding: 6px;
 selection-background-color: #516577;
}
QHeaderView::section { background: #323b44; color: #e1e6eb; padding: 7px; border: none; }
QTableWidget { gridline-color: #343d45; alternate-background-color: #242c33; }
QScrollArea { border: none; }
QProgressBar { background: #353d45; border: none; border-radius: 4px; text-align: center; min-height: 8px; max-height: 10px; }
QProgressBar::chunk { background: #91a9b8; border-radius: 4px; }
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
        # GPU/psutil probing never blocks Qt's main/UI thread.
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="uasm-telemetry")
        self.pending = None
        self.timer = QTimer(self)
        self.timer.setInterval(1300)
        self.timer.timeout.connect(self.poll)
        self.completed = QTimer(self)
        self.completed.setInterval(75)
        self.completed.timeout.connect(self.collect)

    def poll(self):
        if self.pending is None:
            self.pending = self.pool.submit(self.sampler.sample)

    def collect(self):
        if self.pending is None or not self.pending.done():
            return
        job, self.pending = self.pending, None
        try:
            self.sample.emit(job.result())
        except Exception as exc:
            self.error.emit(str(exc))

    def start(self):
        self.completed.start()
        self.timer.start()
        QTimer.singleShot(100, self.poll)


class History(QWidget):
    """CPU + GPU graph with no giant decorative empty metric cards."""
    def __init__(self):
        super().__init__()
        self.cpu = deque(maxlen=100)
        self.gpu = deque(maxlen=100)
        self.setMinimumHeight(175)

    def add(self, cpu, gpu):
        self.cpu.append(max(0.0, min(100.0, float(cpu))))
        self.gpu.append(None if gpu is None else max(0.0, min(100.0, float(gpu))))
        self.update()

    def paintEvent(self, _event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        area = QRectF(self.rect()).adjusted(36, 12, -10, -22)
        if area.width() <= 0 or area.height() <= 0:
            return
        painter.setPen(QPen(QColor("#45505a"), 1))
        for i in range(5):
            y = area.top() + area.height() * i / 4
            painter.drawLine(QPointF(area.left(), y), QPointF(area.right(), y))
            painter.setPen(QColor("#a5b1ba"))
            painter.drawText(QPointF(1, y + 4), f"{100 - i * 25:>3}")
            painter.setPen(QPen(QColor("#45505a"), 1))
        for samples, color in ((self.cpu, "#b5c6d1"), (self.gpu, "#c2ae87")):
            values = list(samples)
            if len(values) < 2:
                continue
            painter.setPen(QPen(QColor(color), 2))
            for i in range(1, len(values)):
                if values[i] is None or values[i - 1] is None:
                    continue
                x1 = area.left() + area.width() * (i - 1) / 99
                x2 = area.left() + area.width() * i / 99
                y1 = area.bottom() - area.height() * values[i - 1] / 100
                y2 = area.bottom() - area.height() * values[i] / 100
                painter.drawLine(QPointF(x1, y1), QPointF(x2, y2))


class DesktopMonitor(QWidget):
    """Full monitoring workspace in a *separate* Qt window, shared sampler."""
    def __init__(self, hub):
        super().__init__()
        self.setWindowTitle("UASM • Desktop Monitor")
        self.resize(1040, 700)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 12)
        layout.setSpacing(10)
        title_row = QHBoxLayout()
        heading = text("Desktop Monitor")
        heading.setObjectName("heading")
        title_row.addWidget(heading)
        title_row.addStretch()
        self.host = text("Sampling…", muted=True)
        title_row.addWidget(self.host)
        layout.addLayout(title_row)

        # Eight small, information-rich meters instead of four giant cards.
        self.values = {}
        strip = QGridLayout()
        strip.setHorizontalSpacing(9)
        strip.setVerticalSpacing(8)
        for index, key in enumerate((
            "CPU", "GPU", "Memory", "VRAM", "CPU Temp", "GPU Temp", "Down", "Up",
        )):
            frame = QFrame()
            frame.setObjectName("card")
            inner = QVBoxLayout(frame)
            inner.setContentsMargins(12, 9, 12, 9)
            inner.setSpacing(3)
            inner.addWidget(text(key, muted=True))
            metric = QLabel("—")
            metric.setObjectName("compactValue")
            inner.addWidget(metric)
            self.values[key] = metric
            strip.addWidget(frame, index // 4, index % 4)
        layout.addLayout(strip)

        graph_box, graph_layout = card("Live load · CPU (silver)  /  GPU (sand)")
        graph_layout.setContentsMargins(12, 9, 12, 7)
        self.graph = History()
        graph_layout.addWidget(self.graph)
        layout.addWidget(graph_box, 2)

        lower = QGridLayout()
        lower.setSpacing(9)
        process_box, process_layout = card("Top processes")
        self.process_table = QTableWidget(0, 3)
        self.process_table.setHorizontalHeaderLabels(["Process", "CPU", "RAM"])
        self.process_table.horizontalHeader().setStretchLastSection(True)
        self.process_table.verticalHeader().hide()
        self.process_table.setShowGrid(False)
        self.process_table.setAlternatingRowColors(True)
        self.process_table.setMinimumHeight(205)
        process_layout.addWidget(self.process_table)
        lower.addWidget(process_box, 0, 0)
        summary_box, summary_layout = card("System & storage")
        self.gpu_name = text("GPU: checking…", muted=True)
        summary_layout.addWidget(self.gpu_name)
        self.usage = text("RAM: —")
        summary_layout.addWidget(self.usage)
        self.uptime = text("Uptime: —")
        summary_layout.addWidget(self.uptime)
        self.root_disk = text("Storage: —")
        summary_layout.addWidget(self.root_disk)
        self.disk_progress = QProgressBar()
        self.disk_progress.setRange(0, 100)
        self.disk_progress.setTextVisible(False)
        summary_layout.addWidget(self.disk_progress)
        summary_layout.addStretch()
        lower.addWidget(summary_box, 0, 1)
        lower.setColumnStretch(0, 3)
        lower.setColumnStretch(1, 2)
        layout.addLayout(lower, 2)
        hub.sample.connect(self.update_stats)

    def update_stats(self, data):
        self.values["CPU"].setText(f"{data.cpu:.0f}%")
        self.values["GPU"].setText("N/A" if data.gpu.load is None else f"{data.gpu.load:.0f}%")
        self.values["Memory"].setText(f"{data.ram:.0f}%")
        self.values["VRAM"].setText(
            "N/A" if data.gpu.vram_total is None or not data.gpu.vram_total else
            f"{100 * (data.gpu.vram_used or 0) / data.gpu.vram_total:.0f}%"
        )
        self.values["CPU Temp"].setText("N/A" if data.temperature is None else f"{data.temperature:.0f} °C")
        self.values["GPU Temp"].setText("N/A" if data.gpu.temperature is None else f"{data.gpu.temperature:.0f} °C")
        self.values["Down"].setText(readable_rate(data.down_bytes_per_sec))
        self.values["Up"].setText(readable_rate(data.up_bytes_per_sec))
        self.graph.add(data.cpu, data.gpu.load)
        self.host.setText(data.hostname)
        self.gpu_name.setText(f"GPU: {data.gpu.name} · {data.gpu.source}")
        self.usage.setText(f"RAM: {data.used_gb:.1f} / {data.total_gb:.1f} GiB")
        hours, mins = divmod(data.uptime_seconds // 60, 60)
        self.uptime.setText(f"Uptime: {hours}h {mins}m · {len(data.per_cpu)} CPU threads")
        self.root_disk.setText(data.storage_label)
        self.disk_progress.setValue(int(data.root_used_percent or 0))
        self.process_table.setRowCount(len(data.top_processes))
        for row, proc in enumerate(data.top_processes):
            for col, value in enumerate((proc.name, f"{proc.cpu:.1f}%", f"{proc.memory:.1f}%")):
                self.process_table.setItem(row, col, QTableWidgetItem(value))


class WifiScanWorker(QThread):
    completed = Signal(object)
    failed = Signal(str)

    def __init__(self, discovery_port):
        super().__init__()
        self.discovery_port = discovery_port

    def run(self):
        try:
            self.completed.emit(discover_wifi(port=self.discovery_port))
        except (OSError, ValueError) as exc:
            self.failed.emit(str(exc))


class ControlCenter(QWidget):
    def __init__(self, hub):
        super().__init__()
        self.hub = hub
        self.store = ConfigStore()
        self.dashboard = None
        self.processes = []
        self.wifi_worker = None
        self.wifi_boards = []
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
        QTimer.singleShot(650, self.scan_wifi)

    def select(self, index):
        self.stack.setCurrentIndex(index)
        for position, widget in enumerate(self.nav_buttons):
            widget.setChecked(index == position)

    def overview(self):
        body, layout = page("Overview", "Live telemetry and device transport at a glance.")
        grid = QGridLayout()
        self.metrics = [QLabel("—") for _ in range(8)]
        for i, (heading, metric) in enumerate(zip(
            ("CPU", "GPU", "RAM", "VRAM", "CPU temperature",
             "GPU temperature", "Download", "Monitor service"), self.metrics,
        )):
            box, inner = card(heading)
            inner.setContentsMargins(13, 10, 13, 10)
            metric.setObjectName("compactValue")
            inner.addWidget(metric)
            grid.addWidget(box, i // 4, i % 4)
        layout.addLayout(grid)
        box, inner = card("Arduino devices · USB + Wi-Fi")
        self.board_line = text("Checking USB and scanning Wi-Fi…")
        inner.addWidget(self.board_line)
        inner.addWidget(text(
            "USB presence or a UDP discovery reply does not prove an active sender connection.",
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
        body, layout = page("Arduino Devices", "Detect local USB boards and discover R4 Wi-Fi devices without changing settings.")
        toolbar = QHBoxLayout()
        toolbar.addWidget(action("Refresh USB", self.refresh_devices))
        toolbar.addWidget(action("Scan Wi-Fi · UDP", self.scan_wifi, primary=True))
        toolbar.addStretch()
        layout.addLayout(toolbar)
        self.device_label = text("USB scan pending", muted=True)
        layout.addWidget(self.device_label)
        self.device_table = QTableWidget(0, 3)
        self.device_table.setHorizontalHeaderLabels(["Port", "Description", "Hardware ID"])
        self.device_table.horizontalHeader().setStretchLastSection(True)
        self.device_table.setMinimumHeight(250)
        layout.addWidget(self.device_table)
        wifi_box, wifi_inner = card("Wi-Fi R4 discovery · UDP probe (default port 5001)")
        self.wifi_status = text("Preparing network scan…", muted=True)
        wifi_inner.addWidget(self.wifi_status)
        self.wifi_table = QTableWidget(0, 4)
        self.wifi_table.setHorizontalHeaderLabels(["Board", "Reply IP / TCP port", "Assigned host", "Assigned name"])
        self.wifi_table.horizontalHeader().setStretchLastSection(True)
        self.wifi_table.verticalHeader().hide()
        self.wifi_table.setMaximumHeight(170)
        wifi_inner.addWidget(self.wifi_table)
        wifi_inner.addWidget(action("Use selected Wi-Fi board in Connections", self.use_selected_wifi))
        wifi_inner.addWidget(text(
            "Found = replied to a firmware UDP discovery request; not yet verified as a live telemetry stream.",
            muted=True,
        ))
        layout.addWidget(wifi_box)
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
        self.metrics[1].setText("N/A" if snapshot.gpu.load is None else f"{snapshot.gpu.load:.0f}%")
        self.metrics[2].setText(f"{snapshot.ram:.0f}%")
        self.metrics[3].setText(
            "N/A" if not snapshot.gpu.vram_total else
            f"{100 * (snapshot.gpu.vram_used or 0) / snapshot.gpu.vram_total:.0f}%"
        )
        self.metrics[4].setText(
            "N/A" if snapshot.temperature is None else f"{snapshot.temperature:.0f}°C"
        )
        self.metrics[5].setText(
            "N/A" if snapshot.gpu.temperature is None else f"{snapshot.gpu.temperature:.0f}°C"
        )
        self.metrics[6].setText(readable_rate(snapshot.down_bytes_per_sec))

    def refresh_status(self):
        # This quick systemd query runs only every 12 seconds, not every frame.
        state, enabled = service_state()
        self.metrics[7].setText(state)
        self.footer.setText(f"Monitor: {state} ({enabled})  •  Runtime: {version()}  •  Qt preview")
        self.refresh_devices()

    def refresh_devices(self):
        found = serial_devices()
        self.device_label.setText(f"{len(found)} USB serial candidate(s)")
        self.update_board_summary(found)
        self.device_table.setRowCount(len(found))
        for row, entry in enumerate(found):
            for col, value in enumerate((entry.port, entry.description, entry.hardware_id)):
                self.device_table.setItem(row, col, QTableWidgetItem(value))

    def update_board_summary(self, found=None):
        if found is None:
            found = serial_devices()
        usb_label = f"{len(found)} USB candidate(s)"
        wifi_label = f"{len(self.wifi_boards)} Wi-Fi reply/replies"
        self.board_line.setText(usb_label + "  ·  " + wifi_label)
        if self.wifi_boards:
            self.board_line.setToolTip(", ".join(
                f"{board.name} {board.address}:{board.tcp_port} → {board.target_host or board.target_hostname or 'unassigned'}"
                for board in self.wifi_boards
            ))

    def scan_wifi(self):
        if self.wifi_worker is not None and self.wifi_worker.isRunning():
            return
        # Network scans are explicit; never rely on the preview worktree's
        # local config matching the running sender installation.
        self.wifi_status.setText("Sending UAM_DISCOVER to LAN broadcast…")
        self.wifi_worker = WifiScanWorker(self.discovery_port.value())
        self.wifi_worker.completed.connect(self.scan_complete)
        self.wifi_worker.failed.connect(self.scan_failed)
        self.wifi_worker.finished.connect(self.wifi_scan_finished)
        self.wifi_worker.start()

    def wifi_scan_finished(self):
        worker = self.wifi_worker
        self.wifi_worker = None
        if worker is not None:
            worker.deleteLater()

    def scan_failed(self, error):
        self.wifi_status.setText(f"Discovery error: {error}")

    def scan_complete(self, boards):
        self.wifi_boards = list(boards)
        self.wifi_table.setRowCount(len(self.wifi_boards))
        for row, board in enumerate(self.wifi_boards):
            cells = (
                board.name,
                f"{board.address}:{board.tcp_port}",
                board.target_host or "Not specified",
                board.target_hostname or "Not specified",
            )
            for col, value in enumerate(cells):
                self.wifi_table.setItem(row, col, QTableWidgetItem(value))
        if self.wifi_boards:
            self.wifi_status.setText(
                f"{len(self.wifi_boards)} UNO R4 discovery reply/replies received. "
                "Select one to fill its endpoint (not saved yet)."
            )
        else:
            self.wifi_status.setText(
                "No R4 discovery replies. Ensure the board is powered, on this LAN, "
                "using compatible R4 firmware, and UDP broadcasts are allowed."
            )
        self.update_board_summary()

    def use_selected_wifi(self):
        selected = self.wifi_table.currentRow()
        if not 0 <= selected < len(self.wifi_boards):
            self.wifi_status.setText("Select a discovered board row first.")
            return
        board = self.wifi_boards[selected]
        self.host.setText(board.address)
        self.port.setValue(board.tcp_port)
        self.wifi.setChecked(True)
        self.connection_result.setText(
            f"{board.name} at {board.address}:{board.tcp_port}. "
            f"Assigned host: {board.target_host or board.target_hostname or 'unspecified'}. "
            "These fields have NOT been saved and the board stream is not verified."
        )
        self.select(2)

    def closeEvent(self, event):
        # A discovery thread may be finishing a 1.5s socket timeout.
        if self.wifi_worker is not None and self.wifi_worker.isRunning():
            self.wifi_worker.wait(3500)
        super().closeEvent(event)

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
