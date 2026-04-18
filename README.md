# Arduino Universal System Monitor (v12.0 Beta)

A Linux system monitor that sends live PC stats to Arduino touchscreen dashboards.

**Current version:** `v12.0 Beta` (shared via `VERSION`).

- **Main runtime (both Fedora + Debian):** `UniversalArduinoMonitor.py`.
- **Service path:** `arduino-monitor.service` runs the same Python runtime in background.
- **GUI tools are optional:** launched manually with:
  - `./UniversalMonitorControlCenter.sh`
  - `./UniversalMonitorDashboard.sh`
- **CLI identity (primary):** UASM.
- **RayFetch:** optional fetch-style personality alias on the same backend.

---

## Unified architecture

This project keeps one runtime architecture:

- Python monitor runtime is the core sender/data collector.
- GUI is optional and independent.
- Headless Debian works because GUI is optional (not because of a separate mode).
- Fedora desktop workflow stays intact when GUI tools are launched.

---

## Main features

- Live stats: CPU, RAM, temperatures, network, storage, processes, GPU, optional qBittorrent.
- USB + Wi-Fi monitor transport with UNO R4 Wi-Fi pairing/discovery helpers.
- Shared snapshot backend for service sender and one-shot CLI output.
- Optional Java Control Center + desktop dashboard.
- UASM CLI:
  - `fetch`
  - `status`
  - `update`
  - `doctor`
  - `config`

---

## Fedora / Debian / Linux Mint install

### Fedora

```bash
sudo dnf install -y git python3 python3-pip java-21-openjdk arduino-cli socat

git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor

chmod +x fedora_easy_setup.sh
./fedora_easy_setup.sh
```

Generic installer path (also valid on Fedora):

```bash
./install.sh
```

---

### Debian (headless or desktop)

```bash
sudo apt update
sudo apt install -y git python3 python3-pip python3-venv socat lm-sensors pciutils upower

git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor

./install.sh
```

Notes:
- `./install.sh` handles distro detection and service creation.
- GUI tools are optional; headless deployments can skip GUI launch commands.
- Linux Mint users can use the same Debian package/install path.

### Make `uasm` commands work without `python3 ...` or `./`

The installer now links CLI launchers into `~/.local/bin` (`uasm`, `uasm-fetch`, `uasmfetch`, `rayfetch`, `uasm-update`) and adds `~/.local/bin` to PATH if needed.

If your current shell still cannot find `uasm`, run:

```bash
source ~/.bashrc   # or: source ~/.zshrc
hash -r
which uasm
```

If needed, add PATH manually:

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

---

## Run / service commands

```bash
sudo systemctl status arduino-monitor.service
sudo systemctl restart arduino-monitor.service
sudo systemctl stop arduino-monitor.service
sudo systemctl reset-failed arduino-monitor.service
```

Manual runtime launch:

```bash
python3 UniversalArduinoMonitor.py
```

---

## CLI usage (UASM + RayFetch)

### New command structure

```bash
# canonical one-shot fetch
python3 UniversalArduinoMonitor.py fetch
./uasm fetch
./uasm-fetch
./uasmfetch

# optional RayFetch personality alias (same backend path)
./rayfetch

# diagnostics/status
python3 UniversalArduinoMonitor.py status
./uasm status

# update flow
python3 UniversalArduinoMonitor.py --update
python3 UniversalArduinoMonitor.py update
./uasm update
./rayfetch update
./uasm-update

# health checks
python3 UniversalArduinoMonitor.py doctor
./uasm doctor

# config display
python3 UniversalArduinoMonitor.py config
python3 UniversalArduinoMonitor.py config --json
./uasm config
```

### Legacy one-shot flags (still supported)

```bash
python3 UniversalArduinoMonitor.py --rayfetch
python3 UniversalArduinoMonitor.py --json
python3 UniversalArduinoMonitor.py --payload-preview
python3 UniversalArduinoMonitor.py --arduino-status
```

### How to add your own CLI command later (manual extensibility)

The CLI is intentionally centralized in `UniversalArduinoMonitor.py`:

1. Add a handler function near the CLI section (`handle_<name>_command`).
2. Add one `COMMAND_REGISTRY` entry with:
   - `help`
   - `handler`
   - optional `add_args` function for command-specific flags
3. The parser auto-builds subcommands from that registry.

This keeps one shared backend path and avoids duplicating command logic.

---

## Optional GUI launch

Run GUI only when you want it:

```bash
./UniversalMonitorControlCenter.sh
./UniversalMonitorDashboard.sh
```

The monitor runtime/service does not require these commands to run.

---

## Notes

- Linux-focused project. Legacy Windows files remain under `legacy/Windows/` for reference.
- Serial permission changes can require logout/login.
- Fedora remains a primary desktop target; Debian instructions are now documented for the same runtime path.

## Remote actions (advanced / optional in v12.0 Beta)

The Control Center includes a **Remote / CLI actions panel** for predefined actions (update, flash, service control, discovery debug), runnable locally or over SSH profiles.
