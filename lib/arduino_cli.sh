#!/usr/bin/env bash

ensure_arduino_cli() {
    local local_bin_dir="${ARDUINO_CLI_BINDIR:-$HOME/.local/bin}"
    local local_cli="$local_bin_dir/arduino-cli"
    local tmp_dir=""

    export PATH="$local_bin_dir:$PATH"

    if command -v arduino-cli >/dev/null 2>&1; then
        echo "arduino-cli already installed at $(command -v arduino-cli)."
        return 0
    fi

    if ! command -v curl >/dev/null 2>&1; then
        echo "Error: curl is required to install arduino-cli." >&2
        return 1
    fi

    echo "arduino-cli not found; installing it with the official Arduino installer..."
    mkdir -p "$local_bin_dir"
    tmp_dir="$(mktemp -d)"

    if ! curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh \
        | BINDIR="$tmp_dir" sh; then
        rm -rf "$tmp_dir"
        echo "Failed to download or run the official arduino-cli installer." >&2
        return 1
    fi

    if [[ ! -x "$tmp_dir/arduino-cli" ]]; then
        rm -rf "$tmp_dir"
        echo "The arduino-cli installer completed without producing a binary." >&2
        return 1
    fi

    install -m 755 "$tmp_dir/arduino-cli" "$local_cli.new"
    mv -f "$local_cli.new" "$local_cli"
    rm -rf "$tmp_dir"

    if ! "$local_cli" version >/dev/null 2>&1; then
        echo "arduino-cli was installed to $local_cli but failed its version check." >&2
        return 1
    fi

    echo "arduino-cli installed to $local_cli"
}
