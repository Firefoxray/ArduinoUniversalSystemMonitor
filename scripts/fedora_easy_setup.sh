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

echo "== Fedora Easy Setup (Arduino Universal System Monitor) =="
FEDORA_VERSION_ID="$(. /etc/os-release && printf '%s' "$VERSION_ID")"
echo "Detected Fedora Linux $FEDORA_VERSION_ID (Fedora 44 is the supported target)."

echo "[1/4] Installing Fedora 44 dependencies..."
sudo dnf install -y \
    git curl python3 python3-pip \
    java-25-openjdk java-25-openjdk-devel \
    socat

echo "[2/4] Installing Arduino CLI..."
ensure_arduino_cli

echo "[3/4] Running project installer..."
chmod +x "$PROJECT_DIR/install.sh"
"$PROJECT_DIR/install.sh"

echo "[4/4] Setup complete. Launching Control Center command hint:"
echo "  cd $PROJECT_DIR && ./UniversalMonitorControlCenter.sh"
