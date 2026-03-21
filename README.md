# Ray Co. Linux / Windows Arduino Desktop System Monitor

Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.

**Author:** Ray Barrett  
**Version:** 9.0 beta
**Last Modified:** March 20, 2026  

---

## Preview

### Main Views
<p align="center">
  <img src="screenshots/home1.JPEG" width="420"/>
  <img src="screenshots/home2.JPEG" width="420"/>
</p>

### Detail Pages
<p align="center">
  <img src="screenshots/cpu1.JPEG" width="160"/>
  <img src="screenshots/cpu2.JPEG" width="160"/>
  <img src="screenshots/gpu1.JPEG" width="160"/>
  <img src="screenshots/network1.JPEG" width="160"/>
  <img src="screenshots/storage1.JPEG" width="160"/>
  <img src="screenshots/processes1.JPEG" width="160"/>
  <img src="screenshots/graph1.JPEG" width="160"/>
  <img src="screenshots/graph2.JPEG" width="160"/>
</p>

---

## Important Notes

- This project is **developed and tested on Fedora (KDE 43)**.
- It is now designed to work across **most Linux distributions**.
- GPU detection supports:
  - NVIDIA (`nvidia-smi`)
  - AMD (DRM/sysfs)
  - Intel (DRM/sysfs / intel tools)
- Behavior may vary depending on drivers and system configuration.

## Project Structure

```text
ArduinoUniversalSystemMonitor/
├── UniversalArduinoMonitor.py              # Main desktop monitor sender
├── monitor_config.default.json             # Tracked baseline monitor settings
├── monitor_config.json                     # Shared runtime config template
├── requirements.txt                        # Python dependencies
├── install.sh                              # Main Linux installer
├── arduino_install.sh                      # Small entrypoint wrapper for Arduino flashing
├── install_arduinos.sh                     # Arduino CLI setup + board flashing workflow
├── update.sh                               # Pull latest changes and refresh dependencies
├── uninstall_monitor.sh                    # Remove service and installed files
├── UniversalMonitorControlCenter.sh        # Root-level Java GUI launcher/build helper
├── install_control_center_desktop.sh       # Optional Linux app-menu launcher installer
├── R3_MonitorScreen28/              # Arduino UNO R3 2.8" TFT sketch
├── R3_MonitorScreen35/              # Placeholder for future Arduino UNO R3 3.5" TFT sketch
├── R3_MEGA_MonitorScreen35/         # Arduino Mega 2560 R3 3.5" TFT sketch
├── R4_WIFI35/                       # Canonical Arduino UNO R4 WiFi USB + Wi-Fi 3.5" TFT sketch
├── backups/                         # Backup copies of the previous desktop monitor/config
├── debug_tools/FakeArduinoDisplay/         # Java fake display + Control Center
├── legacy/Windows/                         # Older Windows-side monitor files
└── screenshots/                            # README/device preview images
```

For now, keeping the install/update/uninstall scripts in the repository root is the most practical option because the README commands stay short and the scripts can reliably call each other with their current relative paths. If the helper script count keeps growing later, moving them into a `scripts/` folder would make sense, but it would be a cleanup/refactor rather than a functional improvement.

---

## Requirements

- Python 3.8+
- Java OpenJDK 21 (build) + OpenJDK 25 (runtime for the Control Center launcher; auto-installed on supported Linux distros)
- Arduino IDE 1.8.19+
- **Option A:** Arduino UNO R4 (USB-C) + Arduino 3.5" TFT Display, or Arduino Mega 2560 R3 + 3.5" TFT Display
- **Option B:** Arduino UNO R3 (USB-B) + 2.8" TFT UNO R3 Display

### Windows Only
- LibreHardwareMonitor

---

## Changelog

