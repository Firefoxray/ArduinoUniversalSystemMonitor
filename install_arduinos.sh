#!/usr/bin/env bash
set -Eeuo pipefail

SERVICE_NAME="arduino-monitor.service"
RESTART_DELAY=2

R3_FQBN="arduino:avr:uno"
R3_MEGA_FQBN="arduino:avr:mega"
UNO_R3_SCREEN_SIZE="${UNO_R3_SCREEN_SIZE:-28}"

case "$UNO_R3_SCREEN_SIZE" in
    28)
        R3_SKETCH="R3_MonitorScreen28"
        ;;
    35)
        R3_SKETCH="R3_MonitorScreen35"
        ;;
    *)
        echo "Invalid UNO_R3_SCREEN_SIZE: $UNO_R3_SCREEN_SIZE (expected 28 or 35)" >&2
        exit 1
        ;;
esac

R3_MEGA_SKETCH="R3_MEGA_MonitorScreen35"

R4_FQBN="arduino:renesas_uno:unor4wifi"
R4_SKETCH="R4_WIFI35"
R4_WIFI_LOCAL_CONFIG="$R4_SKETCH/wifi_config.local.h"
R4_WIFI_DEFAULT_CONFIG="$R4_SKETCH/wifi_config.h"

REQUIRED_LIBS=(
    "MCUFRIEND_kbv|MCUFRIEND_kbv|MCUFRIEND_kbv.h"
    "Adafruit GFX Library|Adafruit GFX Library|Adafruit_GFX.h"
    "Adafruit TouchScreen|TouchScreen|TouchScreen.h"
    "DIYables TFT Touch Shield|DIYables TFT Touch Shield|DIYables_TFT_Touch_Shield.h"
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
    if ! arduino-cli core list | grep -q '^arduino:avr'; then
        echo "Updating Arduino core index (missing core)..."
        arduino-cli core update-index
        echo "Installing arduino:avr core..."
        arduino-cli core install arduino:avr
    else
        echo "arduino:avr core already installed."
    fi

    if ! arduino-cli core list | grep -q '^arduino:renesas_uno'; then
        echo "Updating Arduino core index (missing core)..."
        arduino-cli core update-index
        echo "Installing arduino:renesas_uno core..."
        arduino-cli core install arduino:renesas_uno
    else
        echo "arduino:renesas_uno core already installed."
    fi
}

header_exists() {
    local header="$1"
    local sketchbook_libs="$HOME/Arduino/libraries"

    if [[ -d "$sketchbook_libs" ]] && find "$sketchbook_libs" -maxdepth 4 -type f -name "$header" | grep -q .; then
        return 0
    fi

    return 1
}

read_wifi_define() {
    local define_name="$1"
    local default_value="${2:-}"
    local marker="#define $define_name"
    local path=""
    local line=""

    for path in "$R4_WIFI_LOCAL_CONFIG" "$R4_WIFI_DEFAULT_CONFIG"; do
        [[ -f "$path" ]] || continue
        while IFS= read -r line; do
            line="${line#"${line%%[![:space:]]*}"}"
            [[ "$line" == "$marker"* ]] || continue
            line="${line#"$marker"}"
            line="${line#"${line%%[![:space:]]*}"}"
            if [[ "$line" == '"'*'"' ]]; then
                line="${line#\"}"
                line="${line%\"}"
            fi
            printf '%s\n' "${line:-$default_value}"
            return 0
        done < "$path"
    done

    printf '%s\n' "$default_value"
}

prompt_with_default() {
    local prompt="$1"
    local default_value="${2:-}"
    local reply=""

    if [[ ! -t 0 ]]; then
        printf '%s\n' "$default_value"
        return 0
    fi

    read -r -p "$prompt [$default_value]: " reply
    printf '%s\n' "${reply:-$default_value}"
}

escape_cpp_string() {
    local value="${1//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s' "$value"
}

write_r4_wifi_local_config() {
    local ssid="$1"
    local pass="$2"
    local tcp_port="$3"
    local device_name="$4"
    local paired_label="$5"
    local paired_hostname="$6"
    local paired_assignment="$7"

    cat > "$R4_WIFI_LOCAL_CONFIG" <<EOF
#pragma once

#define WIFI_SSID_VALUE "$(escape_cpp_string "$ssid")"
#define WIFI_PASS_VALUE "$(escape_cpp_string "$pass")"
#define WIFI_TCP_PORT_VALUE $tcp_port
#define WIFI_DEVICE_NAME_VALUE "$(escape_cpp_string "$device_name")"
#define PAIRED_HOST_LABEL_VALUE "$(escape_cpp_string "$paired_label")"
#define PAIRED_HOSTNAME_VALUE "$(escape_cpp_string "$paired_hostname")"
#define PAIRED_BOARD_ASSIGNMENT_VALUE "$(escape_cpp_string "$paired_assignment")"
EOF
}

