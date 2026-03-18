#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_PROJECT_DIR="$REPO_ROOT/debug_tools/FakeArduinoDisplay"
JAR_PATH="$JAVA_PROJECT_DIR/build/libs/UniversalMonitorControlCenter.jar"
CURRENT_USER="${USER:-$(id -un)}"

trim() {
    local value="${1:-}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
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

load_desktop_env_from_runtime_dir() {
    local runtime_dir="$1"

    if [[ -z "${XDG_RUNTIME_DIR:-}" ]] && [[ -d "$runtime_dir" ]]; then
        export XDG_RUNTIME_DIR="$runtime_dir"
    fi

    if [[ -z "${WAYLAND_DISPLAY:-}" ]] && [[ -n "${XDG_RUNTIME_DIR:-}" ]]; then
        local candidate
        for candidate in "$XDG_RUNTIME_DIR"/wayland-*; do
            if [[ -S "$candidate" ]]; then
                export WAYLAND_DISPLAY="$(basename "$candidate")"
                export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"
                break
            fi
        done
    fi
}

load_desktop_env_from_session() {
    local session_id="$1"
    local uid="$2"
    local properties
    properties="$(loginctl show-session "$session_id" \
        -p Name \
        -p Type \
        -p Display \
        -p Remote \
        -p State 2>/dev/null || true)"
    [[ -n "$properties" ]] || return 1

    local session_user session_type session_display session_remote session_state
    session_user="$(trim "$(printf '%s\n' "$properties" | sed -n 's/^Name=//p' | head -n 1)")"
    session_type="$(trim "$(printf '%s\n' "$properties" | sed -n 's/^Type=//p' | head -n 1)")"
    session_display="$(trim "$(printf '%s\n' "$properties" | sed -n 's/^Display=//p' | head -n 1)")"
    session_remote="$(trim "$(printf '%s\n' "$properties" | sed -n 's/^Remote=//p' | head -n 1)")"
    session_state="$(trim "$(printf '%s\n' "$properties" | sed -n 's/^State=//p' | head -n 1)")"

    [[ "$session_user" == "$CURRENT_USER" ]] || return 1
    [[ "$session_remote" == "no" ]] || return 1
    [[ "$session_state" == "active" || "$session_state" == "online" ]] || return 1

    if [[ -n "$session_display" ]] && [[ -z "${DISPLAY:-}" ]]; then
        export DISPLAY="$session_display"
    fi

    if [[ "$session_type" == "wayland" || "$session_type" == "x11" ]]; then
        export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-$session_type}"
    fi

    local runtime_dir="/run/user/$uid"
    load_desktop_env_from_runtime_dir "$runtime_dir"

    if [[ -z "${XAUTHORITY:-}" ]]; then
        local candidate
        for candidate in \
            "$HOME/.Xauthority" \
            "$runtime_dir/gdm/Xauthority" \
            "$runtime_dir/.mutter-Xwaylandauth."* \
            "$runtime_dir/xauth_"* \
            "/tmp/xauth-${CURRENT_USER}-"* \
            "/tmp/xauth-"*
        do
            if [[ -f "$candidate" ]]; then
                export XAUTHORITY="$candidate"
                break
            fi
        done
    fi

    if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
        return 0
    fi

    return 1
}

ensure_graphical_env() {
    if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
        if [[ -z "${XAUTHORITY:-}" && -f "$HOME/.Xauthority" ]]; then
            export XAUTHORITY="$HOME/.Xauthority"
        fi
        return 0
    fi

    local uid session_ids session_id
    uid="$(id -u)"
    session_ids="$(loginctl list-sessions --no-legend 2>/dev/null | awk -v user="$CURRENT_USER" '$3 == user { print $1 }' || true)"

    for session_id in $session_ids; do
        if load_desktop_env_from_session "$session_id" "$uid"; then
            return 0
        fi
    done

    load_desktop_env_from_runtime_dir "/run/user/$uid"
    if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
        if [[ -z "${XAUTHORITY:-}" && -f "$HOME/.Xauthority" ]]; then
            export XAUTHORITY="$HOME/.Xauthority"
        fi
        return 0
    fi

    cat <<'EOF' >&2
Error: no graphical desktop session detected for Universal Monitor Control Center.
Try launching it from your KDE Plasma / Cinnamon app menu or terminal inside the desktop session.
If you are launching it from another shell, export DISPLAY (X11) or WAYLAND_DISPLAY + XDG_RUNTIME_DIR (Wayland), and XAUTHORITY if required.
EOF
    exit 1
}

ensure_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "Error: java not found. Install OpenJDK first."
        echo "Fedora: sudo dnf install -y java-21-openjdk-devel"
        echo "Debian/Ubuntu: sudo apt install -y openjdk-21-jdk"
        exit 1
    fi

    if JDK_HOME="$(pick_jdk_home)"; then
        export JAVA_HOME="$JDK_HOME"
    else
        echo "Error: no JDK with javac found."
        echo "Fedora: sudo dnf install -y java-21-openjdk-devel"
        echo "Debian/Ubuntu: sudo apt install -y openjdk-21-jdk"
        exit 1
    fi
}

build_jar_if_needed() {
    cd "$JAVA_PROJECT_DIR"
    chmod +x gradlew

    local needs_build=0
    if [[ ! -f "$JAR_PATH" ]]; then
        needs_build=1
    elif find src/main build.gradle.kts settings.gradle.kts gradle -type f -newer "$JAR_PATH" -print -quit 2>/dev/null | grep -q .; then
        needs_build=1
    fi

    if [[ "$needs_build" -eq 1 ]]; then
        echo "Building Universal Monitor Control Center jar..."
        ./gradlew --quiet fatJar
    fi
}

ensure_graphical_env
ensure_java
build_jar_if_needed

echo "Launching $JAR_PATH"
exec java -jar "$JAR_PATH"