```text
4.0  - Initial
4.1  - Fixed README
4.2  - Enlarged text for OS and hostname, fixed Linux issues
4.3  - Fixed OS naming
4.4  - Faster polling and improved Windows logic
4.5  - Re-added uptime
5.0  - Major fixes, GPU page fully fixed
5.1  - Timing tuning for Windows 7 & 10
5.2  - Added R3 support
5.3  - Backup versions + README fixes
5.4  - Laptop support (no charging % yet)
5.5  - Dropped Windows XP support
6.0  - Stable release, Linux packages separated
7.0  - Added Fedora KDE 43 support
7.1  - Reorganized folder structure, separated Windows components
7.2  - Made Linux universal, GPU detection for NVIDIA/AMD/Intel, reduced Fedora-specific dependencies
7.3  - Added basic Java GUI debugger/emulator and config-driven debug output support for the Java fake display
7.4  - Added install.sh improvements, config auto-generation, standardized install path (~/ArduinoUniversalSystemMonitor), and update.sh support
7.5  - Re-added 2.8" TFT UNO R3 support, updated documentation, and clarified hardware requirements/options
7.6  - Added Arduino CLI flashing workflow, automatic board/core/library setup, and install_arduinos.sh support
7.7  - Added post-install Arduino flashing prompt, arduino_install.sh entrypoint, and updated install script documentation
7.8  - Fixed Installer for Ubuntu/Mint machines due to Python restrictions
7.9  - Reverted the Linux service back to the system Python install path and documented the Ubuntu/Mint working-directory workaround
8.0  - Added Java Control Center with install/update/flash tooling, service controls, sudo prompt support, and live fake-port preview integration
8.1  - Documented exact non-IntelliJ Java Control Center launch steps and Gradle fallback workflow
8.2  - Refreshed README project layout and clarified the root-level install/update/uninstall helper scripts
8.3  - Added a root-level Java Control Center launcher, runnable fat-jar build, and optional desktop-menu installer
8.4  - Made Control Center updates rebuild/relaunch the Java app and added visible in-app version display
8.5  - Refined the Control Center layout/theme, added custom-sketch selection status, cleaned 3.5" RAM/OS labels, normalized Fedora OS naming, and improved Mega touchscreen page switching
8.6  - Removed the README release-notes section, updated project/control-center version metadata to 8.6, and refreshed the README modified date
8.7  - Switched the default Linux monitor/config to the new R4 Wi-Fi build, added USB-only/Wi-Fi mode controls, and made the R4 Wi-Fi sketch the default flasher target
8.8  - Made USB take priority over Wi-Fi for the R4 monitor path, cleaned up the R4 header/home layout, and reduced duplicate USB+Wi-Fi updates
8.9  - Added committed Wi-Fi config templates and a Control Center button for saving R4 Wi-Fi credentials into wifi_config.h before flashing
8.10  - Cleaned up .gitignore for tracked Wi-Fi templates and added a Control Center warning not to commit real Wi-Fi credentials
8.11  - Switched real R4 Wi-Fi credentials to git-ignored wifi_config.local.h files so test pushes do not include passwords
8.12  - Added Control Center UNO R3 mode selection, moved the visible display toggles into the action area, and added monitor connection port settings directly in the Control Center
8.13  - Removed duplicate nested R4 Wi-Fi monitor/sketch copies so the repo now uses one root Python monitor, one root monitor_config.json, and one canonical R4_WIFI35 sketch folder
9.0 beta  - Added layered default/shared/local monitor config support for per-computer port overrides, refreshed README screenshots with the newer captures, and bumped the project/control-center release branding to version 9 beta
```

---

## TODO

- Bundle Python, pip, and dependencies

---

## Confirmed Working Systems

- Windows 11 Pro  
- Windows 10 LTSC  
- Windows 7 Ultimate  
- Linux Mint 22.3  
- Fedora KDE 43  

---

# Installation

## Linux Setup (Automatic - Recommended)

First, make sure `git` is installed:

### Ubuntu / Linux Mint / Debian

```bash
sudo apt update
sudo apt install -y git
```

### Fedora

```bash
sudo dnf install -y git
```

### Arch

```bash
sudo pacman -Sy --noconfirm git
```

### Linux Automatic Install Snippet

```bash
git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor
chmod +x install.sh
./install.sh
```

or

```bash
git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git && cd ArduinoUniversalSystemMonitor && chmod +x install.sh && ./install.sh
```

During `./install.sh`, the script installs system packages, Python dependencies, config/service files, and then prompts:

`Would you like to install and flash your Arduino(s) now? [y/N]:`

