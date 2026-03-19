#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/project_paths.sh"
PROJECT_DIR="$(resolve_project_dir "${PROJECT_DIR:-$SCRIPT_DIR}" "${BASH_SOURCE[0]}")"
SERVICE_NAME="${SERVICE_NAME:-arduino-monitor.service}"
VENV_DIR="$PROJECT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python3"
PIP_BIN="$VENV_DIR/bin/pip"
REPO_USER=""
REPO_GROUP=""

have_command() {
    command -v "$1" >/dev/null 2>&1
}

detect_distro() {
    if [[ -f /etc/fedora-release ]]; then
        DISTRO="fedora"
    elif [[ -f /etc/debian_version ]]; then
        DISTRO="debian"
    elif [[ -f /etc/arch-release ]]; then
        DISTRO="arch"
    else
        DISTRO="unknown"
    fi
}

install_missing_prereqs() {
    detect_distro

    if have_command git && have_command python3; then
        return 0
    fi

    echo "Missing git/python3 detected, attempting install for $DISTRO..."

    case "$DISTRO" in
        fedora)
            sudo dnf install -y python3 python3-pip python3-virtualenv git
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3 python3-pip python3-venv git
            ;;
        arch)
            sudo pacman -Sy --noconfirm python python-pip git
            ;;
        *)
            echo "Unsupported distro for automatic dependency install."
            echo "Install git + python3 manually, then rerun update.sh"
            exit 1
            ;;
    esac

    if ! have_command git || ! have_command python3; then
        echo "Failed to install required prerequisites (git/python3)."
        exit 1
    fi
}

refresh_python_paths() {
    VENV_DIR="$PROJECT_DIR/.venv"
    PYTHON_BIN="$VENV_DIR/bin/python3"
    PIP_BIN="$VENV_DIR/bin/pip"
}

venv_needs_rebuild() {
    if [[ ! -d "$VENV_DIR" ]]; then
        return 0
    fi

    if [[ ! -x "$PYTHON_BIN" ]]; then
        echo "Detected missing venv interpreter at $PYTHON_BIN"
        return 0
    fi

    if [[ -f "$PIP_BIN" ]]; then
        local pip_shebang
        pip_shebang="$(head -n 1 "$PIP_BIN" 2>/dev/null || true)"
        if [[ "$pip_shebang" == '#!'* ]]; then
            local pip_python="${pip_shebang#\#!}"
            if [[ "$pip_python" != "$PYTHON_BIN" ]]; then
                echo "Detected relocated virtual environment (pip points to $pip_python)."
                return 0
            fi
        fi
    fi

    return 1
}

rebuild_venv_if_needed() {
    if venv_needs_rebuild; then
        echo "Rebuilding Python virtual environment inside $VENV_DIR ..."
        rm -rf "$VENV_DIR"
        run_as_repo_user "python3 -m venv $(printf '%q' "$VENV_DIR")"
        refresh_python_paths
    fi
}

detect_repo_owner() {
    local owner_source="$PROJECT_DIR"
    if [[ -e "$PROJECT_DIR/.git" ]]; then
        owner_source="$PROJECT_DIR/.git"
    fi

    REPO_USER="$(stat -c '%U' "$owner_source")"
    REPO_GROUP="$(stat -c '%G' "$owner_source")"

    if [[ -z "$REPO_USER" || "$REPO_USER" == "UNKNOWN" ]]; then
        REPO_USER="${SUDO_USER:-${USER:-root}}"
    fi
    if [[ -z "$REPO_GROUP" || "$REPO_GROUP" == "UNKNOWN" ]]; then
        REPO_GROUP="$REPO_USER"
    fi
}

JAVA_HELPER="$PROJECT_DIR/lib/java_control_center.sh"
# shellcheck source=lib/java_control_center.sh
source "$JAVA_HELPER"

run_as_repo_user() {
    local command_text="$1"

    if [[ "$REPO_USER" == "root" || "$(id -un)" == "$REPO_USER" ]]; then
        bash -lc "$command_text"
    else
        sudo -u "$REPO_USER" bash -lc "cd $(printf '%q' "$PROJECT_DIR") && $command_text"
    fi
}

