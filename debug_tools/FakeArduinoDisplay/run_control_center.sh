#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
    echo "Error: java not found. Install OpenJDK (java + javac) first."
    exit 1
fi

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

if JDK_HOME="$(pick_jdk_home)"; then
    export JAVA_HOME="$JDK_HOME"
    echo "Using JAVA_HOME=$JAVA_HOME"
else
    echo "Error: no JDK with javac found."
    echo "Fedora: sudo dnf install -y java-21-openjdk-devel"
    echo "Debian/Ubuntu: sudo apt install -y openjdk-21-jdk"
    exit 1
fi

./gradlew run
