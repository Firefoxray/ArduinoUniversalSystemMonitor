#!/usr/bin/env bash

JAVA_CONTROL_CENTER_REQUIRED_VERSION="25"
JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION="21"

java_control_center_detect_pkg_manager() {
    local manager
    for manager in apt-get dnf pacman zypper; do
        if command -v "$manager" >/dev/null 2>&1; then
            printf '%s\n' "$manager"
            return 0
        fi
    done
    return 1
}

java_control_center_run_privileged() {
    if [[ "$(id -u)" -eq 0 ]]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        return 1
    fi
}

java_control_center_package_exists() {
    local manager="$1"
    local package_name="$2"

    case "$manager" in
        apt-get)
            apt-cache show "$package_name" >/dev/null 2>&1
            ;;
        dnf)
            dnf list --available "$package_name" >/dev/null 2>&1 || dnf list --installed "$package_name" >/dev/null 2>&1
            ;;
        pacman)
            pacman -Si "$package_name" >/dev/null 2>&1 || pacman -Q "$package_name" >/dev/null 2>&1
            ;;
        zypper)
            zypper --non-interactive info "$package_name" >/dev/null 2>&1
            ;;
        *)
            return 1
            ;;
    esac
}

java_control_center_install_packages() {
    local manager="$1"
    shift
    local packages=("$@")

    case "$manager" in
        apt-get)
            java_control_center_run_privileged apt-get update
            java_control_center_run_privileged apt-get install -y "${packages[@]}"
            ;;
        dnf)
            java_control_center_run_privileged dnf install -y "${packages[@]}"
            ;;
        pacman)
            java_control_center_run_privileged pacman -Sy --noconfirm "${packages[@]}"
            ;;
        zypper)
            java_control_center_run_privileged zypper --non-interactive install --no-confirm "${packages[@]}"
            ;;
        *)
            return 1
            ;;
    esac
}

java_control_center_pick_install_packages() {
    local manager="$1"
    local required_runtime=""
    local required_devel=""
    local fallback_runtime=""
    local fallback_devel=""

    case "$manager" in
        apt-get)
            required_devel="openjdk-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-jdk"
            fallback_devel="openjdk-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-jdk"
            if java_control_center_package_exists "$manager" "$required_devel"; then
                printf '%s\n' "$required_devel"
            elif java_control_center_package_exists "$manager" "$fallback_devel"; then
                printf '%s\n' "$fallback_devel"
            fi
            ;;
        dnf|zypper)
            required_runtime="java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk"
            required_devel="java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk-devel"
            fallback_runtime="java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk"
            fallback_devel="java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk-devel"

            if java_control_center_package_exists "$manager" "$required_runtime" && java_control_center_package_exists "$manager" "$required_devel"; then
                printf '%s\n' "$required_runtime"
                printf '%s\n' "$required_devel"
            elif java_control_center_package_exists "$manager" "$fallback_runtime" && java_control_center_package_exists "$manager" "$fallback_devel"; then
                printf '%s\n' "$fallback_runtime"
                printf '%s\n' "$fallback_devel"
            fi
            ;;
        pacman)
            if java_control_center_package_exists "$manager" "jdk-openjdk"; then
                printf '%s\n' "jdk-openjdk"
            elif java_control_center_package_exists "$manager" "jdk${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk"; then
                printf '%s\n' "jdk${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk"
            fi
            ;;
    esac
}

