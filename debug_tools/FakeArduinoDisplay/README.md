# Fake Arduino Display + Control Center (Java)

A desktop Java application suite for the Universal Arduino System Monitor.

It includes:

- **UniversalMonitorControlCenter**: launcher/control panel for install, uninstall, update, flashing, Linux service control, fake-port management, and opening the debug display.
- **JavaSerialFakeDisplay**: full serial debugger + Arduino-style fake TFT window.

------------------------------------------------------------------------

## What the Control Center Can Do

- Run Linux scripts from the repo:
  - `install.sh`
  - `uninstall_monitor.sh`
  - `update.sh`
  - `arduino_install.sh`
- Manage Linux service state (`arduino-monitor.service`):
  - Service On
  - Service Off
  - Service Restart
  - Service Status
  - Green/Red status indicator
- Manage Python debug mirror mode in `monitor_config.json`:
  - Enable Debug Mode (writes config + restarts service)
  - Disable Debug Mode (writes config + restarts service)
  - Refresh Debug Status
  - Green/Red status indicator
- Manage virtual serial ports with `socat`
  - Start fake ports using configurable input/output paths
  - Stop fake ports from the UI
- Live preview feed in the embedded Arduino panel from the configured output port
  - Auto-sends a one-time probe packet after fake ports start (helps verify wiring)
  - Auto-updates `monitor_config.json` to use debug mirror on the fake input path
- Open the existing debug display app with one click
- Stream command output logs in-app

------------------------------------------------------------------------

## sudo / Admin behavior

Most Linux app management and service actions need root privileges.

You can either:

1. Run the Java app as root, or
2. Enter your sudo password in the Control Center password field.

If the password field is empty, the app prompts when needed.

------------------------------------------------------------------------

## Run Without IntelliJ (Terminal)

From `debug_tools/FakeArduinoDisplay`:

```bash
./run_control_center.sh
```

This launcher auto-selects a JDK that includes `javac` (helps avoid the Gradle/JRE-only error).

If no JDK is found, install one:

```bash
# Fedora
sudo dnf install -y java-21-openjdk-devel

# Debian/Ubuntu
sudo apt install -y openjdk-21-jdk
```

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

For preview to update:

- Python monitor debug output should write to **input** (`/tmp/fakearduino_in`)
- Control Center preview and Java debug tool should read from **output** (`/tmp/fakearduino_out`)

------------------------------------------------------------------------

## Entry Point

Gradle `application` defaults to:

- `UniversalMonitorControlCenter`

So `./gradlew run` launches the Control Center.
