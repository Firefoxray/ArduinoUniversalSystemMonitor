# UASM v13.0 Qt migration

**Status: Qt development preview; NOT the v13.0 stable release yet.**
The main runtime and Arduino firmware remain on v12.1 while migration happens.

## Safest preview: separate Git worktree

Keep the v12.1 checkout and its running systemd service untouched:

~~~bash
cd ~/ArduinoUniversalSystemMonitor
git fetch origin
git worktree add -b v13-qt-preview ../ArduinoUniversalSystemMonitor-v13 origin/feature/v13-qt-control-center-foundation
cd ../ArduinoUniversalSystemMonitor-v13
sudo dnf install -y python3-pyside6 python3-psutil python3-pyserial
./uasm gui
# The standalone monitoring window:
./uasm dashboard
~~~

This preview checkout has its **own** ignored local config and does not change
the service running from the original v12.1 checkout. Do not assume that saving
Wi-Fi preferences in the worktree updates the live sender. Do not run the v13
updater/installer against your production service while testing the GUI.

## Run the preview on a graphical Linux desktop

~~~bash
cd ~/ArduinoUniversalSystemMonitor
# "uasm update" already creates .venv on existing installations.
# Fedora 44/45 (recommended distro packages):
sudo dnf install -y python3-pyside6 python3-psutil python3-pyserial
# Other distros may instead use:
# ./.venv/bin/python3 -m pip install -r requirements-qt.txt
./UniversalMonitorQt.sh
./UniversalMonitorQt.sh --dashboard-only
# The unified CLI also supports "uasm gui" / "uasm dashboard".
~~~

Keep the existing Java application installed during feature migration. Do not
install Qt on headless Fedora/Debian servers; their Python service and CLI do
not import the new GUI.

## Second pass — October 2026

- New graphite/slate theme with transparent labels (fixes dark text rectangles)
- Compact dashboard with CPU/GPU/RAM/VRAM, CPU/GPU temperatures, network, uptime,
  process table, and root storage; CPU/GPU graph with separate muted lines
- AMD Radeon GPU monitoring through amdgpu sysfs (works with Fedora's AMD stack)
  plus NVIDIA fallback using nvidia-smi; unsupported GPU fields display N/A
- Actual UNO R4 WiFi UDP discovery on port 5001 using firmware `UAM_DISCOVER`
  and `UAM_HERE|ip|port|name|target_host|target_hostname` response format.
  Scans run off-thread; selection fills fixed host/port, but does NOT save or
  change pairing without your explicit Save click.
- 'Found via UDP' != 'TCP endpoint reachable' != 'actively receiving stats'.
  True live sender transport status and firmware pairing still need integration.
- Telemetry probes run in a worker thread, and CPU/GPU/process readings are shared
  between the main app and the independent pop-out window.

For users testing in a separate Git worktree: the config shown belongs to the
preview checkout, not necessarily the original running systemd sender. Automatic
UDP discovery runs even when that preview has discovery disabled by default;
scanning is a read-only troubleshooting action, not a sender preference change.

## First working vertical slice

- Modern dark Qt Widgets Control Center with sidebar and native pop-out monitor
- One shared, lightweight psutil sampler (CPU/RAM/network/temperature/CPU history)
- Service status, filtered USB candidates, local mount capacity
- Local transport/Wi-Fi configuration editing and TCP endpoint reachability test
- Existing UASM doctor and systemd journal logs, launched without blocking the GUI
- Safe legacy Java launcher for flashing, TFT Arduino previews and page profiles

Wi-Fi config is written ONLY to the ignored
config/monitor_config.local.json with atomic replacement. Existing Wi-Fi
firmware credentials, shared config, page profiles and Arduino sketches are not
modified. Restart the sender service manually after changing connection settings.

## Windows native preview and headless Linux testing

Clone the feature branch into a separate Windows checkout, then run from PowerShell:

~~~powershell
py -3.14 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-qt.txt
.\.venv\Scripts\python.exe -m uasm_qt
# Independent dashboard:
.\.venv\Scripts\python.exe -m uasm_qt --dashboard-only
~~~

This is a development preview with an opt-in Windows USB sender, not an installed Windows service. CPU, RAM, storage, network,
USB enumeration and R4 UDP discovery use cross-platform libraries. AMD GPU
and CPU temperature support on Windows still need native collectors; missing
data is shown as N/A. Windows does not invoke systemd, journalctl or the Linux
Java launcher from the GUI. Local configuration stays in the preview checkout.

On an existing Linux headless machine with psutil and pyserial installed,
run the following from the preview worktree (no Qt installation required):

~~~bash
python3 -m unittest discover -s tests -p 'test_qt_*.py' -v
python3 -m uasm_qt.probe
# Optional explicit Wi-Fi discovery:
python3 -m uasm_qt.probe --wifi
~~~

The probe reads telemetry and enumerates ports without opening them; the
optional discovery sends only a UDP query. It does not stop the production
sender, flash a board, install a service, or modify configuration.

## Windows USB sender and optional sensors (experimental)

On Windows, close any older PowerShell sender, open Overview, select the
Arduino USB port and use Start / Stop / Restart. Logs shows the managed
sender output. Closing Control Center stops its own sender only.
The same sender can also run independently in PowerShell:

~~~powershell
.\.venv\Scripts\python.exe -m uasm_qt.sender --serial COM3
~~~

Only the specified detected Arduino USB port is opened at 115200 baud. This
sends the existing 73-field v12.1 monitor packet, not firmware or pairing
commands. Stop with Ctrl+C. Successful serial writes are not display ACKs.
Battery, GPU clock and unsupported numeric fields retain legacy placeholder
values; this is not full feature parity. Wi-Fi sending remains unimplemented.
The preview does not install a Windows service or manage unrelated processes.

For Windows temperatures and AMD GPU metrics, run LibreHardwareMonitor and
enable its web server on loopback only (127.0.0.1, port 8085). The preview reads
its local data.json endpoint; no driver or web server is installed by UASM.
If loopback binding is unavailable, leave the server disabled rather than
exposing hardware control to the LAN. N/A remains valid without sensors.
Set UASM_LHM_PORT in the launching shell for a different local port.

## Disk0 and Disk1 assignments

Use Storage → choose Disk0 and Disk1 → Save Disk Assignments in Qt. The
preview sender reads these machine-local mount selections at startup. On
Windows, Save assignments & restart USB sender applies them to an already
running GUI-owned sender, or saves them for the next start.
Disk0 defaults to the system volume and Disk1 is unassigned until selected.
Unavailable selections remain saved, rather than falling back to another drive.
Old firmware represents missing/unassigned disk percentages as zero.
This does not change legacy Linux storage target IDs or the running v12.1 service.

## Release packaging backlog

Add a Windows launcher/shortcut with the UASM icon, eventually a packaged
executable or installer and separate background service/autostart support.
Keep Linux desktop launcher integration and service controls as separate tasks.

## Remaining v13 migration work

Native Arduino TFT preview, page profiles, firmware flashing, Wi-Fi
discovery/pairing/credential provisioning, qBittorrent controls, remote SSH
profiles, gaming/MangoHud and storage I/O graphs. Keep the legacy Java windows
available until all those features and the pop-out tabs are migrated.

## Tests

~~~bash
python3 -m unittest discover -s tests -p 'test_qt_*.py'
QT_QPA_PLATFORM=offscreen ./.venv/bin/python3 -m uasm_qt --smoke
QT_QPA_PLATFORM=offscreen ./.venv/bin/python3 -m uasm_qt --dashboard-only --smoke
~~~