java_control_center_install_jdks() {
    local manager=""
    local packages=()

    if ! manager="$(java_control_center_detect_pkg_manager)"; then
        echo "Warning: unsupported Linux package manager. Install Java ${JAVA_CONTROL_CENTER_REQUIRED_VERSION} JDK manually (Fedora 44: sudo dnf install java-25-openjdk java-25-openjdk-devel)."
        return 1
    fi

    mapfile -t packages < <(java_control_center_pick_install_packages "$manager")

    if [[ ${#packages[@]} -eq 0 ]]; then
        echo "Warning: could not find installable OpenJDK packages via $manager."
        return 1
    fi

    echo "Installing Java dependencies with $manager: ${packages[*]}"
    if ! java_control_center_install_packages "$manager" "${packages[@]}"; then
        echo "Warning: automatic Java installation failed. On Fedora 44 run: sudo dnf install java-25-openjdk java-25-openjdk-devel"
        return 1
    fi

    return 0
}

java_control_center_extract_major_version() {
    local version_output="$1"
    local token=""
    token="$(awk -F '"' '/version/ {print $2; exit}' <<<"$version_output")"
    if [[ -z "$token" ]]; then
        token="$(awk '{for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+([.][0-9]+)*/) {print $i; exit}}' <<<"$version_output")"
    fi
    [[ -z "$token" ]] && return 1

    if [[ "$token" =~ ^1\.([0-9]+) ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi

    if [[ "$token" =~ ^([0-9]+) ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi

    return 1
}

java_control_center_version_of_bin() {
    local bin_path="$1"
    local out=""

    [[ -x "$bin_path" ]] || return 1
    out="$($bin_path -version 2>&1 | head -n 1)"
    java_control_center_extract_major_version "$out"
}

java_control_center_collect_java_homes() {
    local java_path=""
    local javac_path=""
    local candidate=""

    if [[ -n "${JAVA_HOME:-}" ]]; then
        printf '%s\n' "$JAVA_HOME"
    fi

    javac_path="$(command -v javac 2>/dev/null || true)"
    if [[ -n "$javac_path" ]]; then
        dirname "$(dirname "$(readlink -f "$javac_path")")"
    fi

    java_path="$(command -v java 2>/dev/null || true)"
    if [[ -n "$java_path" ]]; then
        dirname "$(dirname "$(readlink -f "$java_path")")"
    fi

    if command -v update-alternatives >/dev/null 2>&1; then
        update-alternatives --list javac 2>/dev/null | while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            dirname "$(dirname "$(readlink -f "$path")")"
        done
        update-alternatives --list java 2>/dev/null | while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            dirname "$(dirname "$(readlink -f "$path")")"
        done
    fi

    for candidate in \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk-amd64" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk-x86_64" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_REQUIRED_VERSION}-openjdk-$(uname -m)" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk-amd64" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk-x86_64" \
        "/usr/lib/jvm/java-${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION}-openjdk-$(uname -m)" \
        "/usr/lib/jvm/default-java"
    do
        [[ -d "$candidate" ]] && printf '%s\n' "$candidate"
    done
}

java_control_center_pick_build_jdk() {
    local home=""
    local major=""
    local fallback_home=""

    while IFS= read -r home; do
        [[ -z "$home" || ! -x "$home/bin/javac" ]] && continue
        major="$(java_control_center_version_of_bin "$home/bin/javac" 2>/dev/null || true)"
        [[ -z "$major" ]] && continue

        if (( major >= JAVA_CONTROL_CENTER_REQUIRED_VERSION )); then
            printf '%s\n' "$home"
            return 0
        fi

        if (( major >= JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION )) && [[ -z "$fallback_home" ]]; then
            fallback_home="$home"
        fi
    done < <(java_control_center_collect_java_homes | awk '!seen[$0]++')

    if [[ -n "$fallback_home" ]]; then
        printf '%s\n' "$fallback_home"
        return 0
    fi

    return 1
}

java_control_center_pick_runtime_jdk() {
    local preferred_home="$1"
    local major=""
    local home=""

    if [[ -n "$preferred_home" && -x "$preferred_home/bin/java" ]]; then
        major="$(java_control_center_version_of_bin "$preferred_home/bin/java" 2>/dev/null || true)"
        if [[ -n "$major" ]] && (( major >= JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION )); then
            printf '%s\n' "$preferred_home"
            return 0
        fi
    fi

    while IFS= read -r home; do
        [[ -z "$home" || ! -x "$home/bin/java" ]] && continue
        major="$(java_control_center_version_of_bin "$home/bin/java" 2>/dev/null || true)"
        [[ -z "$major" ]] && continue
        if (( major >= JAVA_CONTROL_CENTER_REQUIRED_VERSION )); then
            printf '%s\n' "$home"
            return 0
        fi
    done < <(java_control_center_collect_java_homes | awk '!seen[$0]++')

    while IFS= read -r home; do
        [[ -z "$home" || ! -x "$home/bin/java" ]] && continue
        major="$(java_control_center_version_of_bin "$home/bin/java" 2>/dev/null || true)"
        [[ -z "$major" ]] && continue
        if (( major >= JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION )); then
            printf '%s\n' "$home"
            return 0
        fi
    done < <(java_control_center_collect_java_homes | awk '!seen[$0]++')

    return 1
}

java_control_center_ensure_jdks() {
    local build_jdk=""
    local runtime_jdk=""
    local build_major=""
    local runtime_major=""

    build_jdk="$(java_control_center_pick_build_jdk 2>/dev/null || true)"
    runtime_jdk="$(java_control_center_pick_runtime_jdk "$build_jdk" 2>/dev/null || true)"

    if [[ -z "$build_jdk" || -z "$runtime_jdk" ]]; then
        echo "Control Center requires a Java JDK with javac and java. Fedora 44 recommendation:"
        echo "  sudo dnf install java-25-openjdk java-25-openjdk-devel"
        echo "Attempting automatic Java package installation..."
        java_control_center_install_jdks || true

        build_jdk="$(java_control_center_pick_build_jdk 2>/dev/null || true)"
        runtime_jdk="$(java_control_center_pick_runtime_jdk "$build_jdk" 2>/dev/null || true)"
    fi

    if [[ -z "$build_jdk" || -z "$runtime_jdk" ]]; then
        echo "Error: unable to find a usable Java installation for Control Center."
        echo "Expected: Java ${JAVA_CONTROL_CENTER_REQUIRED_VERSION}+ (Java ${JAVA_CONTROL_CENTER_OPTIONAL_FALLBACK_VERSION} optional fallback)."
        echo "Fedora 44 install command: sudo dnf install java-25-openjdk java-25-openjdk-devel"
        return 1
    fi

    build_major="$(java_control_center_version_of_bin "$build_jdk/bin/javac" 2>/dev/null || true)"
    runtime_major="$(java_control_center_version_of_bin "$runtime_jdk/bin/java" 2>/dev/null || true)"

    if [[ -n "$build_major" && "$build_major" -lt "$JAVA_CONTROL_CENTER_REQUIRED_VERSION" ]]; then
        echo "Warning: using Java $build_major build JDK fallback. Java ${JAVA_CONTROL_CENTER_REQUIRED_VERSION}+ is recommended."
    fi

    if [[ -n "$runtime_major" && "$runtime_major" -lt "$JAVA_CONTROL_CENTER_REQUIRED_VERSION" ]]; then
        echo "Warning: using Java $runtime_major runtime fallback. Java ${JAVA_CONTROL_CENTER_REQUIRED_VERSION}+ is recommended."
    fi

    JAVA_CONTROL_CENTER_BUILD_JDK="$build_jdk"
    JAVA_CONTROL_CENTER_RUNTIME_JDK="$runtime_jdk"
    export JAVA_CONTROL_CENTER_BUILD_JDK JAVA_CONTROL_CENTER_RUNTIME_JDK
    return 0
}
