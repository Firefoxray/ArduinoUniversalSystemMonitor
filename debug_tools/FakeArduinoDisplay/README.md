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
- When **Update and Restart GUI** is used, pull the newest repo files from GitHub as the repo owner, rebuild the Java jar/distribution, and relaunch the Control Center automatically
- When **Install Monitor + Desktop Entry** is used, rerun `install.sh` without pulling from GitHub, clear machine-local/generated files first, install/refresh the Linux desktop launcher, and relaunch the Control Center so the latest icon is picked up immediately
- Manage Linux service state (`arduino-monitor.service`):
  - Service On
  - Service Off
  - Service Restart
  - Service Status
  - Green/Red status indicator
- Manage Python debug mirror mode in `config/monitor_config.local.json` (with shared/default config fallback):
  - Enable Debug Mode (writes config + restarts service)
  - Disable Debug Mode (writes config + restarts service)
  - Refresh Debug Status
  - Green/Red status indicator
- Manage virtual serial ports with `socat`
  - Start fake ports using configurable input/output paths
  - Stop fake ports from the UI
- Live preview feed in the embedded Arduino panel from the configured output port
  - Auto-sends a one-time probe packet after fake ports start (helps verify wiring)
  - Auto-updates `config/monitor_config.local.json` to use debug mirror on the fake input path
- Open the existing debug display app with one click
- Stream command output logs in-app
- Show the Java Control Center version directly in the window so it matches the packaged project version

------------------------------------------------------------------------

## sudo / Admin behavior

Most Linux app management and service actions need root privileges.

You can either:

1. Run the Java app as root, or
2. Enter your sudo password in the Control Center password field.

If the password field is empty, the app prompts when needed.

------------------------------------------------------------------------

## Fastest Launch From Repo Root

From the repository root, use the new helper:

```bash
chmod +x UniversalMonitorControlCenter.sh
./UniversalMonitorControlCenter.sh
```

This root-level launcher:

1. Detects a working JDK.
2. Builds `build/libs/UniversalMonitorControlCenter.jar` automatically when Java files/resources changed.
3. Launches the GUI with `java -jar`.

If you want a desktop app-menu entry on Linux, run this from the repo root too:

```bash
chmod +x install_control_center_desktop.sh
./install_control_center_desktop.sh
```

That creates a per-user `.desktop` launcher in `~/.local/share/applications`.

------------------------------------------------------------------------

## Run Without IntelliJ (Terminal)

Use these exact steps from the repository root if you want to launch the Control Center without IntelliJ:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x run_control_center.sh gradlew
./run_control_center.sh
```

What this does:

1. Moves into the Java project folder.
2. Makes sure the launcher and Gradle wrapper are executable.
3. Auto-installs OpenJDK 21 for building and OpenJDK 25 for runtime on supported Linux package managers when they are missing.
4. Auto-selects the build JDK and runtime JDK separately so the launcher does not accidentally mix Java versions.
5. Runs `./gradlew run`, which launches `UniversalMonitorControlCenter`.

If you want to install them yourself instead of using the automatic bootstrapper:

```bash
# Fedora
sudo dnf install -y java-21-openjdk-devel java-25-openjdk-devel

# Debian/Ubuntu/Linux Mint
sudo apt install -y openjdk-21-jdk openjdk-25-jdk

# Arch
sudo pacman -Sy --noconfirm jdk21-openjdk jdk-openjdk
```

### Direct Gradle command

If you do not want to use the helper script, run the Gradle wrapper directly:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x gradlew
./gradlew run
```

### Gradle fallback if `run` crashes

If `./gradlew run` fails on your machine, build the app first and then use the generated launcher or the runnable jar:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x gradlew
./gradlew installDist fatJar
./build/install/UniversalMonitorControlCenter/bin/UniversalMonitorControlCenter
# or
java -jar build/libs/UniversalMonitorControlCenter.jar
```

### Launch only the fake display window

If you only want the serial fake display instead of the full Control Center:

```bash
cd ~/ArduinoUniversalSystemMonitor/debug_tools/FakeArduinoDisplay
chmod +x gradlew
./gradlew installDist
java -cp "build/install/FakeArduinoDisplay/lib/*" JavaSerialFakeDisplay
```

### Important note

This is a desktop Swing application, so it must be started inside a normal graphical Linux session. If you launch it from a headless shell with no X11/Wayland desktop available, Java will throw a `HeadlessException` and the window will not open.

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
