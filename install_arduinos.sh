#!/usr/bin/env bash
set -Eeuo pipefail

SERVICE_NAME="arduino-monitor.service"
RESTART_DELAY=2

R3_FQBN="arduino:avr:uno"
R3_SKETCH="UniversalArduinoMonitor28"

R4_FQBN="arduino:renesas_uno:unor4wifi"
R4_SKETCH="UniversalArduinoMonitor35"

REQUIRED_LIBS=(
    "MCUFRIEND_kbv"
    "Adafruit GFX Library"
    "TouchScreen"
)

echo "==== Ray Co Arduino Auto Flasher ===="

cd "$(dirname "$0")"

ensure_arduino_cli() {
    if command -v arduino-cli >/dev/null 2>&1; then
        echo "arduino-cli already installed."
        return 0
    fi

    echo "arduino-cli not found. Installing..."
    mkdir -p "$HOME/.local/bin"

    if curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR="$HOME/.local/bin" sh; then
        export PATH="$HOME/.local/bin:$PATH"
        echo "arduino-cli installed to $HOME/.local/bin"
    else
        echo "Failed to install arduino-cli automatically."
        echo "Install it manually, then rerun this script."
        exit 1
    fi

    export PATH="$HOME/.local/bin:$PATH"

    if ! command -v arduino-cli >/dev/null 2>&1; then
        echo "arduino-cli still not found after install."
        echo "Add $HOME/.local/bin to your PATH and try again."
        exit 1
    fi
}

ensure_arduino_cores() {
    echo "Updating Arduino core index..."
    arduino-cli core update-index

    if ! arduino-cli core list | grep -q '^arduino:avr'; then
        echo "Installing arduino:avr core..."
        arduino-cli core install arduino:avr
    else
        echo "arduino:avr core already installed."
    fi

    if ! arduino-cli core list | grep -q '^arduino:renesas_uno'; then
        echo "Installing arduino:renesas_uno core..."
        arduino-cli core install arduino:renesas_uno
    else
        echo "arduino:renesas_uno core already installed."
    fi
}

ensure_libraries() {
    echo "Checking required Arduino libraries..."

    for lib in "${REQUIRED_LIBS[@]}"; do
        if ! arduino-cli lib list | grep -Fqi "$lib"; then
            echo "Installing $lib..."
            arduino-cli lib install "$lib"
        else
            echo "$lib already installed."
        fi
    done
}

cleanup() {
    echo
    echo "Waiting ${RESTART_DELAY}s before restarting service..."
    sleep "$RESTART_DELAY"
    echo "Restarting $SERVICE_NAME ..."
    sudo systemctl restart "$SERVICE_NAME" || true
    echo "Done."
}
trap cleanup EXIT

ensure_arduino_cli
ensure_arduino_cores
ensure_libraries

echo "[1/4] Stopping monitor service..."
sudo systemctl stop "$SERVICE_NAME" || true

echo "[2/4] Detecting connected Arduino boards..."
mapfile -t BOARD_LINES < <(arduino-cli board list | tail -n +2)

if [[ ${#BOARD_LINES[@]} -eq 0 ]]; then
    echo "No serial boards detected."
    exit 1
fi

FLASHED_COUNT=0

for line in "${BOARD_LINES[@]}"; do
    port="$(awk '{print $1}' <<< "$line")"

    if grep -q "Arduino UNO R4 WiFi" <<< "$line"; then
        board_name="Arduino UNO R4 WiFi"
        fqbn="$R4_FQBN"
        sketch="$R4_SKETCH"
    elif grep -q "Arduino UNO" <<< "$line"; then
        board_name="Arduino UNO"
        fqbn="$R3_FQBN"
        sketch="$R3_SKETCH"
    else
        echo "Skipping unsupported device on $port"
        continue
    fi

    echo
    echo "[3/4] Flashing $board_name on $port"
    echo "      Sketch: $sketch"
    echo "      FQBN:   $fqbn"

    arduino-cli compile --fqbn "$fqbn" "$sketch"
    arduino-cli upload -p "$port" --fqbn "$fqbn" "$sketch"

    FLASHED_COUNT=$((FLASHED_COUNT + 1))

    if [[ $FLASHED_COUNT -ge 2 ]]; then
        echo
        echo "Flashed 2 boards, stopping here."
        break
    fi
done

if [[ $FLASHED_COUNT -eq 0 ]]; then
    echo "No supported Arduino boards were flashed."
    exit 1
fi

echo
echo "[4/4] Flash complete for $FLASHED_COUNT board(s)."
