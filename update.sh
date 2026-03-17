#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-$HOME/ArduinoUniversalSystemMonitor}"
SERVICE_NAME="${SERVICE_NAME:-arduino-monitor.service}"

echo "==== Ray Co Arduino Monitor Updater ===="

if [[ ! -d "$PROJECT_DIR/.git" ]]; then
    echo "Error: $PROJECT_DIR is not a git repository."
    echo "Clone the repo there first."
    exit 1
fi

cd "$PROJECT_DIR"

echo "[1/4] Pulling latest changes from GitHub..."
git pull origin main

echo "[2/4] Installing/updating Python requirements..."
if [[ -f requirements.txt ]]; then
    if python3 -m pip install --user -r requirements.txt; then
        echo "Requirements updated."
    else
        echo "User install failed, trying system-wide install..."
        sudo python3 -m pip install -r requirements.txt
    fi
else
    echo "No requirements.txt found, skipping."
fi

echo "[3/4] Making sure main script is executable..."
chmod +x UniversalArduinoMonitor.py
if [[ -f install.sh ]]; then
    chmod +x install.sh
fi

echo "[4/4] Restarting service..."
sudo systemctl restart "$SERVICE_NAME"

echo
echo "==== UPDATE COMPLETE ===="
echo "Repo: $PROJECT_DIR"
echo "Service: $SERVICE_NAME"
echo
sudo systemctl status "$SERVICE_NAME" --no-pager
