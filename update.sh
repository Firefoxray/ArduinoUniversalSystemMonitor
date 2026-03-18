#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$SCRIPT_DIR}"
SERVICE_NAME="${SERVICE_NAME:-arduino-monitor.service}"
VENV_DIR="$PROJECT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python3"
PIP_BIN="$VENV_DIR/bin/pip"

have_command() {
    command -v "$1" >/dev/null 2>&1
}

detect_distro() {
    if [[ -f /etc/fedora-release ]]; then
        DISTRO="fedora"
    elif [[ -f /etc/debian_version ]]; then
        DISTRO="debian"
    elif [[ -f /etc/arch-release ]]; then
        DISTRO="arch"
    else
        DISTRO="unknown"
    fi
}

install_missing_prereqs() {
    detect_distro

    if have_command git && have_command python3; then
        return 0
    fi

    echo "Missing git/python3 detected, attempting install for $DISTRO..."

    case "$DISTRO" in
        fedora)
            sudo dnf install -y python3 python3-pip python3-virtualenv git
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip python3-venv git
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip git
            ;;
        *)
            echo "Unsupported distro for automatic dependency install."
            echo "Install git + python3 manually, then rerun update.sh"
            exit 1
            ;;
    esac

    if ! have_command git || ! have_command python3; then
        echo "Failed to install required prerequisites (git/python3)."
        exit 1
    fi
}

echo "==== Ray Co Arduino Monitor Updater ===="

install_missing_prereqs

if [[ ! -d "$PROJECT_DIR/.git" ]]; then
    echo "Warning: $PROJECT_DIR is not a git repository."
    if [[ -d "$SCRIPT_DIR/.git" ]]; then
        echo "Using script location repo instead: $SCRIPT_DIR"
        PROJECT_DIR="$SCRIPT_DIR"
        VENV_DIR="$PROJECT_DIR/.venv"
        PYTHON_BIN="$VENV_DIR/bin/python3"
        PIP_BIN="$VENV_DIR/bin/pip"
    else
        echo "Cloning fresh repository into $PROJECT_DIR ..."
        rm -rf "$PROJECT_DIR"
        git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git "$PROJECT_DIR"
        VENV_DIR="$PROJECT_DIR/.venv"
        PYTHON_BIN="$VENV_DIR/bin/python3"
        PIP_BIN="$VENV_DIR/bin/pip"
    fi
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
    "$PIP_BIN" install psutil pyserial
    echo "requirements.txt missing, installed fallback dependencies (psutil, pyserial)."
fi

echo "[5/5] Making sure scripts are executable and restarting service..."
chmod +x UniversalArduinoMonitor.py 2>/dev/null || true
chmod +x install.sh 2>/dev/null || true
chmod +x update.sh 2>/dev/null || true
chmod +x uninstall_monitor.sh 2>/dev/null || true
chmod +x install_arduinos.sh 2>/dev/null || true
chmod +x arduino_install.sh 2>/dev/null || true

sudo systemctl restart "$SERVICE_NAME"

echo
echo "==== UPDATE COMPLETE ===="
echo "Repo: $PROJECT_DIR"
echo "Virtual environment: $VENV_DIR"
echo "Service: $SERVICE_NAME"
echo
sudo systemctl status "$SERVICE_NAME" --no-pager