- Typing `y` runs `./arduino_install.sh` (which calls `install_arduinos.sh`).
- The Arduino installer ensures `arduino-cli` is installed, installs required board cores (`arduino:avr`, `arduino:renesas_uno`), installs required libraries/dependencies (`MCUFRIEND_kbv`, `Adafruit GFX Library`, `TouchScreen`, `DIYables TFT Touch Shield`), compiles each sketch once per board family, and flashes every supported connected board it detects.
- With a single detected Arduino UNO R3, the flasher now defaults straight to the **2.8"** sketch unless you override it with `UNO_R3_SCREEN_SIZE=35`. When **multiple** Arduino UNO R3 boards are detected, the flasher asks which R3 sketch to use for that flashing run, because USB detection alone cannot distinguish whether the attached TFT shield is **2.8"** or **3.5"**. You can also preselect either screen size non-interactively with `UNO_R3_SCREEN_SIZE=28 ./install_arduinos.sh` or `UNO_R3_SCREEN_SIZE=35 ./install_arduinos.sh`. The Java Control Center follows the same rule: it defaults a single detected UNO R3 to **2.8"** and only shows a GUI choice when multiple UNO R3 boards are connected.
- The Java Control Center now also includes an `Upload Custom Sketch` action so you can compile/upload your own sketch folder or `.ino` file to a selected connected board from the GUI.
- The Java Control Center can optionally remember your sudo password in a local `.control_center_sudo_password` file in the repo root; that file is ignored by Git so it stays local to your machine.
- On Fedora and other distros that gate `/dev/ttyACM*` access more aggressively, the flasher now automatically retries the upload with `sudo` if the Arduino UNO R4 WiFi reset/upload step reports `Permission denied` or a failed 1200-bps touch reset, while preserving the original user's Arduino CLI home/config paths so installed board cores remain visible during the retry.
- Built-in board flashing now also does a short automatic retry for transient upload failures before giving up on a board, which helps when several boards are connected at the same time.
- The Linux installer now runs the service with `/usr/bin/python3` again and installs Python dependencies with `pip` instead of pointing systemd at a project `.venv`.
- On Ubuntu / Linux Mint, the installer automatically uses `pip --break-system-packages` when available so the required Python packages can still be installed on PEP 668 managed systems.
- Install flow order is: dependency setup -> Arduino flash prompt -> systemd service start.

Default/shared `monitor_config.default.json` + `monitor_config.json` files are created during installation, and a git-ignored `monitor_config.local.json` is also created for per-computer overrides such as serial port and Wi-Fi port changes.

For the Wi-Fi TCP port specifically, the effective precedence is:

1. `ARDUINO_MONITOR_WIFI_PORT` environment override at monitor startup
2. `monitor_config.local.json` machine-local override
3. Shared JSON config (`monitor_config.json`, then `monitor_config.default.json`)
4. Flashed sketch header settings from `R4_WIFI35/wifi_config.local.h` or `R4_WIFI35/wifi_config.h`

If you expect a port change to take effect immediately, keep the machine-local JSON, any environment override, and the flashed sketch header settings in agreement. Changing only one layer can leave the Python monitor and the flashed Arduino sketch listening on different TCP ports until you re-save/reflash/restart the matching pieces.

`install.sh` also writes the systemd service file with your real detected username and install path automatically. The `YOUR_USERNAME` examples below are only for manual editing/troubleshooting.

### Ubuntu / Linux Mint service workaround

If your service still refuses to start on Ubuntu or Linux Mint after installation, re-open the systemd unit and make sure it uses the system Python plus the repo as the working directory:

```bash
sudo nano /etc/systemd/system/arduino-monitor.service
```

```ini
[Service]
ExecStart=/usr/bin/python3 /home/YOUR_USERNAME/ArduinoUniversalSystemMonitor/UniversalArduinoMonitor.py
WorkingDirectory=/home/YOUR_USERNAME/ArduinoUniversalSystemMonitor
```

Then reload systemd and restart the service:

```bash
sudo systemctl daemon-reload
sudo systemctl restart arduino-monitor.service
```

This bypasses the Ubuntu/Mint `.venv` startup issue by running the monitor from the normal project folder instead of the virtual environment.

If you used `./install.sh`, you do **not** need to hand-edit `YOUR_USERNAME` in the service file unless you are manually repairing a broken unit.

---

## Java Control Center Launcher (Root-Level)

You can now launch the Java Control Center straight from the repository root without remembering the Gradle path:

```bash
chmod +x UniversalMonitorControlCenter.sh
./UniversalMonitorControlCenter.sh
```

If `DISPLAY` is missing, the launcher now tries to infer it from the local desktop session before starting Swing. If no GUI session can be found, it exits with a clear error instead of a Java stack trace.

What the root launcher does:

- Checks for OpenJDK 21 (build) and OpenJDK 25 (runtime) the first time you launch it
- Automatically installs both JDKs on supported Linux distros (`apt`, `dnf`, `pacman`, `zypper`) when they are missing
- Builds `debug_tools/FakeArduinoDisplay/build/libs/UniversalMonitorControlCenter.jar` automatically when Java files/resources changed
- Launches the GUI with the detected OpenJDK 25 runtime instead of whichever `java` happens to be first in `PATH`

