#!/bin/bash

set -e

echo "==== Ray Co Arduino Monitor Installer ===="

PROJECT_DIR="$HOME/ArduinoUniversalSystemMonitor"
REPO_URL="https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git"
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
            sudo dnf install -y python3 python3-pip git
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip git
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip git
            ;;
        *)
            echo "Unsupported distro."
            echo "Please manually install: python3, python3-pip, git"
            exit 1
            ;;
    esac
}

install_or_update_repo() {
    echo "[2/6] Installing repository into $PROJECT_DIR..."

    if [ -d "$PROJECT_DIR/.git" ]; then
        echo "Existing git repo found. Pulling latest..."
        git -C "$PROJECT_DIR" pull origin main
    else
        rm -rf "$PROJECT_DIR"
        git clone "$REPO_URL" "$PROJECT_DIR"
    fi
}

install_python_packages() {
    echo "[3/6] Installing Python packages..."

    cd "$PROJECT_DIR"

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
    echo "[4/6] Fixing serial permissions..."

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
    echo "[5/6] Ensuring config file exists..."

    if [ ! -f "$PROJECT_DIR/$CONFIG_NAME" ]; then
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

    chmod +x "$PROJECT_DIR/$SCRIPT_NAME" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/update.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/uninstall_monitor.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/install_arduinos.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/arduino_install.sh" 2>/dev/null || true
}

create_and_enable_service() {
    echo "[6/6] Creating and enabling systemd service..."

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

prompt_arduino_install() {
    local reply

    echo
    read -r -p "would you like to install and flash your arduino's now [y/N]: " reply

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

detect_distro
install_system_packages
install_or_update_repo
install_python_packages
fix_serial_permissions
ensure_config
create_and_enable_service
finish_message
prompt_arduino_install