lib_installed_by_name() {
    local detect_name="$1"
    arduino-cli lib list | grep -Fqi "$detect_name"
}

run_cli_with_user_env() {
    local cli_path="$1"
    shift

    sudo env \
        HOME="$HOME" \
        USER="${USER:-$(id -un)}" \
        LOGNAME="${LOGNAME:-${USER:-$(id -un)}}" \
        XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}" \
        XDG_CACHE_HOME="${XDG_CACHE_HOME:-$HOME/.cache}" \
        XDG_DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}" \
        "$cli_path" "$@"
}

ensure_libraries() {
    echo "Checking required Arduino libraries..."

    local index_updated=0

    for entry in "${REQUIRED_LIBS[@]}"; do
        IFS='|' read -r install_name detect_name header_name <<< "$entry"

        if lib_installed_by_name "$detect_name" || header_exists "$header_name"; then
            echo "$install_name already installed."
            continue
        fi

        if [[ $index_updated -eq 0 ]]; then
            echo "Updating Arduino library index (missing library)..."
            arduino-cli lib update-index
            index_updated=1
        fi

        echo "Installing $install_name..."
        if arduino-cli lib install "$install_name"; then
            continue
        fi

        if [[ "$detect_name" != "$install_name" ]]; then
            echo "Retrying with fallback name: $detect_name"
            if arduino-cli lib install "$detect_name"; then
                continue
            fi
        fi

        echo "Error installing $install_name."
        exit 1
    done
}


count_r3_and_mega_boards() {
    local count=0
    local line=""

    for line in "${BOARD_LINES[@]}"; do
        if grep -q "Arduino UNO R4 WiFi" <<< "$line"; then
            continue
        fi
        if grep -Eq "Arduino UNO|Arduino Mega|Mega 2560" <<< "$line"; then
            count=$((count + 1))
        fi
    done

    printf '%s\n' "$count"
}


stop_service_before_flash() {
    echo "[1/4] Stopping monitor service before flashing..."
    sudo systemctl stop "$SERVICE_NAME" || true
}

