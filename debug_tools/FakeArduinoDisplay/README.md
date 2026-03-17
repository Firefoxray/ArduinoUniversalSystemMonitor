# Fake Arduino Display + Control Center (Java)

A desktop Java application suite for the Universal Arduino System Monitor.

It now includes:

- **UniversalMonitorControlCenter** (new): a launcher/control panel for install, uninstall, update, flashing, fake-port management, and opening the debug display.
- **JavaSerialFakeDisplay** (existing): the full serial debugger + Arduino-style fake TFT window.

------------------------------------------------------------------------

## What the Control Center Can Do

- Run Linux scripts from the repo:
  - `install.sh`
  - `uninstall_monitor.sh`
  - `update.sh`
  - `arduino_install.sh`
- Manage virtual serial ports with `socat`
  - Start fake ports using configurable input/output paths
  - Stop fake ports from the UI
- Open the existing debug display app with one click
- Show a built-in Arduino-style preview panel
- Stream command output logs in-app

------------------------------------------------------------------------

## IntelliJ Workflow

1. Open `debug_tools/FakeArduinoDisplay` in IntelliJ.
2. Let Gradle import dependencies.
3. Run:
   - `UniversalMonitorControlCenter` (recommended launcher)
   - or `JavaSerialFakeDisplay` directly if you only need serial debugging.

The project intentionally stays as plain Java source so it is easy to edit and extend in IntelliJ.

------------------------------------------------------------------------

## Virtual Port Notes

The fake-port helper uses `socat`. On Linux:

```bash
sudo apt install -y socat
# or sudo dnf install -y socat
```

Default fake ports used by the UI:

- Input: `/tmp/fakearduino_in`
- Output: `/tmp/fakearduino_out`

------------------------------------------------------------------------

## Existing Debug Display

`JavaSerialFakeDisplay` still supports:

- Live serial parsing (`KEY:VALUE|KEY:VALUE` packets)
- Multi-page Arduino-style display preview
- Raw serial logs and parsed field view
- Click preview area to switch pages

------------------------------------------------------------------------

## Entry Point

Gradle `application` now defaults to:

- `UniversalMonitorControlCenter`

So `./gradlew run` launches the Control Center.
