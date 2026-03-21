#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
DESKTOP_FILE="$DESKTOP_DIR/universal-monitor-control-center.desktop"
ICON_PATH="$REPO_ROOT/screenshots/arduinoPreview1.png"

mkdir -p "$DESKTOP_DIR"

cat > "$DESKTOP_FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=Universal Monitor Control Center
Comment=Launch the Java GUI control center for ArduinoUniversalSystemMonitor
Exec=$REPO_ROOT/UniversalMonitorControlCenter.sh
Path=$REPO_ROOT
Terminal=false
Categories=Utility;System;
Icon=$ICON_PATH
StartupNotify=true
DESKTOP

chmod +x "$DESKTOP_FILE"

echo "Desktop launcher installed: $DESKTOP_FILE"
echo "You can now launch 'Universal Monitor Control Center' from your app menu on Linux desktops."