detect_boards() {
    echo "[2/4] Detecting connected Arduino boards..."
    mapfile -t BOARD_LINES < <(arduino-cli board list | tail -n +2)

    if [[ ${#BOARD_LINES[@]} -eq 0 ]]; then
        echo "No serial boards detected."
        exit 1
    fi
}

upload_with_retry() {
    local port="$1"
    local fqbn="$2"
    local sketch="$3"
    local board_name="$4"
    local cli_path
    local upload_output
    local attempt

    cli_path="$(command -v arduino-cli)"

    for attempt in 1 2; do
        if [[ $attempt -gt 1 ]]; then
            echo "Retrying upload for $board_name on $port after a short delay..."
            sleep 3
        fi

        if upload_output="$($cli_path upload -p "$port" --fqbn "$fqbn" "$sketch" 2>&1)"; then
            printf '%s
' "$upload_output"
            return 0
        fi

        printf '%s
' "$upload_output"

        if grep -Eqi '(permission denied|cannot perform port reset|no device found on tty|access is denied)' <<< "$upload_output"; then
            echo "Upload hit a serial-port access/reset problem on $port."
            echo "Retrying upload with sudo so Fedora can perform the reset cleanly..."

            sleep 2

            if upload_output="$(run_cli_with_user_env "$cli_path" upload -p "$port" --fqbn "$fqbn" "$sketch" 2>&1)"; then
                printf '%s
' "$upload_output"
                echo "Upload succeeded after sudo retry for $board_name on $port."
                return 0
            fi

            printf '%s
' "$upload_output"
            echo "Upload still failed on $port after sudo retry."
        fi
    done

    echo "Upload failed on $port after 2 attempt(s)."
    return 1
}

flash_boards() {
    local flashed_count=0
    local compile_key=""
    local port=""
    local board_name=""
    local fqbn=""
    local sketch=""
    local board_index=0
    local wifi_ssid=""
    local wifi_pass=""
    local wifi_tcp_port=""
    local wifi_device_name=""
    local paired_host_label=""
    local paired_hostname=""
    local paired_assignment=""
    declare -A compiled_sketches=()

    wifi_ssid="$(read_wifi_define "WIFI_SSID_VALUE" "YOUR_WIFI_SSID")"
    wifi_pass="$(read_wifi_define "WIFI_PASS_VALUE" "YOUR_WIFI_PASSWORD")"
    wifi_tcp_port="$(read_wifi_define "WIFI_TCP_PORT_VALUE" "5000")"
    wifi_device_name="$(read_wifi_define "WIFI_DEVICE_NAME_VALUE" "R4_WIFI35")"
    paired_host_label="$(read_wifi_define "PAIRED_HOST_LABEL_VALUE" "Unassigned")"
    paired_hostname="$(read_wifi_define "PAIRED_HOSTNAME_VALUE" "")"
    paired_assignment="$(read_wifi_define "PAIRED_BOARD_ASSIGNMENT_VALUE" "")"

    for line in "${BOARD_LINES[@]}"; do
        port="$(awk '{print $1}' <<< "$line")"

        if grep -q "Arduino UNO R4 WiFi" <<< "$line"; then
            board_name="Arduino UNO R4 WiFi"
            fqbn="$R4_FQBN"
            sketch="$R4_SKETCH"
        elif grep -Eq "Arduino Mega|Mega 2560" <<< "$line"; then
            board_name="Arduino Mega 2560"
            fqbn="$R3_MEGA_FQBN"
            sketch="$R3_MEGA_SKETCH"
        elif grep -q "Arduino UNO" <<< "$line"; then
            board_name="Arduino UNO R3"
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

        if [[ "$fqbn" == "$R4_FQBN" ]]; then
            board_index=$((board_index + 1))
            echo "      Configure pairing metadata for UNO R4 WiFi board #$board_index on $port."
            wifi_device_name="$(prompt_with_default "      Wi-Fi device name" "$wifi_device_name")"
            paired_host_label="$(prompt_with_default "      Paired computer label" "$paired_host_label")"
            paired_hostname="$(prompt_with_default "      Target computer hostname" "$paired_hostname")"
            paired_assignment="$(prompt_with_default "      Board assignment" "$paired_assignment")"
            write_r4_wifi_local_config \
                "$wifi_ssid" \
                "$wifi_pass" \
                "$wifi_tcp_port" \
                "$wifi_device_name" \
                "$paired_host_label" \
                "$paired_hostname" \
                "$paired_assignment"
            echo "      Updated $R4_WIFI_LOCAL_CONFIG for this board:"
            echo "        device=$wifi_device_name"
            echo "        label=$paired_host_label"
            echo "        host=${paired_hostname:-<blank>}"
            echo "        assignment=${paired_assignment:-<blank>}"
        fi

        compile_key="$fqbn|$sketch"
        if [[ "$fqbn" == "$R4_FQBN" ]]; then
            compile_key="$fqbn|$sketch|$wifi_device_name|$paired_host_label|$paired_hostname|$paired_assignment"
        fi
        if [[ -z "${compiled_sketches[$compile_key]:-}" ]]; then
            echo "      Compiling $sketch once for all matching boards..."
            arduino-cli compile --fqbn "$fqbn" "$sketch" || exit 1
            compiled_sketches[$compile_key]=1
        else
            echo "      Reusing existing compile output for $sketch"
        fi

        if upload_with_retry "$port" "$fqbn" "$sketch" "$board_name"; then
            flashed_count=$((flashed_count + 1))
        else
            echo "      Upload failed for $board_name on $port; continuing with remaining boards."
        fi

    done

    if [[ $flashed_count -eq 0 ]]; then
        echo "No supported Arduino boards were flashed."
        exit 1
    fi

    echo
    echo "[4/4] Flash complete for $flashed_count board(s)."
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
stop_service_before_flash
ensure_arduino_cores
ensure_libraries
detect_boards

R3_MEGA_BOARD_COUNT="$(count_r3_and_mega_boards)"
if [[ "$R3_MEGA_BOARD_COUNT" -gt 0 ]]; then
    echo
    echo "Detected $R3_MEGA_BOARD_COUNT Arduino UNO R3 / Mega board(s)."
    echo "Arduino UNO R3 boards will use $R3_SKETCH based on UNO_R3_SCREEN_SIZE=$UNO_R3_SCREEN_SIZE."
    echo "Arduino Mega boards will use $R3_MEGA_SKETCH."
fi

flash_boards
