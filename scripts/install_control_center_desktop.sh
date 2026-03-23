#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/project_paths.sh"

INSTALL_USER="${SUDO_USER:-${USER:-$(id -un)}}"
INSTALL_HOME="$(getent passwd "$INSTALL_USER" | cut -d: -f6)"
if [[ -z "$INSTALL_HOME" ]]; then
    INSTALL_HOME="$HOME"
fi

DEFAULT_PROJECT_DIR="$(resolve_project_dir "$SCRIPT_DIR/.." "${BASH_SOURCE[0]}")"
PROJECT_DIR="$(resolve_project_dir "${PROJECT_DIR:-$DEFAULT_PROJECT_DIR}" "${BASH_SOURCE[0]}")"
DESKTOP_DIR="${XDG_DATA_HOME:-$INSTALL_HOME/.local/share}/applications"
DESKTOP_FILE="$DESKTOP_DIR/universal-monitor-control-center.desktop"
LAUNCHER_PATH="$PROJECT_DIR/UniversalMonitorControlCenter.sh"

pick_icon_path() {
    local candidate=""
    for candidate in \
        "$PROJECT_DIR/screenshots/arduinoPreview1.png" \
        "$PROJECT_DIR/screenshots/arduinoPreview.png" \
        "$PROJECT_DIR/screenshots/home1.JPEG"; do
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

ICON_PATH="$(pick_icon_path || true)"
if [[ ! -x "$LAUNCHER_PATH" ]]; then
    chmod +x "$LAUNCHER_PATH"
fi

if [[ ! -f "$LAUNCHER_PATH" ]]; then
    echo "Error: Control Center launcher not found at $LAUNCHER_PATH" >&2
    exit 1
fi

if [[ -z "$ICON_PATH" || ! -f "$ICON_PATH" ]]; then
    echo "Error: Control Center icon not found in $PROJECT_DIR/screenshots" >&2
    exit 1
fi

mkdir -p "$DESKTOP_DIR"

cat > "$DESKTOP_FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=Universal Monitor Control Center
Comment=Launch the Java GUI control center for ArduinoUniversalSystemMonitor
Exec=$LAUNCHER_PATH
Path=$PROJECT_DIR
Terminal=false
Categories=Utility;System;
Icon=$ICON_PATH
StartupNotify=true
StartupWMClass=UniversalMonitorControlCenter
Keywords=Arduino;Monitor;Control Center;System Monitor;
DESKTOP

chmod 644 "$DESKTOP_FILE"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
fi

printf 'Desktop launcher installed: %s\n' "$DESKTOP_FILE"
printf 'Launcher target: %s\n' "$LAUNCHER_PATH"
printf 'Icon path: %s\n' "$ICON_PATH"
printf "You can now launch 'Universal Monitor Control Center' from your app menu on Linux desktops.\n"