build_control_center() {
    local build_command="cd debug_tools/FakeArduinoDisplay && ./gradlew fatJar installDist"
    local jdk_home=""

    if java_control_center_ensure_jdks; then
        jdk_home="$JAVA_CONTROL_CENTER_BUILD_JDK"
        echo "Using JAVA_HOME=$jdk_home for Control Center rebuild."
        run_as_repo_user "export JAVA_HOME=$(printf '%q' "$jdk_home"); export PATH=\$JAVA_HOME/bin:\$PATH; $build_command"
    else
        echo "Skipping Java rebuild because the required JDKs are unavailable."
    fi
}

fix_repo_ownership() {
    if [[ "$(id -u)" -eq 0 && -n "$REPO_USER" && "$REPO_USER" != "root" ]]; then
        chown -R "$REPO_USER:$REPO_GROUP" "$PROJECT_DIR"
    fi
}

echo "==== Ray Co Arduino Monitor Updater ===="

install_missing_prereqs

if [[ ! -d "$PROJECT_DIR/.git" ]]; then
    echo "Warning: $PROJECT_DIR is not a git repository."
    if [[ -d "$SCRIPT_DIR/.git" ]]; then
        echo "Using script location repo instead: $SCRIPT_DIR"
        PROJECT_DIR="$SCRIPT_DIR"
        refresh_python_paths
    else
        echo "Cloning fresh repository into $PROJECT_DIR ..."
        rm -rf "$PROJECT_DIR"
        git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git "$PROJECT_DIR"
        refresh_python_paths
    fi
fi

detect_repo_owner
cd "$PROJECT_DIR"

echo "[1/7] Pulling latest changes from GitHub..."
run_as_repo_user "git pull origin main"
fix_repo_ownership

echo "[2/7] Ensuring Python virtual environment exists..."
rebuild_venv_if_needed

echo "[3/7] Updating Python packaging tools..."
run_as_repo_user "$(printf '%q' "$PYTHON_BIN") -m pip install --upgrade pip"

echo "[4/7] Installing/updating Python requirements..."
if [[ -f requirements.txt ]]; then
    run_as_repo_user "$(printf '%q' "$PIP_BIN") install -r requirements.txt"
    echo "Requirements updated."
else
    run_as_repo_user "$(printf '%q' "$PIP_BIN") install psutil pyserial"
    echo "requirements.txt missing, installed fallback dependencies (psutil, pyserial)."
fi

echo "[5/7] Making sure scripts are executable..."
chmod +x UniversalArduinoMonitor.py 2>/dev/null || true
chmod +x install.sh 2>/dev/null || true
chmod +x update.sh 2>/dev/null || true
chmod +x uninstall_monitor.sh 2>/dev/null || true
chmod +x install_arduinos.sh 2>/dev/null || true
chmod +x arduino_install.sh 2>/dev/null || true
chmod +x UniversalMonitorControlCenter.sh 2>/dev/null || true
chmod +x install_control_center_desktop.sh 2>/dev/null || true

echo "[6/7] Rebuilding Java Control Center artifacts..."
if [[ -f debug_tools/FakeArduinoDisplay/gradlew ]]; then
    chmod +x debug_tools/FakeArduinoDisplay/gradlew
    build_control_center
else
    echo "Skipping Java rebuild because debug_tools/FakeArduinoDisplay/gradlew is missing."
fi
fix_repo_ownership

echo "[7/7] Restarting monitor service..."
sudo systemctl restart "$SERVICE_NAME"

echo
echo "==== UPDATE COMPLETE ===="
echo "Repo: $PROJECT_DIR"
echo "Repo owner used for git/build steps: $REPO_USER"
echo "Virtual environment: $VENV_DIR"
echo "Service: $SERVICE_NAME"
echo
sudo systemctl status "$SERVICE_NAME" --no-pager
