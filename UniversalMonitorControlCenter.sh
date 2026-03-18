#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [[ -L "$SCRIPT_SOURCE" ]]; do
    SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)"
    SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
    [[ "$SCRIPT_SOURCE" != /* ]] && SCRIPT_SOURCE="$SCRIPT_DIR/$SCRIPT_SOURCE"
done
REPO_ROOT="$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)"
JAVA_PROJECT_DIR="$REPO_ROOT/debug_tools/FakeArduinoDisplay"
JAR_PATH="$JAVA_PROJECT_DIR/build/libs/UniversalMonitorControlCenter.jar"
CURRENT_USER="${SUDO_USER:-${USER:-$(id -un)}}"
CURRENT_UID="$(id -u)"

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

ensure_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "Error: java not found. Install OpenJDK first."
        echo "Fedora: sudo dnf install -y java-21-openjdk-devel"
        exit 1
    fi

    if JDK_HOME="$(pick_jdk_home)"; then
        export JAVA_HOME="$JDK_HOME"
    else
        echo "Error: no JDK with javac found."
        echo "Fedora: sudo dnf install -y java-21-openjdk-devel"
        exit 1
    fi
}

prime_gui_env() {
    local runtime_dir="/run/user/$CURRENT_UID"
    local candidate

    [[ -z "${XDG_RUNTIME_DIR:-}" && -d "$runtime_dir" ]] && export XDG_RUNTIME_DIR="$runtime_dir"

    if [[ -z "${WAYLAND_DISPLAY:-}" && -n "${XDG_RUNTIME_DIR:-}" ]]; then
        for candidate in "$XDG_RUNTIME_DIR"/wayland-*; do
            if [[ -S "$candidate" ]]; then
                export WAYLAND_DISPLAY="$(basename "$candidate")"
                export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"
                break
            fi
        done
    fi

    if [[ -z "${DISPLAY:-}" ]]; then
        if [[ -S /tmp/.X11-unix/X0 ]]; then
            export DISPLAY=:0
        elif [[ -S /tmp/.X11-unix/X1 ]]; then
            export DISPLAY=:1
        fi
    fi

    if [[ -z "${XAUTHORITY:-}" ]]; then
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

    export _JAVA_AWT_WM_NONREPARENTING=1
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

ensure_java
prime_gui_env
build_jar_if_needed

echo "Launching $JAR_PATH"
echo "Using DISPLAY=${DISPLAY:-<empty>} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-<empty>} XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-<empty>} XAUTHORITY=${XAUTHORITY:-<empty>}"
exec java -Djava.awt.headless=false -jar "$JAR_PATH"