So for normal testing, you can stay in the repo root and just run `./UniversalMonitorControlCenter.sh`.

Inside the Control Center, **Update from GitHub** still pulls the newest repo files from GitHub first, then rebuilds the Java app and relaunches it.

### Optional Linux app-menu launcher

If you want a clickable launcher in KDE/GNOME/etc, run:

```bash
chmod +x install_control_center_desktop.sh
./install_control_center_desktop.sh
```

That installs a `.desktop` entry into `~/.local/share/applications`, so the Control Center shows up in the desktop app menu as **Universal Monitor Control Center**.

## Arduino Flashing Script (Standalone)

If you skip the prompt or want to reflash later:

```bash
cd ~/ArduinoUniversalSystemMonitor
chmod +x arduino_install.sh install_arduinos.sh
./arduino_install.sh
```

`arduino_install.sh` is just a stable wrapper entrypoint. The actual flashing logic lives in `install_arduinos.sh`, which installs `arduino-cli` if needed, checks cores/libraries, stops the monitor service before flashing, and restarts it afterward.

---

## Linux Setup (Manual)

### Create Service

```bash
sudo nano /etc/systemd/system/arduino-monitor.service
```

```ini
[Unit]
Description=Arduino System Monitor
After=network.target

[Service]
ExecStart=/usr/bin/python3 /home/YOUR_USERNAME/ArduinoUniversalSystemMonitor/UniversalArduinoMonitor.py
WorkingDirectory=/home/YOUR_USERNAME/ArduinoUniversalSystemMonitor
Restart=always
User=YOUR_USERNAME

[Install]
WantedBy=multi-user.target
```

---

### Enable

```bash
sudo systemctl daemon-reload
sudo systemctl enable arduino-monitor
sudo systemctl start arduino-monitor
```

---

### Serial Permissions (IMPORTANT)

```bash
sudo usermod -aG dialout $USER
```

Log out and back in.

---
# Running the Program

## Linux

```bash
sudo systemctl start arduino-monitor
sudo systemctl stop arduino-monitor
sudo systemctl restart arduino-monitor
sudo systemctl status arduino-monitor
```
## Updating (Linux)

```bash
cd ~/ArduinoUniversalSystemMonitor
chmod +x update.sh
./update.sh
```

This will:

- Pull the latest version from GitHub
- Ensure the local `.venv` exists for update-time package management
- Update Python dependencies
- Re-apply executable bits to the helper scripts
- Restart the system service

---

## Uninstall (Linux)

To completely remove the monitor, service, and installed files:

```bash
cd ~/ArduinoUniversalSystemMonitor
chmod +x uninstall_monitor.sh
./uninstall_monitor.sh
```

This will:

- Stop and disable the systemd service
- Remove the service file
- Remove installed monitor files and directories
- Clean up previous install locations

After uninstalling, you can reinstall cleanly using:

`./install.sh`

---

# Troubleshooting

### Check Arduino Port

```bash
ls /dev/ttyACM* /dev/ttyUSB*
```

---

### Detect Plug-In

```bash
dmesg -w
```

---

### Verify Permissions

```bash
ls -l /dev/ttyACM0
```

---

### Check Service

```bash
sudo systemctl status arduino-monitor
```

## Debug Tools

This repository also includes a Java-based Fake Arduino Display tool for testing the serial output without requiring a physical Arduino.

Location:
`debug_tools/FakeArduinoDisplay`

To launch the Java Control Center without IntelliJ:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x run_control_center.sh gradlew
./run_control_center.sh
```

If `./gradlew run` crashes on your system, use the fallback launcher instead:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x gradlew
./gradlew installDist
./build/install/FakeArduinoDisplay/bin/FakeArduinoDisplay
```

For the full Java instructions, including JDK setup and launching only the fake display window, see:
`debug_tools/FakeArduinoDisplay/README.md`

Use it with a virtual serial pair such as:
`/tmp/fakearduino_in` and `/tmp/fakearduino_out`

Debug mirror is configured from the merged `monitor_config.default.json` / `monitor_config.json` / `monitor_config.local.json` settings, with local overrides taking precedence:

```json
{
  "debug_enabled": false,
  "debug_port": "/tmp/fakearduino_in"
}
```

The main `UniversalArduinoMonitor.py` still sends the original Arduino positional payload, and when debug mode is enabled it also emits the Java-compatible `KEY:VALUE` stream on `debug_port`.
