#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-$HOME/ArduinoUniversalSystemMonitor}"
SERVICE_NAME="${SERVICE_NAME:-arduino-monitor.service}"
VENV_DIR="$PROJECT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python3"
PIP_BIN="$VENV_DIR/bin/pip"

echo "==== Ray Co Arduino Monitor Updater ===="

if [[ ! -d "$PROJECT_DIR/.git" ]]; then
    echo "Error: $PROJECT_DIR is not a git repository."
    echo "Clone the repo there first."
    exit 1
fi

cd "$PROJECT_DIR"

echo "[1/5] Pulling latest changes from GitHub..."
git pull origin main

echo "[2/5] Ensuring Python virtual environment exists..."
if [[ ! -d "$VENV_DIR" ]]; then
    python3 -m venv "$VENV_DIR"
fi

echo "[3/5] Updating Python packaging tools..."
"$PYTHON_BIN" -m pip install --upgrade pip

echo "[4/5] Installing/updating Python requirements..."
if [[ -f requirements.txt ]]; then
    "$PIP_BIN" install -r requirements.txt
    echo "Requirements updated."
else
    echo "No requirements.txt found, skipping."
fi

echo "[5/5] Making sure scripts are executable and restarting service..."
chmod +x UniversalArduinoMonitor.py 2>/dev/null || true
chmod +x install.sh 2>/dev/null || true
chmod +x update.sh 2>/dev/null || true
chmod +x uninstall_monitor.sh 2>/dev/null || true
chmod +x install_arduinos.sh 2>/dev/null || true

sudo systemctl restart "$SERVICE_NAME"

echo
echo "==== UPDATE COMPLETE ===="
echo "Repo: $PROJECT_DIR"
echo "Virtual environment: $VENV_DIR"
echo "Service: $SERVICE_NAME"
echo
sudo systemctl status "$SERVICE_NAME" --no-pager
