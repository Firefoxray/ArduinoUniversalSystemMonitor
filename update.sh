#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$SCRIPT_DIR}"
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

pick_jdk_home() {
    local candidate

    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/javac" ]]; then
        printf '%s' "$JAVA_HOME"
        return 0
    fi

    for candidate in \
        /usr/lib/jvm/java-21-openjdk \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/default-java
    do
        if [[ -x "$candidate/bin/javac" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done

    local javac_path
    javac_path="$(command -v javac 2>/dev/null || true)"
    if [[ -n "$javac_path" ]]; then
        dirname "$(dirname "$(readlink -f "$javac_path")")"
        return 0
    fi

    return 1
}

build_control_center() {
    local build_command="cd debug_tools/FakeArduinoDisplay && ./gradlew fatJar installDist"
    local jdk_home=""

    if ! have_command java; then
        echo "Skipping Java rebuild because 'java' is not installed."
        return 0
    fi

    if jdk_home="$(pick_jdk_home)"; then
        echo "Using JAVA_HOME=$jdk_home for Control Center rebuild."
        run_as_repo_user "export JAVA_HOME=$(printf '%q' "$jdk_home"); export PATH=\$JAVA_HOME/bin:\$PATH; $build_command"
    else
        echo "Skipping Java rebuild because no JDK with javac was found."
        echo "Install one with:"
        echo "  Fedora: sudo dnf install -y java-21-openjdk-devel"
        echo "  Debian/Ubuntu: sudo apt install -y openjdk-21-jdk"
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
if [[ ! -d "$VENV_DIR" ]]; then
    run_as_repo_user "python3 -m venv $(printf '%q' "$VENV_DIR")"
fi

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
