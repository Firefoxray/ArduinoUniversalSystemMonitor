#!/usr/bin/env bash
set -Eeuo pipefail

echo "==== Ray Co Arduino Monitor Uninstall ===="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/project_paths.sh"
SERVICE_NAME="arduino-monitor.service"
OLD_HOME_SCRIPT="$HOME/UniversalArduinoMonitor.py"
OLD_HOME_DEBUG="$HOME/UniversalArduinoMonitor_debugmirror.py"
OLD_HOME_CONFIG="$HOME/monitor_config.json"

OLD_INSTALL_DIR="$HOME/ArduinoMonitor"
NEW_INSTALL_DIR="$HOME/ArduinoUniversalSystemMonitor"
CURRENT_INSTALL_DIR="$(resolve_project_dir "$SCRIPT_DIR" "${BASH_SOURCE[0]}")"

echo "[1/5] Stopping and disabling service if it exists..."
if systemctl list-unit-files | grep -q "^$SERVICE_NAME"; then
    sudo systemctl stop "$SERVICE_NAME" 2>/dev/null || true
    sudo systemctl disable "$SERVICE_NAME" 2>/dev/null || true
fi

echo "[2/5] Removing systemd service file..."
if [ -f "/etc/systemd/system/$SERVICE_NAME" ]; then
    sudo rm -f "/etc/systemd/system/$SERVICE_NAME"
    sudo systemctl daemon-reload
fi

echo "[3/5] Removing old loose files in home directory..."
rm -f "$OLD_HOME_SCRIPT"
rm -f "$OLD_HOME_DEBUG"
rm -f "$OLD_HOME_CONFIG"

echo "[4/5] Removing old install directories..."
rm -rf "$OLD_INSTALL_DIR"
rm -rf "$NEW_INSTALL_DIR"
if [[ "$CURRENT_INSTALL_DIR" != "$OLD_INSTALL_DIR" && "$CURRENT_INSTALL_DIR" != "$NEW_INSTALL_DIR" ]]; then
    rm -rf "$CURRENT_INSTALL_DIR"
fi

echo "[5/5] Uninstall complete."
echo
echo "Removed:"
echo "- Service: $SERVICE_NAME"
echo "- Old home-folder monitor files"
echo "- $OLD_INSTALL_DIR"
echo "- $NEW_INSTALL_DIR"
if [[ "$CURRENT_INSTALL_DIR" != "$OLD_INSTALL_DIR" && "$CURRENT_INSTALL_DIR" != "$NEW_INSTALL_DIR" ]]; then
    echo "- $CURRENT_INSTALL_DIR"
fi
echo
echo "You can now reinstall cleanly with:"
echo "  ./install.sh"
