# Arduino Universal System Monitor (v11.3)

A Fedora-first desktop monitor that sends live PC stats to Arduino touchscreen dashboards, with a GUI Control Center for setup, flashing, and monitor settings.

**Current version:** `v11.3` (shared via `VERSION`).

- **Current focus:** Linux/Fedora workflow and GUI-first setup.
- **Primary GUI:** Java Control Center (`./UniversalMonitorControlCenter.sh`).
- **Monitor runtime:** Python sender (`UniversalArduinoMonitor.py`) managed by systemd.

---

## Project overview

This project combines:
- a **Python monitor service** that reads system stats and sends data over USB or Wi-Fi,
- **Arduino sketches** for supported boards/displays,
- a **GUI Control Center** to configure transport, pages, modules, storage mappings, and flash boards.

It is currently tuned and tested mainly for **Fedora Linux**.

---

## Main features

- Live stats: CPU, RAM, temperatures, network, storage, processes, GPU, and optional qBittorrent data.
- USB + Wi-Fi monitor transport, with UNO R4 Wi-Fi pairing helpers.
- Board/page profiles and per-board display options.
- RayFetch one-shot CLI modes for snapshot debugging:
  - `--rayfetch`
  - `--json`
  - `--payload-preview`
  - `--arduino-status`
- Storage target controls, including:
  - `storage_enabled_targets`
  - `storage_disk0_target`
  - `storage_disk1_target`
- Built-in flashing workflow from the Control Center.
- Desktop pop-out Gaming Mode framework page with MangoHud-oriented Linux telemetry scaffolding for future FPS/frametime ingestion.

---

## Screens / pages

The Arduino side supports pages such as:
- Home / quad overview
- CPU
- GPU
- Network
- Storage
- Processes
- Graphs
- qBittorrent (optional)

Preview screenshots are available in `screenshots/`.

---

## Easy install/setup (Fedora)

### 1) Install base packages

```bash
sudo dnf install -y git python3 python3-pip java-21-openjdk arduino-cli socat
```

### 2) Clone the repo

```bash
git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor
```

### 3) Run the easiest Fedora setup path

```bash
chmod +x fedora_easy_setup.sh
./fedora_easy_setup.sh
```

Installer actions (high-level):
- installs Python dependencies,
- ensures config files exist,
- configures/starts `arduino-monitor.service`,
- optionally launches Arduino install/flash flow.

If you prefer the generic path, run `./install.sh` directly.

### 4) Launch the GUI Control Center

```bash
./UniversalMonitorControlCenter.sh
```

From the GUI, review/save monitor settings, choose storage targets, and run flash actions.

### 5) Launch desktop pop-out dashboard only (standalone scaffold)

```bash
./UniversalMonitorDashboard.sh
```

This runs the Java app in `--dashboard-only` mode so the pop-out dashboard opens directly without showing the full Control Center window.

---

## Run instructions

### Service commands

```bash
sudo systemctl status arduino-monitor.service
sudo systemctl restart arduino-monitor.service
sudo systemctl stop arduino-monitor.service
```

### Manual monitor run (debug/testing)

```bash
python3 UniversalArduinoMonitor.py
```

### RayFetch one-shot commands

```bash
python3 UniversalArduinoMonitor.py --rayfetch
python3 UniversalArduinoMonitor.py --json
python3 UniversalArduinoMonitor.py --payload-preview
python3 UniversalArduinoMonitor.py --arduino-status
```

---

## Notes / limitations

- **Fedora-first** project: most testing and setup tuning is currently Fedora-based.
- Linux support is strongest; other distros may need package-name adjustments.
- Legacy Windows files remain under `legacy/Windows/` for reference only.
- Some features (serial permissions/group updates) may require logout/login after install.

---

## TODO

- GUI-first polish pass across all tabs and setup flows.
- Reduce remaining manual setup friction in installer + Control Center.
- Improve first-run diagnostics/help messaging.
- **Windows compatibility planned for a future release**.

## Remote actions (advanced / optional in v11.3)

The Control Center includes a **Remote / CLI actions panel** with a conservative "predefined actions only" model:
- update project
- flash Arduinos
- monitor service restart/status
- Wi-Fi discovery debug log action

Actions can run locally or over SSH (saved target profiles supported). This is intended as an advanced helper path, not a required setup path for v11.3.
