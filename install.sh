#!/bin/bash

set -Eeuo pipefail

echo "==== Ray Co Arduino Monitor Installer ===="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -d "$SCRIPT_DIR/.git" ]; then
    PROJECT_DIR="${PROJECT_DIR:-$SCRIPT_DIR}"
else
    PROJECT_DIR="${PROJECT_DIR:-$HOME/ArduinoUniversalSystemMonitor}"
fi
REPO_URL="https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git"
SCRIPT_NAME="UniversalArduinoMonitor.py"
CONFIG_NAME="monitor_config.json"
SERVICE_NAME="arduino-monitor.service"
VENV_DIR="$PROJECT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python3"

have_command() {
    command -v "$1" >/dev/null 2>&1
}

run_preinstall_uninstall() {
    echo "[pre] Stopping and removing old service for a clean install..."
    if systemctl list-unit-files | grep -q "^$SERVICE_NAME"; then
        sudo systemctl stop "$SERVICE_NAME" 2>/dev/null || true
        sudo systemctl disable "$SERVICE_NAME" 2>/dev/null || true
    fi

    if [ -f "/etc/systemd/system/$SERVICE_NAME" ]; then
        sudo rm -f "/etc/systemd/system/$SERVICE_NAME"
        sudo systemctl daemon-reload
    fi
}

detect_distro() {
    if [ -f /etc/fedora-release ]; then
        DISTRO="fedora"
    elif [ -f /etc/debian_version ]; then
        DISTRO="debian"
    elif [ -f /etc/arch-release ]; then
        DISTRO="arch"
    else
        DISTRO="unknown"
    fi
}

install_system_packages() {
    echo "[1/8] Installing system packages for $DISTRO..."

    case "$DISTRO" in
        fedora)
            sudo dnf install -y python3 python3-pip python3-virtualenv git curl
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip python3-venv git curl
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip git curl
            ;;
        *)
            echo "Unsupported distro."
            echo "Please manually install: python3, python3-pip, python3-venv/virtualenv, git, curl"
            exit 1
            ;;
    esac

    if ! have_command git; then
        echo "Error: git is still not available after package installation."
        exit 1
    fi

    if ! have_command python3; then
        echo "Error: python3 is still not available after package installation."
        exit 1
    fi
}

install_or_update_repo() {
    echo "[2/8] Installing repository into $PROJECT_DIR..."

    if [ -d "$PROJECT_DIR/.git" ]; then
        echo "Existing git repo found. Pulling latest..."
        git -C "$PROJECT_DIR" pull origin main
    elif [ "$PROJECT_DIR" = "$SCRIPT_DIR" ]; then
        echo "Using current project directory (not re-cloning)."
    else
        rm -rf "$PROJECT_DIR"
        git clone "$REPO_URL" "$PROJECT_DIR"
    fi
}

setup_virtualenv() {
    echo "[3/8] Creating Python virtual environment..."

    cd "$PROJECT_DIR"

    if [ ! -d "$VENV_DIR" ]; then
        python3 -m venv "$VENV_DIR"
    fi

    "$VENV_DIR/bin/python3" -m pip install --upgrade pip
}

install_python_packages() {
    echo "[4/8] Installing Python packages into virtual environment..."

    cd "$PROJECT_DIR"

    if [ -f requirements.txt ]; then
        "$VENV_DIR/bin/pip" install -r requirements.txt
    else
        "$VENV_DIR/bin/pip" install psutil pyserial
    fi
}

fix_serial_permissions() {
    echo "[5/8] Fixing serial permissions..."

    SERIAL_GROUP="dialout"

    if getent group dialout >/dev/null 2>&1; then
        SERIAL_GROUP="dialout"
    elif getent group uucp >/dev/null 2>&1; then
        SERIAL_GROUP="uucp"
    fi

    echo "Using serial group: $SERIAL_GROUP"
    sudo usermod -aG "$SERIAL_GROUP" "$USER"
}

ensure_config() {
    echo "[6/8] Ensuring config file exists..."

    if [ ! -f "$PROJECT_DIR/$CONFIG_NAME" ]; then
        echo "Creating default $CONFIG_NAME..."
        cat > "$PROJECT_DIR/$CONFIG_NAME" <<'JSON'
{
  "arduino_port": "AUTO",
  "baud": 115200,
  "debug_enabled": false,
  "debug_port": "/tmp/fakearduino_in",
  "root_mount": "/",
  "secondary_mount": "/mnt/linux_storage",
  "send_interval": 2.0
}
JSON
    fi

    chmod +x "$PROJECT_DIR/$SCRIPT_NAME" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/install.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/update.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/uninstall_monitor.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/install_arduinos.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/arduino_install.sh" 2>/dev/null || true
}

create_service_file() {
    echo "[7/8] Creating systemd service file..."

    sudo tee "/etc/systemd/system/$SERVICE_NAME" > /dev/null <<SERVICE
[Unit]
Description=Arduino System Monitor
After=network.target

[Service]
ExecStart=$PYTHON_BIN $PROJECT_DIR/$SCRIPT_NAME
WorkingDirectory=$PROJECT_DIR
Restart=always
User=$USER

[Install]
WantedBy=multi-user.target
SERVICE

    sudo systemctl daemon-reload
}

prompt_arduino_install() {
    local reply

    echo
    read -r -p "Would you like to install and flash your Arduino(s) now? [y/N]: " reply

    if [[ "$reply" =~ ^[Yy]$ ]]; then
        if [ -x "$PROJECT_DIR/arduino_install.sh" ]; then
            "$PROJECT_DIR/arduino_install.sh"
        elif [ -x "$PROJECT_DIR/install_arduinos.sh" ]; then
            "$PROJECT_DIR/install_arduinos.sh"
        else
            echo "Arduino install script not found or not executable."
            return 1
        fi
    else
        echo "Skipping Arduino install and flash step."
    fi
}

enable_and_start_service() {
    echo "[8/8] Enabling and starting systemd service..."
    sudo systemctl enable "$SERVICE_NAME"
    sudo systemctl restart "$SERVICE_NAME"
}

finish_message() {
    echo
    echo "==== INSTALL COMPLETE ===="
    echo "Project installed to: $PROJECT_DIR"
    echo "Config file: $PROJECT_DIR/$CONFIG_NAME"
    echo "Virtual environment: $VENV_DIR"
    echo
    echo "IMPORTANT:"
    echo "- Log out and back in (or reboot) so new serial permissions apply."
    echo "- Make sure your Arduino is plugged in."
    echo
    echo "Service commands:"
    echo "  sudo systemctl start arduino-monitor"
    echo "  sudo systemctl stop arduino-monitor"
    echo "  sudo systemctl restart arduino-monitor"
    echo "  sudo systemctl status arduino-monitor"
    echo
    echo "Update later with:"
    echo "  cd $PROJECT_DIR && ./update.sh"
    echo
    echo "Uninstall later with:"
    echo "  cd $PROJECT_DIR && ./uninstall_monitor.sh"
    echo "=========================="
}

run_preinstall_uninstall
detect_distro
install_system_packages
install_or_update_repo
setup_virtualenv
install_python_packages
fix_serial_permissions
ensure_config
create_service_file
prompt_arduino_install
enable_and_start_service
finish_message
