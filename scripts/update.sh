#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/project_paths.sh"
PROJECT_DIR="$(resolve_project_dir "${PROJECT_DIR:-$SCRIPT_DIR/..}" "${BASH_SOURCE[0]}")"
SERVICE_NAME="${SERVICE_NAME:-arduino-monitor.service}"
UPDATE_SOURCE_FILE="${UPDATE_SOURCE_FILE:-.last_update_source}"
VENV_DIR="$PROJECT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python3"
PIP_BIN="$VENV_DIR/bin/pip"
REPO_USER=""
REPO_GROUP=""
GIT_UPDATED=0
VENV_REBUILT=0

have_command() {
    command -v "$1" >/dev/null 2>&1
}

service_installed() {
    systemctl cat "$SERVICE_NAME" >/dev/null 2>&1
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

ensure_system_runtime_dependencies() {
    detect_distro

    if /usr/bin/env python3 -c 'import psutil, serial' >/dev/null 2>&1; then
        return 0
    fi

    echo "Core Python runtime dependencies are missing; attempting repair for $DISTRO..."
    case "$DISTRO" in
        fedora)
            sudo dnf install -y python3-psutil python3-pyserial
            ;;
        debian)
            sudo apt update
            sudo apt install -y python3-psutil python3-serial
            ;;
        arch)
            sudo pacman -Sy --noconfirm python-psutil python-pyserial
            ;;
        *)
            echo "Unable to auto-repair psutil/pyserial on this distro."
            return 1
            ;;
    esac

    /usr/bin/env python3 -c 'import psutil, serial'
}

refresh_python_paths() {
    VENV_DIR="$PROJECT_DIR/.venv"
    PYTHON_BIN="$VENV_DIR/bin/python3"
    PIP_BIN="$VENV_DIR/bin/pip"
}

