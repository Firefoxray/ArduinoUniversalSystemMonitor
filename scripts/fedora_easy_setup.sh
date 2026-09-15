#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$PROJECT_DIR/lib/arduino_cli.sh"

if [ ! -f /etc/fedora-release ]; then
    echo "[warn] This helper is Fedora-focused. Detected non-Fedora system."
    echo "Run ./install.sh directly for generic Linux install flow."
    exit 1
fi

FEDORA_VERSION_ID="$(. /etc/os-release && printf '%s' "$VERSION_ID")"

echo "== Fedora Easy Setup (Arduino Universal System Monitor) =="
echo "Detected Fedora Linux $FEDORA_VERSION_ID."

case "$FEDORA_VERSION_ID" in
    45)
        echo "Fedora 45 is the current tested desktop target."
        ;;
    44)
        echo "Fedora 44 remains supported for existing installations."
        ;;
    *)
        echo "[warn] Fedora $FEDORA_VERSION_ID has not been explicitly tested yet."
        echo "[warn] Continuing with the standard Fedora package set."
        ;;
esac

echo "[1/5] Installing Fedora dependencies..."
sudo dnf install -y \
    git curl python3 python3-pip python3-virtualenv \
    python3-psutil python3-pyserial \
    java-25-openjdk java-25-openjdk-devel \
    socat lm_sensors pciutils upower

echo "[2/5] Verifying Python runtime dependencies..."
if ! /usr/bin/python3 -c 'import psutil, serial' >/dev/null 2>&1; then
    echo "Error: Fedora Python cannot import psutil and/or pyserial after installation."
    echo "Try: sudo dnf reinstall python3-psutil python3-pyserial"
    exit 1
fi
/usr/bin/python3 -c 'import psutil, serial; print(f"Python dependencies OK: psutil={psutil.__version__}, pyserial={serial.__version__}")'

echo "[3/5] Installing Arduino CLI..."
ensure_arduino_cli

echo "[4/5] Running project installer..."
chmod +x "$PROJECT_DIR/install.sh"
"$PROJECT_DIR/install.sh"

echo "[5/5] Setup complete. Launching Control Center command hint:"
echo "  cd $PROJECT_DIR && ./UniversalMonitorControlCenter.sh"
