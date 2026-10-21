# UASM v13.0 Qt migration

**Status: Qt development preview; NOT the v13.0 stable release yet.**
The main runtime and Arduino firmware remain on v12.1 while migration happens.

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

## Remaining v13 migration work

Native Arduino TFT preview, page profiles, firmware flashing, Wi-Fi
discovery/pairing/credential provisioning, qBittorrent controls, remote SSH
profiles, gaming/MangoHud and storage I/O graphs. Keep the legacy Java windows
available until all those features and the pop-out tabs are migrated.

## Tests

~~~bash
python3 -m unittest discover -s tests -p 'test_qt_backend.py'
QT_QPA_PLATFORM=offscreen ./.venv/bin/python3 -m uasm_qt --smoke
QT_QPA_PLATFORM=offscreen ./.venv/bin/python3 -m uasm_qt --dashboard-only --smoke
~~~
