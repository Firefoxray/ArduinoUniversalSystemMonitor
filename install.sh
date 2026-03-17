#!/bin/bash

set -e

echo "==== Ray Co Arduino Monitor Installer ===="

PROJECT_DIR="$HOME/ArduinoUniversalSystemMonitor"
SCRIPT_NAME="UniversalArduinoMonitor.py"
CONFIG_NAME="monitor_config.json"
SERVICE_NAME="arduino-monitor.service"
PYTHON_BIN="/usr/bin/python3"

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
    echo "[1/6] Installing system packages for $DISTRO..."

    case "$DISTRO" in
        fedora)
            sudo dnf install -y python3 python3-pip
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip
            ;;
        *)
            echo "Unsupported distro."
            echo "Please manually install: python3, pip"
            exit 1
            ;;
    esac
}

install_python_packages() {
    echo "[2/6] Installing Python packages..."

    if [ -f requirements.txt ]; then
        if python3 -m pip install --user -r requirements.txt; then
            echo "Python packages installed successfully."
        else
            echo "pip user install failed, trying system-wide install..."
            sudo python3 -m pip install -r requirements.txt
        fi
    else
        if python3 -m pip install --user psutil pyserial; then
            echo "Python packages installed successfully."
        else
            echo "pip user install failed, trying system-wide install..."
            sudo python3 -m pip install psutil pyserial
        fi
    fi
}

fix_serial_permissions() {
    echo "[3/6] Fixing serial permissions..."

    SERIAL_GROUP="dialout"

    if getent group dialout >/dev/null 2>&1; then
        SERIAL_GROUP="dialout"
    elif getent group uucp >/dev/null 2>&1; then
        SERIAL_GROUP="uucp"
    fi

    echo "Using serial group: $SERIAL_GROUP"
    sudo usermod -aG "$SERIAL_GROUP" "$USER"
}

install_script() {
    echo "[4/6] Installing monitor script..."

    mkdir -p "$PROJECT_DIR"

    if [ ! -f "$SCRIPT_NAME" ]; then
        echo "Error: $SCRIPT_NAME not found in current directory."
        echo "Run this installer from the folder containing $SCRIPT_NAME."
        exit 1
    fi

    cp "$SCRIPT_NAME" "$PROJECT_DIR/$SCRIPT_NAME"

    if [ -f "$CONFIG_NAME" ]; then
        cp "$CONFIG_NAME" "$PROJECT_DIR/$CONFIG_NAME"
    else
        echo "Creating default $CONFIG_NAME..."
        cat > "$PROJECT_DIR/$CONFIG_NAME" <<'EOF'
{
  "arduino_port": "AUTO",
  "baud": 115200,
  "debug_enabled": false,
  "debug_port": "/tmp/fakearduino_in",
  "root_mount": "/",
  "secondary_mount": "/mnt/linux_storage",
  "send_interval": 2.0
}
EOF
    fi

    chmod +x "$PROJECT_DIR/$SCRIPT_NAME"
}

create_service() {
    echo "[5/6] Creating systemd service..."

    sudo tee "/etc/systemd/system/$SERVICE_NAME" > /dev/null <<EOF
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
EOF
}

enable_service() {
    echo "[6/6] Enabling and starting service..."
    sudo systemctl daemon-reload
    sudo systemctl enable "$SERVICE_NAME"
    sudo systemctl restart "$SERVICE_NAME"
}

finish_message() {
    echo
    echo "==== INSTALL COMPLETE ===="
    echo "Project installed to: $PROJECT_DIR"
    echo "Config file: $PROJECT_DIR/$CONFIG_NAME"
    echo
    echo "IMPORTANT:"
    echo "- Reboot or log out/log back in so new serial permissions apply."
    echo "- Make sure your Arduino is plugged in."
    echo
    echo "Service commands:"
    echo "  sudo systemctl start arduino-monitor"
    echo "  sudo systemctl stop arduino-monitor"
    echo "  sudo systemctl restart arduino-monitor"
    echo "  sudo systemctl status arduino-monitor"
    echo "=========================="
}

detect_distro
install_system_packages
install_python_packages
fix_serial_permissions
install_script
create_service
enable_service
finish_message
