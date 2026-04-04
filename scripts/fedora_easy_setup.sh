#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -f /etc/fedora-release ]; then
    echo "[warn] This helper is Fedora-focused. Detected non-Fedora system."
    echo "Run ./install.sh directly for generic Linux install flow."
    exit 1
fi

echo "== Fedora Easy Setup (Arduino Universal System Monitor) =="
echo "[1/3] Installing Fedora dependencies..."
sudo dnf install -y \
    git python3 python3-pip \
    java-21-openjdk \
    arduino-cli socat

echo "[2/3] Running project installer..."
chmod +x "$PROJECT_DIR/install.sh"
"$PROJECT_DIR/install.sh"

echo "[3/3] Setup complete. Launching Control Center command hint:"
echo "  cd $PROJECT_DIR && ./UniversalMonitorControlCenter.sh"
