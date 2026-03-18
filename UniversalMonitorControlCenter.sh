#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_PROJECT_DIR="$REPO_ROOT/debug_tools/FakeArduinoDisplay"
JAR_PATH="$JAVA_PROJECT_DIR/build/libs/UniversalMonitorControlCenter.jar"

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

ensure_java
build_jar_if_needed

echo "Launching $JAR_PATH"
exec java -jar "$JAR_PATH"
