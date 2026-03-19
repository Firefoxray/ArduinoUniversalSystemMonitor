#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAVA_HELPER="$REPO_ROOT/lib/java_control_center.sh"

# shellcheck source=../../lib/java_control_center.sh
source "$JAVA_HELPER"

java_control_center_ensure_jdks
export JAVA_HOME="$JAVA_CONTROL_CENTER_BUILD_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using build JDK=$JAVA_CONTROL_CENTER_BUILD_JDK"
echo "Using runtime JDK=$JAVA_CONTROL_CENTER_RUNTIME_JDK"

./gradlew run
