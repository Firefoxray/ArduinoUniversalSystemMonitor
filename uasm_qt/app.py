"""UASM v13 Qt preview: an optional GUI, never part of the sender service."""
from __future__ import annotations

import argparse
import subprocess
import sys
import socket
from datetime import datetime
from collections import deque
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtCore import QObject, QPointF, QRectF, Qt, QProcess, QThread, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtNetwork import QAbstractSocket, QTcpSocket
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFrame, QGridLayout, QHBoxLayout, QHeaderView, QLabel,
    QLineEdit, QMessageBox, QPlainTextEdit, QPushButton, QProgressBar, QScrollArea,
    QSpinBox, QStackedWidget, QTableWidget, QTableWidgetItem,
    QVBoxLayout, QWidget,
)

from .backend import (
    ConfigError, ConfigStore, ROOT, TelemetrySampler, disk_assignments, mount_key, drives, readable_rate,
    serial_devices, service_state, version,
)
from .wifi import discover as discover_wifi
from .sender import arduino_usb_ports
from .sender_control import SenderControl
from .updater import PreviewUpdater

STYLE = """
/* Neutral graphite theme with restrained silver/steel accents. */
QWidget { background: #181c20; color: #e4e8eb; font-family: "Noto Sans", "Segoe UI", sans-serif; font-size: 13px; }
QLabel, QCheckBox { background: transparent; }
QToolTip { color: #edf1f4; background-color: #353d45; border: 1px solid #667580; padding: 6px; }
QPushButton:disabled { color: #879099; background: #292e33; border-color: #3a4148; }
QPushButton#primary:disabled { color: #879099; background: #292e33; border-color: #3a4148; }
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


def action(name, callback, primary=False, tip=None):
    widget = QPushButton(name)
    if primary:
        widget.setObjectName("primary")
    widget.clicked.connect(callback)
    if tip:
        widget.setToolTip(tip)
        widget.setAccessibleDescription(tip)
    return widget


def help_tip(layout, message):
    """Small focusable help control; tooltip also available on click/tap."""
    from PySide6.QtWidgets import QToolTip
    widget = QPushButton("ⓘ")
    widget.setFixedSize(28, 28)
    widget.setAccessibleName("Help")
    widget.setAccessibleDescription(message)
    widget.setToolTip(message)
    widget.clicked.connect(lambda: QToolTip.showText(widget.mapToGlobal(widget.rect().bottomLeft()), message, widget))
    layout.addWidget(widget, 0, Qt.AlignmentFlag.AlignRight)
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


class UpdateWorker(QThread):
    def __init__(self, updater, plan=None, parent=None):
        super().__init__(parent)
        self.updater, self.plan = updater, plan
        self.result = None
        self.failure = ""

    def run(self):
        try:
            if self.plan is None:
                self.result = self.updater.check()
            else:
                self.updater.apply(self.plan)
                self.result = self.plan
        except Exception as exc:
            self.failure = str(exc)


class ControlCenter(QWidget):
    def __init__(self, hub):
        super().__init__()
        self.hub = hub
        self.store = ConfigStore()
        self.dashboard = None
        self.processes = []
        self.wifi_worker = None
        self.wifi_boards = []
        self.update_worker = None
        self.update_busy = False
        self.last_sender_state = None
        self.last_usb_ports = None
        self.sender = SenderControl(ROOT, self) if sys.platform == "win32" else None
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
        self.update_button = action("Check for updates", self.check_updates, tip="Check the v13 tracking branch; download only after confirmation.")
        header.addWidget(self.update_button)
        header.addWidget(action("Open Desktop Monitor", self.open_monitor, primary=True))
        main.addLayout(header)
        main.addWidget(self.stack, 1)
        self.footer = text("The sender and firmware are unchanged in this preview.", muted=True)
        main.addWidget(self.footer)
        root.addWidget(container, 1)
        self.select(0)
        self.app_log(f"Control Center started · {sys.platform} · runtime {version()}")
        if self.sender:
            self.sender.status.connect(self.sender_status_changed)
            self.sender.output.connect(self.sender_output)
            self.sender.changed.connect(self.sync_sender_buttons)
            self.refresh_sender_ports()
            self.sender_status_changed(self.sender.state)
        hub.sample.connect(self.update_stats)
        hub.error.connect(self.telemetry_error)
        self.health_timer = QTimer(self)
        self.health_timer.timeout.connect(self.refresh_status)
        self.health_timer.start(12000)
        QTimer.singleShot(200, self.refresh_status)
        QTimer.singleShot(650, self.scan_wifi)

    def telemetry_error(self, error):
        self.footer.setText(f"Telemetry error: {error}")
        self.app_log(f"Telemetry error: {error}")

    def app_log(self, message):
        self.log_view.appendPlainText(f"[{datetime.now():%H:%M:%S}] {message}")

    def check_updates(self):
        self.launch_update_worker()

    def launch_update_worker(self, plan=None):
        if self.update_busy:
            return
        self.update_busy = True
        self.update_button.setEnabled(False)
        self.update_button.setText("Downloading…" if plan else "Checking…")
        self.app_log("Applying confirmed update" if plan else "Checking Git for updates")
        self.update_worker = UpdateWorker(PreviewUpdater(ROOT), plan, self)
        self.update_worker.finished.connect(self.update_finished)
        self.update_worker.start()

    def update_finished(self):
        worker = self.update_worker
        self.update_worker = None
        self.update_busy = False
        self.update_button.setEnabled(True)
        self.update_button.setText("Check for updates")
        failure, plan, applying = worker.failure, worker.result, worker.plan is not None
        worker.deleteLater()
        if failure:
            self.app_log(f"Update blocked: {failure}")
            QMessageBox.warning(self, "UASM update", failure)
            return
        if not applying:
            if not plan.behind:
                self.app_log("Preview is up to date")
                QMessageBox.information(self, "UASM update", "You're up to date.")
                return
            answer = QMessageBox.question(
                self, "Update and restart",
                f"{plan.behind} new commit(s):\n\n{plan.summary}\n\n"
                "Download and restart Control Center? Its USB sender will stop; start it again after reopening. "
                "Your local settings will be preserved.",
            )
            if answer != QMessageBox.StandardButton.Yes:
                self.app_log("Update cancelled")
                return
            if self.sender:
                self.sender.shutdown()
            self.launch_update_worker(plan)
            return
        self.app_log(f"Update applied: {plan.target[:8]}")
        try:
            result = QProcess.startDetached(sys.executable, ["-m", "uasm_qt"], str(ROOT))
            ok = result[0] if isinstance(result, tuple) else result
        except Exception as exc:
            ok = False
            self.app_log(f"Restart failed: {exc}")
        if ok:
            if self.dashboard is not None:
                self.dashboard.close()
            self.close()
            QApplication.instance().quit()
        else:
            QMessageBox.warning(self, "Update installed", "Could not relaunch automatically. Close and reopen UASM manually.")

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
        help_tip(inner, "USB presence or a UDP discovery reply does not prove an active sender connection.")
        layout.addWidget(box)
        if self.sender:
            sender_box, sender_inner = card("Windows USB sender")
            self.sender_line = text("Stopped", muted=True)
            self.sender_line.setToolTip("Successful USB writes do not prove display acceptance.")
            sender_inner.addWidget(self.sender_line)
            port_row = QHBoxLayout()
            port_row.addWidget(text("Arduino port"))
            self.sender_port = QComboBox()
            self.sender_port.setToolTip("Select the Arduino USB serial port, such as COM3.")
            self.sender_port.currentIndexChanged.connect(self.sync_sender_buttons)
            port_row.addWidget(self.sender_port, 1)
            port_row.addWidget(action("Refresh ports", self.refresh_sender_ports))
            sender_inner.addLayout(port_row)
            controls = QHBoxLayout()
            self.start_sender_button = action("Start", self.start_preview_sender, primary=True, tip="Start sending live stats to the selected USB Arduino.")
            self.stop_sender_button = action("Stop", self.sender.stop, tip="Stop only this window’s sender and release its serial port.")
            self.restart_sender_button = action("Restart", self.restart_preview_sender, tip="Restart this sender and reload saved disk assignments and local settings.")
            for widget in (self.start_sender_button, self.stop_sender_button, self.restart_sender_button):
                controls.addWidget(widget)
            sender_inner.addLayout(controls)
            help_tip(sender_inner, "Only this application’s sender is controlled. Stop any older PowerShell sender first. Closing Control Center stops this sender.")
            layout.addWidget(sender_box)
        row = QHBoxLayout()
        row.addWidget(action("Refresh", self.refresh_status))
        if sys.platform == "linux":
            row.addWidget(action("Legacy Flash & Profiles", self.open_legacy))
        row.addStretch()
        layout.addLayout(row)
        layout.addStretch()
        return scroll(body)

    def devices(self):
        body, layout = page("Arduino Devices", "Detect local USB boards and discover R4 Wi-Fi devices without changing settings.")
        toolbar = QHBoxLayout()
        toolbar.addWidget(action("Refresh USB", self.refresh_devices, tip="Enumerate serial devices without opening a port."))
        toolbar.addWidget(action("Scan Wi-Fi · UDP", self.scan_wifi, primary=True, tip="Discover compatible R4 boards on the local network without changing pairing."))
        toolbar.addStretch()
        layout.addLayout(toolbar)
        self.device_label = text("USB scan pending", muted=True)
        layout.addWidget(self.device_label)
        self.device_table = QTableWidget(0, 3)
        self.device_table.setHorizontalHeaderLabels(["Port", "Description", "Hardware ID"])
        self.device_table.horizontalHeader().setStretchLastSection(True)
        self.device_table.setMinimumHeight(150)
        self.device_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        layout.addWidget(self.device_table)
        wifi_box, wifi_inner = card("Wi-Fi R4 discovery · UDP probe (default port 5001)")
        self.wifi_status = text("Preparing network scan…", muted=True)
        wifi_inner.addWidget(self.wifi_status)
        self.wifi_table = QTableWidget(0, 4)
        self.wifi_table.setHorizontalHeaderLabels(["Board", "Reply IP / TCP port", "Assigned host", "Assigned name"])
        self.wifi_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.wifi_table.horizontalHeader().setStretchLastSection(True)
        self.wifi_table.verticalHeader().hide()
        self.wifi_table.setMaximumHeight(170)
        wifi_inner.addWidget(self.wifi_table)
        wifi_inner.addWidget(action("Use selected Wi-Fi board in Connections", self.use_selected_wifi, tip="Fill the selected board’s host and port. Does not save, pair or connect."))
        help_tip(wifi_inner, "Found means the firmware replied to UDP discovery; active telemetry is not verified.")
        layout.addWidget(wifi_box)
        box, inner = card("Firmware & board page profiles")
        if sys.platform == "linux":
            inner.addWidget(text("Use the existing Java interface while native Qt flashing, profile editing and TFT previews are being migrated."))
            inner.addWidget(action("Open Legacy Control Center", self.open_legacy))
        else:
            inner.addWidget(text("Windows preview: USB sender controls are on Overview. Native flashing, page profiles and Wi-Fi sending are not yet implemented."))
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
        self.wifi.setToolTip("Enable Wi-Fi transport in the saved configuration. Windows Wi-Fi sending is pending.")
        self.discover.setToolTip("Allow UDP discovery when resolving a board. Manual discovery works independently.")
        self.usb.setToolTip("Prefer USB if available. The current Windows preview sender only uses USB.")
        for checkbox in (self.wifi, self.discover, self.usb):
            inner.addWidget(checkbox)
        grid = QGridLayout()
        self.host = QLineEdit()
        self.host.setPlaceholderText("Board IP or hostname; blank for discovery")
        self.host.setToolTip("Address of the Arduino, not the computer. A fixed endpoint is preferred for multiple boards.")
        self.port = QSpinBox()
        self.port.setRange(1, 65535)
        self.port.setToolTip("TCP port the Arduino listens on, usually 5000.")
        self.discovery_port = QSpinBox()
        self.discovery_port.setRange(1, 65535)
        self.discovery_port.setToolTip("UDP discovery port used by the Arduino firmware, usually 5001.")
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
        row.addWidget(action("Test TCP", self.test_tcp, tip="Check endpoint reachability only, not pairing or telemetry acceptance."))
        row.addWidget(action("Save Local Settings", self.save_connections, primary=True, tip="Save this checkout’s local configuration. Does not flash the board or modify another installation."))
        inner.addLayout(row)
        self.connection_result = text("", muted=True)
        inner.addWidget(self.connection_result)
        layout.addWidget(box)
        help_tip(inner,
            "Settings apply to this checkout only. "
            + ("Restart the matching sender to apply." if sys.platform == "linux" else "Windows Wi-Fi sending is pending.")
        )
        layout.addStretch()
        self.socket = QTcpSocket(self)
        self.socket.connected.connect(self.tcp_connected)
        self.socket.errorOccurred.connect(self.tcp_failed)
        QTimer.singleShot(100, self.load_connections)
        return scroll(body)

    def storage(self):
        body, layout = page("Storage", "Choose the volumes shown on the Arduino Home page.")
        box, inner = card("Arduino disk assignments")
        grid = QGridLayout()
        self.disk_choices = [QComboBox(), QComboBox()]
        self.disk_choices_loaded = False
        for index, choice in enumerate(self.disk_choices):
            grid.addWidget(text(f"Disk{index}"), index, 0)
            choice.setToolTip(f"Choose the mounted volume shown in Disk{index} on the Arduino home page.")
            grid.addWidget(choice, index, 1)
        grid.setColumnStretch(1, 1)
        inner.addLayout(grid)
        toolbar = QHBoxLayout()
        toolbar.addWidget(action("Refresh Mounts", self.refresh_drives))
        toolbar.addWidget(action("Save Disk Assignments", self.save_disk_assignments, primary=True, tip="Persist the selected mounts; restart the preview sender to apply."))
        inner.addLayout(toolbar)
        self.disk_status = text("Preview sender only", muted=True)
        self.disk_status.setToolTip("These assignments do not modify the original Linux service.")
        inner.addWidget(self.disk_status)
        layout.addWidget(box)
        if self.sender:
            layout.addWidget(action("Save & restart sender", self.save_and_restart_sender, tip="Save the selected disks and restart the GUI-owned USB sender if running."))
        self.drive_table = QTableWidget(0, 4)
        self.drive_table.setHorizontalHeaderLabels(["Mount", "Device", "Used / Total", "Use"])
        self.drive_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.drive_table.horizontalHeader().setStretchLastSection(True)
        self.drive_table.setMinimumHeight(260)
        self.drive_table.verticalHeader().hide()
        self.drive_table.setAlternatingRowColors(True)
        layout.addWidget(self.drive_table)
        layout.addStretch()
        QTimer.singleShot(400, self.refresh_drives)
        return scroll(body)

    def logs(self):
        body, layout = page("Logs", "Application activity and sender output.")
        row = QHBoxLayout()
        row.addWidget(action("Clear", lambda: self.log_view.clear()))
        row.addWidget(action("Copy", lambda: QApplication.clipboard().setText(self.log_view.toPlainText())))
        if sys.platform == "linux":
            row.addWidget(action("Fetch service logs", self.refresh_logs))
        row.addStretch()
        layout.addLayout(row)
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setMaximumBlockCount(600)
        layout.addWidget(self.log_view, 1)
        if sys.platform == "linux":
            self.service_log_view = QPlainTextEdit()
            self.service_log_view.setReadOnly(True)
            layout.addWidget(self.service_log_view, 1)
        return body

    def diagnostics(self):
        body, layout = page("Diagnostics", "Read-only local hardware and runtime diagnostics.")
        layout.addWidget(action("Run Doctor" if sys.platform == "linux" else "Run Preview Diagnostics", self.run_diagnostics))
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
        if self.sender:
            self.sender_status_changed(self.sender.state)
        else:
            self.metrics[7].setText(state)
            self.footer.setText(f"Monitor: {state} ({enabled})  •  Runtime: {version()}  •  Qt preview")
        self.refresh_devices()

    def refresh_sender_ports(self):
        if not self.sender:
            return
        previous = self.sender_port.currentData()
        self.sender_port.blockSignals(True)
        self.sender_port.clear()
        for port in arduino_usb_ports():
            self.sender_port.addItem(port, port)
        if previous:
            index = self.sender_port.findData(previous)
            if index >= 0:
                self.sender_port.setCurrentIndex(index)
        self.sender_port.blockSignals(False)
        self.sync_sender_buttons()

    def sync_sender_buttons(self, *_args):
        if not self.sender or not hasattr(self, "start_sender_button"):
            return
        selected = bool(self.sender_port.currentData())
        self.start_sender_button.setEnabled(selected and not self.sender.running and not self.sender.stopping)
        self.stop_sender_button.setEnabled(self.sender.running and not self.sender.stopping)
        self.restart_sender_button.setEnabled(selected and self.sender.running and not self.sender.stopping)
        self.sender_port.setEnabled(not self.sender.running and not self.sender.stopping)

    def sender_status_changed(self, state):
        if not self.sender:
            return
        if self.last_sender_state != state:
            self.app_log(f"USB sender: {state}")
            self.last_sender_state = state
        self.metrics[7].setText(state)
        self.metrics[7].setToolTip("Status of this application’s sender, not an installed Windows service.")
        port = self.sender.port or "no port started"
        self.sender_line.setText(f"{state} · {port}")
        self.footer.setText(f"Windows Qt preview • managed USB sender: {state} • Wi-Fi sending pending")
        self.sync_sender_buttons()

    def sender_output(self, line):
        self.app_log(line)

    def start_preview_sender(self):
        self.sender.start(self.sender_port.currentData())

    def restart_preview_sender(self):
        if self.sender.running:
            self.sender.restart(self.sender.port)

    def save_and_restart_sender(self):
        if not self.save_disk_assignments():
            return
        if self.sender.running:
            self.sender.restart(self.sender.port)
            self.disk_status.setText(self.disk_status.text() + " Restart requested.")
        else:
            self.disk_status.setText(self.disk_status.text() + " Start the USB sender from Overview.")

    def refresh_devices(self):
        found = serial_devices()
        ports = tuple(item.port for item in found)
        if ports != self.last_usb_ports:
            self.app_log("USB candidates: " + (", ".join(ports) or "none"))
            self.last_usb_ports = ports
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
        self.app_log(f"Wi-Fi scan started on UDP {self.discovery_port.value()}")
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
        self.app_log(f"Wi-Fi discovery error: {error}")

    def scan_complete(self, boards):
        self.wifi_boards = list(boards)
        self.app_log(f"Wi-Fi scan: {len(boards)} replies")
        for board in boards:
            self.app_log(f"Discovered {board.name} at {board.address}:{board.tcp_port}")
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
        if self.update_busy:
            event.ignore()
            return
        if self.sender:
            self.sender.shutdown()
        # A discovery thread may be finishing a 1.5s socket timeout.
        if self.wifi_worker is not None and self.wifi_worker.isRunning():
            self.wifi_worker.wait(3500)
        super().closeEvent(event)

    def refresh_drives(self):
        rows = drives()
        if self.disk_choices_loaded:
            selected = [choice.currentData() or "" for choice in self.disk_choices]
        else:
            try:
                selected = disk_assignments(self.store.load(), rows)
            except ConfigError as exc:
                self.disk_status.setText(str(exc))
                return
            self.disk_choices_loaded = True
        for choice, current in zip(self.disk_choices, selected):
            choice.clear()
            choice.addItem("Unassigned", "")
            matched = 0
            for mount, device, used, total, percent in rows:
                choice.addItem(f"{mount}  |  {percent:.0f}% used  |  {total / 1024**3:.1f} GiB", mount)
                if current and mount_key(mount) == mount_key(current):
                    matched = choice.count() - 1
            if current and not matched:
                choice.addItem(f"{current} (not currently mounted)", current)
                matched = choice.count() - 1
            choice.setCurrentIndex(matched)
        self.drive_table.setRowCount(len(rows))
        for row, (mount, device, used, total, percent) in enumerate(rows):
            cells = (mount, device, f"{used / 1024**3:.1f} / {total / 1024**3:.1f} GiB", f"{percent:.0f}%")
            for col, value in enumerate(cells):
                self.drive_table.setItem(row, col, QTableWidgetItem(value))

    def save_disk_assignments(self):
        if not self.disk_choices_loaded:
            return False
        first, second = (choice.currentData() or "" for choice in self.disk_choices)
        if first and second and mount_key(first) == mount_key(second):
            self.disk_status.setText("Choose two different mounted volumes, or leave one unassigned.")
            return False
        try:
            self.store.save_local({"qt_disk0_mount": first, "qt_disk1_mount": second})
        except (ConfigError, OSError) as exc:
            self.disk_status.setText(f"Could not save: {exc}")
            return False
        self.disk_status.setText(
            f"Saved Disk0: {first or 'unassigned'} | Disk1: {second or 'unassigned'}. "
            "Restart to apply."
        )
        return True

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
        self.connection_result.setText("Saved local overrides in this checkout. " + ("Restart the matching Linux sender to apply." if sys.platform == "linux" else "USB sending is managed on Overview; Wi-Fi sending is pending."))

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
        self.run_command("journalctl", ["--no-pager", "-n", "120", "-u", "arduino-monitor.service"], self.service_log_view)

    def run_diagnostics(self):
        self.app_log("Running diagnostics")
        args = [str(ROOT / "UniversalArduinoMonitor.py"), "doctor"] if sys.platform == "linux" else ["-m", "uasm_qt.probe"]
        self.run_command(sys.executable, args, self.doctor_view)

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
