#!/bin/bash

set -Eeuo pipefail

echo "==== Ray Co Arduino Monitor Installer ===="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/project_paths.sh"
INSTALL_USER="${SUDO_USER:-${USER:-$(id -un)}}"
INSTALL_HOME="$(getent passwd "$INSTALL_USER" | cut -d: -f6)"
if [ -z "$INSTALL_HOME" ]; then
    INSTALL_HOME="$HOME"
fi

DEFAULT_PROJECT_DIR="$(resolve_project_dir "$SCRIPT_DIR" "${BASH_SOURCE[0]}")"
if [ "$DEFAULT_PROJECT_DIR" = "$SCRIPT_DIR" ] && [ ! -d "$SCRIPT_DIR/.git" ]; then
    DEFAULT_PROJECT_DIR="$INSTALL_HOME/ArduinoUniversalSystemMonitor"
fi
PROJECT_DIR="$(resolve_project_dir "${PROJECT_DIR:-$DEFAULT_PROJECT_DIR}" "${BASH_SOURCE[0]}")"
REPO_URL="https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git"
SCRIPT_NAME="UniversalArduinoMonitor.py"
CONFIG_NAME="monitor_config.json"
DEFAULT_CONFIG_NAME="monitor_config.default.json"
LOCAL_CONFIG_NAME="monitor_config.local.json"
SERVICE_NAME="arduino-monitor.service"
PYTHON_BIN="/usr/bin/python3"
PIP_FLAGS=()

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
            sudo dnf install -y python3 python3-pip git curl socat
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip git curl socat
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip git curl socat
            ;;
        *)
            echo "Unsupported distro."
            echo "Please manually install: python3, python3-pip, git, curl, socat"
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

configure_pip_flags() {
    PIP_FLAGS=()

    if python3 -m pip help install 2>/dev/null | grep -q -- "--break-system-packages"; then
        PIP_FLAGS=(--break-system-packages)
    fi
}

run_pip_user_install() {
    if [ "$(id -un)" = "$INSTALL_USER" ]; then
        python3 -m pip install --user "${PIP_FLAGS[@]}" "$@"
    else
        sudo -H -u "$INSTALL_USER" python3 -m pip install --user "${PIP_FLAGS[@]}" "$@"
    fi
}

install_python_packages() {
    echo "[3/8] Installing Python packages..."

    cd "$PROJECT_DIR"
    configure_pip_flags

    if [ -f requirements.txt ]; then
        if run_pip_user_install -r requirements.txt; then
            echo "Python packages installed to the user site-packages directory."
        else
            echo "User pip install failed, trying system-wide install..."
            sudo python3 -m pip install "${PIP_FLAGS[@]}" -r requirements.txt
        fi
    else
        if run_pip_user_install psutil pyserial; then
            echo "Python packages installed to the user site-packages directory."
        else
            echo "User pip install failed, trying system-wide install..."
            sudo python3 -m pip install "${PIP_FLAGS[@]}" psutil pyserial
        fi
    fi
}

fix_serial_permissions() {
    echo "[4/8] Fixing serial permissions..."

    SERIAL_GROUP="dialout"

    if getent group dialout >/dev/null 2>&1; then
        SERIAL_GROUP="dialout"
    elif getent group uucp >/dev/null 2>&1; then
        SERIAL_GROUP="uucp"
    fi

    echo "Using serial group: $SERIAL_GROUP"
    sudo usermod -aG "$SERIAL_GROUP" "$INSTALL_USER"
}

ensure_config() {
    echo "[5/8] Ensuring config files exist..."

    if [ ! -f "$PROJECT_DIR/$DEFAULT_CONFIG_NAME" ]; then
        echo "Creating $DEFAULT_CONFIG_NAME..."
        cat > "$PROJECT_DIR/$DEFAULT_CONFIG_NAME" <<'JSON'
{
  "arduino_port": "AUTO",
  "baud": 115200,
  "debug_enabled": false,
  "debug_port": "/tmp/fakearduino_in",
  "root_mount": "/",
  "secondary_mount": "/mnt/linux_storage",
  "send_interval": 2.0,
  "arduino_ports": [],
  "wifi_enabled": true,
  "wifi_host": "",
  "wifi_port": 5000,
  "prefer_usb": true,
  "wifi_retry_delay": 5,
  "wifi_auto_discovery": true,
  "wifi_discovery_port": 5001,
  "wifi_discovery_timeout": 1.2,
  "wifi_discovery_refresh": 30,
  "wifi_discovery_magic": "UAM_DISCOVER"
}
JSON
    fi

    if [ ! -f "$PROJECT_DIR/$CONFIG_NAME" ]; then
        echo "Copying $DEFAULT_CONFIG_NAME to $CONFIG_NAME..."
        cp "$PROJECT_DIR/$DEFAULT_CONFIG_NAME" "$PROJECT_DIR/$CONFIG_NAME"
    fi

    if [ ! -f "$PROJECT_DIR/$LOCAL_CONFIG_NAME" ]; then
        echo "Creating machine-local override file $LOCAL_CONFIG_NAME..."
        cp "$PROJECT_DIR/$CONFIG_NAME" "$PROJECT_DIR/$LOCAL_CONFIG_NAME"
    fi

    chmod +x "$PROJECT_DIR/$SCRIPT_NAME" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/install.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/update.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/uninstall_monitor.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/install_arduinos.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/arduino_install.sh" 2>/dev/null || true
}

create_service_file() {
    echo "[6/8] Creating systemd service file..."

    sudo tee "/etc/systemd/system/$SERVICE_NAME" > /dev/null <<SERVICE
[Unit]
Description=Arduino System Monitor
After=network.target

[Service]
ExecStart=$PYTHON_BIN $PROJECT_DIR/$SCRIPT_NAME
WorkingDirectory=$PROJECT_DIR
Restart=always
User=$INSTALL_USER

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
    echo "[7/8] Enabling and starting systemd service..."
    sudo systemctl enable "$SERVICE_NAME"
    sudo systemctl restart "$SERVICE_NAME"
}

finish_message() {
    echo
    echo "==== INSTALL COMPLETE ===="
    echo "Installed for user: $INSTALL_USER"
    echo "Project installed to: $PROJECT_DIR"
    echo "Config file: $PROJECT_DIR/$CONFIG_NAME"
    echo "Systemd service uses user/path: $INSTALL_USER -> $PROJECT_DIR"
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
install_python_packages
fix_serial_permissions
ensure_config
create_service_file
prompt_arduino_install
enable_and_start_service
finish_message