venv_needs_rebuild() {
    if [[ ! -d "$VENV_DIR" ]]; then
        echo "Virtual environment is missing."
        return 0
    fi

    if [[ ! -x "$PYTHON_BIN" ]]; then
        echo "Detected missing venv interpreter at $PYTHON_BIN"
        return 0
    fi

    if [[ ! -x "$PIP_BIN" ]]; then
        echo "Detected missing venv pip launcher at $PIP_BIN"
        return 0
    fi

    if ! "$PYTHON_BIN" -m pip --version >/dev/null 2>&1; then
        echo "Detected venv interpreter without a working pip module."
        return 0
    fi

    local pip_shebang
    pip_shebang="$(head -n 1 "$PIP_BIN" 2>/dev/null || true)"
    if [[ "$pip_shebang" == '#!'* ]]; then
        local pip_python="${pip_shebang#\#!}"
        if [[ "$pip_python" != "$PYTHON_BIN" ]]; then
            echo "Detected relocated virtual environment (pip points to $pip_python)."
            return 0
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
        VENV_REBUILT=1
    fi

    if ! "$PYTHON_BIN" -m pip --version >/dev/null 2>&1; then
        echo "pip is still unavailable in the venv; trying ensurepip..."
        run_as_repo_user "$(printf '%q' "$PYTHON_BIN") -m ensurepip --upgrade"
    fi

    "$PYTHON_BIN" -m pip --version >/dev/null
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

run_as_repo_user() {
    local command_text="$1"

    if [[ "$REPO_USER" == "root" || "$(id -un)" == "$REPO_USER" ]]; then
        bash -lc "$command_text"
    else
        sudo -u "$REPO_USER" bash -lc "cd $(printf '%q' "$PROJECT_DIR") && $command_text"
    fi
}

git_remote_has_updates() {
    local branch="${1:-main}"
    local local_head remote_line remote_head

    local_head="$(run_as_repo_user "git rev-parse HEAD")"
    remote_line="$(run_as_repo_user "git ls-remote --heads origin $(printf '%q' "$branch")")"
    remote_head="${remote_line%%[[:space:]]*}"

    if [[ -z "$local_head" || -z "$remote_head" ]]; then
        echo "Unable to determine local or remote git commit for branch $branch." >&2
        return 2
    fi

    [[ "$local_head" != "$remote_head" ]]
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

refresh_cli_launchers() {
    local user_name="${SUDO_USER:-${USER:-$(id -un)}}"
    local user_home
    local user_bin
    local launchers=(uasm uasm-fetch uasmfetch rayfetch uasm-update)
    local launcher
    local run_as_user_cmd=(bash -lc)

    user_home="$(getent passwd "$user_name" | cut -d: -f6)"
    [[ -z "$user_home" ]] && user_home="$HOME"
    user_bin="$user_home/.local/bin"

    if [[ "$user_name" != "$(id -un)" ]]; then
        run_as_user_cmd=(sudo -u "$user_name" bash -lc)
    fi

    "${run_as_user_cmd[@]}" "mkdir -p $(printf '%q' "$user_bin")"

    # One canonical launcher lives in the repo. Historical command names are
    # symlinks to it, and the launcher dispatches based on argv[0]. This avoids
    # duplicated generated shell scripts and survives repo moves cleanly.
    for launcher in "${launchers[@]}"; do
        local launcher_target="$user_bin/$launcher"
        "${run_as_user_cmd[@]}" "ln -sfn $(printf '%q' "$PROJECT_DIR/uasm") $(printf '%q' "$launcher_target")"
    done

    if ! sudo -u "$user_name" bash -lc 'echo "$PATH"' | tr ':' '\n' | grep -qx "$user_bin"; then
        local shell_rc="$user_home/.bashrc"
        if [[ "${SHELL:-}" == *zsh ]]; then
            shell_rc="$user_home/.zshrc"
        fi
        if [[ -w "$shell_rc" || ! -e "$shell_rc" ]]; then
            echo '' >> "$shell_rc"
            echo '# ArduinoUniversalSystemMonitor CLI aliases' >> "$shell_rc"
            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$shell_rc"
            chown "$user_name":"$user_name" "$shell_rc" 2>/dev/null || true
            echo "Added ~/.local/bin to PATH in $shell_rc"
        else
            echo "Could not auto-update PATH in $shell_rc. Add this manually:"
            echo '  export PATH="$HOME/.local/bin:$PATH"'
        fi
    fi
}

echo "==== Ray Co Arduino Monitor Updater ===="

install_missing_prereqs
ensure_system_runtime_dependencies

if [[ ! -d "$PROJECT_DIR/.git" ]]; then
    echo "Warning: $PROJECT_DIR is not a git repository."
    if [[ -d "$SCRIPT_DIR/../.git" ]]; then
        echo "Using script location repo instead: $SCRIPT_DIR/.."
        PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
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

JAVA_HELPER="$PROJECT_DIR/lib/java_control_center.sh"
if [[ -f "$JAVA_HELPER" ]]; then
    # shellcheck source=lib/java_control_center.sh
    source "$JAVA_HELPER"
fi

echo "[1/7] Checking GitHub for new changes..."
if git_remote_has_updates main; then
    echo "Updates found on origin/main. Pulling latest changes from GitHub..."
    run_as_repo_user "git pull origin main"
    GIT_UPDATED=1
    fix_repo_ownership
else
    status=$?
    if [[ $status -eq 2 ]]; then
        exit 1
    fi
    echo "Project is already up to date on origin/main. Continuing with health/repair checks."
fi
run_as_repo_user "printf '%s\n' main > $(printf '%q' "$UPDATE_SOURCE_FILE")"

echo "[2/7] Ensuring Python virtual environment is healthy..."
rebuild_venv_if_needed

echo "[3/7] Verifying Python packaging tools..."
if [[ "$VENV_REBUILT" -eq 1 ]]; then
    run_as_repo_user "$(printf '%q' "$PYTHON_BIN") -m pip install --upgrade pip"
else
    "$PYTHON_BIN" -m pip --version
fi

echo "[4/7] Installing/updating Python requirements..."
if [[ -f requirements.txt ]]; then
    run_as_repo_user "$(printf '%q' "$PYTHON_BIN") -m pip install -r requirements.txt"
    echo "Requirements verified."
else
    run_as_repo_user "$(printf '%q' "$PYTHON_BIN") -m pip install psutil pyserial"
    echo "requirements.txt missing, verified fallback dependencies (psutil, pyserial)."
fi

echo "[5/7] Refreshing scripts and CLI launchers..."
chmod +x UniversalArduinoMonitor.py scripts/UniversalArduinoMonitor.py 2>/dev/null || true
chmod +x uasm uasm-fetch uasmfetch rayfetch uasm-update 2>/dev/null || true
chmod +x install.sh scripts/install.sh 2>/dev/null || true
chmod +x update.sh scripts/update.sh 2>/dev/null || true
chmod +x uninstall_monitor.sh scripts/uninstall_monitor.sh 2>/dev/null || true
chmod +x install_arduinos.sh scripts/install_arduinos.sh 2>/dev/null || true
chmod +x scripts/arduino/install_arduinos.sh scripts/arduino/sync_version.py scripts/sync_version.py 2>/dev/null || true
chmod +x arduino_install.sh scripts/arduino_install.sh 2>/dev/null || true
chmod +x UniversalMonitorControlCenter.sh scripts/UniversalMonitorControlCenter.sh 2>/dev/null || true
chmod +x install_control_center_desktop.sh scripts/install_control_center_desktop.sh 2>/dev/null || true
refresh_cli_launchers

echo "[6/7] Checking Control Center artifacts..."
CONTROL_CENTER_JAR="$PROJECT_DIR/debug_tools/FakeArduinoDisplay/build/libs/UniversalMonitorControlCenter.jar"
if [[ "$GIT_UPDATED" -eq 1 || ! -f "$CONTROL_CENTER_JAR" ]]; then
    if [[ -f debug_tools/FakeArduinoDisplay/gradlew && -f "$JAVA_HELPER" ]]; then
        chmod +x debug_tools/FakeArduinoDisplay/gradlew
        build_control_center
    else
        echo "Skipping Java rebuild because the Control Center build files are unavailable."
    fi
else
    echo "No Control Center source update detected and existing jar is present; skipping rebuild."
fi
fix_repo_ownership

echo "[7/7] Checking monitor service..."
if service_installed; then
    sudo systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
    sudo systemctl restart "$SERVICE_NAME"
else
    echo "$SERVICE_NAME is not installed on this machine; skipping service restart."
fi

echo
echo "==== UPDATE / REPAIR COMPLETE ===="
echo "Repo: $PROJECT_DIR"
echo "Repo owner used for git/build steps: $REPO_USER"
echo "Virtual environment: $VENV_DIR"
echo "Git changes pulled: $GIT_UPDATED"
echo "Virtual environment rebuilt: $VENV_REBUILT"
echo "Service: $SERVICE_NAME"
echo
if service_installed; then
    sudo systemctl status "$SERVICE_NAME" --no-pager || true
fi
