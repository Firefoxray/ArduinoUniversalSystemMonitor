#!/usr/bin/env bash

JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION="21"
JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION="25"

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

java_control_center_expected_packages() {
    local manager="$1"
    local version="$2"

    case "$manager" in
        apt-get)
            printf '%s\n' "openjdk-${version}-jdk"
            ;;
        dnf)
            printf '%s\n' "java-${version}-openjdk-devel"
            ;;
        pacman)
            if [[ "$version" == "$JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION" ]]; then
                printf '%s\n' "jdk-openjdk"
            fi
            printf '%s\n' "jdk${version}-openjdk"
            ;;
        zypper)
            printf '%s\n' "java-${version}-openjdk-devel"
            printf '%s\n' "java-${version}-openjdk"
            ;;
    esac
}

java_control_center_pick_install_package() {
    local manager="$1"
    local version="$2"
    local package_name=""

    while IFS= read -r package_name; do
        [[ -z "$package_name" ]] && continue
        if java_control_center_package_exists "$manager" "$package_name"; then
            printf '%s\n' "$package_name"
            return 0
        fi
    done < <(java_control_center_expected_packages "$manager" "$version")

    return 1
}

java_control_center_install_jdks() {
    local manager=""
    local packages=()
    local package_name=""
    local missing_descriptions=()
    local version

    if ! manager="$(java_control_center_detect_pkg_manager)"; then
        echo "Warning: unsupported Linux package manager. Please install OpenJDK ${JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION}+ and OpenJDK ${JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION}+ manually."
        return 1
    fi

    for version in "$JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION" "$JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION"; do
        if package_name="$(java_control_center_pick_install_package "$manager" "$version")"; then
            packages+=("$package_name")
        else
            missing_descriptions+=("OpenJDK $version")
        fi
    done

    if [[ ${#missing_descriptions[@]} -gt 0 ]]; then
        printf 'Warning: could not find installable packages for %s on this distro.\n' "$(IFS=', '; echo "${missing_descriptions[*]}")"
        return 1
    fi

    echo "Installing Java dependencies with $manager: ${packages[*]}"
    if ! java_control_center_install_packages "$manager" "${packages[@]}"; then
        echo "Warning: automatic Java installation failed. Install the packages above manually and re-run the launcher."
        return 1
    fi

    return 0
}

java_control_center_collect_jdk_candidates() {
    local version="$1"
    local javac_path=""

    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        printf '%s\n' "$JAVA_HOME"
    fi

    if command -v update-alternatives >/dev/null 2>&1; then
        update-alternatives --list javac 2>/dev/null | while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            dirname "$(dirname "$(readlink -f "$path")")"
        done
    fi

    if command -v archlinux-java >/dev/null 2>&1; then
        archlinux-java status 2>/dev/null | awk '/^[[:space:]]*java-/ {print $1}' | while IFS= read -r name; do
            [[ -d "/usr/lib/jvm/$name" ]] && printf '%s\n' "/usr/lib/jvm/$name"
        done
    fi

    for candidate in \
        "/usr/lib/jvm/java-${version}-openjdk" \
        "/usr/lib/jvm/java-${version}-openjdk-amd64" \
        "/usr/lib/jvm/java-${version}-openjdk-x86_64" \
        "/usr/lib/jvm/java-${version}-openjdk-$(uname -m)" \
        "/usr/lib/jvm/jdk-${version}" \
        "/usr/lib/jvm/jdk${version}-openjdk" \
        "/usr/lib/jvm/default-java"
    do
        [[ -d "$candidate" ]] && printf '%s\n' "$candidate"
    done

    javac_path="$(command -v javac 2>/dev/null || true)"
    if [[ -n "$javac_path" ]]; then
        dirname "$(dirname "$(readlink -f "$javac_path")")"
    fi
}

java_control_center_pick_jdk_home() {
    local version="$1"
    local candidate=""

    while IFS= read -r candidate; do
        [[ -z "$candidate" ]] && continue
        if [[ -x "$candidate/bin/javac" ]]; then
            if [[ "$candidate" == *"${version}"* ]]; then
                printf '%s\n' "$candidate"
                return 0
            fi
        fi
    done < <(java_control_center_collect_jdk_candidates "$version" | awk '!seen[$0]++')

    while IFS= read -r candidate; do
        [[ -z "$candidate" ]] && continue
        if [[ -x "$candidate/bin/javac" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done < <(java_control_center_collect_jdk_candidates "$version" | awk '!seen[$0]++')

    return 1
}

java_control_center_ensure_jdks() {
    local build_jdk=""
    local runtime_jdk=""

    if build_jdk="$(java_control_center_pick_jdk_home "$JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION")" \
        && runtime_jdk="$(java_control_center_pick_jdk_home "$JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION")"; then
        JAVA_CONTROL_CENTER_BUILD_JDK="$build_jdk"
        JAVA_CONTROL_CENTER_RUNTIME_JDK="$runtime_jdk"
        export JAVA_CONTROL_CENTER_BUILD_JDK JAVA_CONTROL_CENTER_RUNTIME_JDK
        return 0
    fi

    echo "OpenJDK ${JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION} (build) and OpenJDK ${JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION} (runtime) are required for the Control Center launcher."
    echo "Attempting to install them automatically..."
    java_control_center_install_jdks || true

    build_jdk="$(java_control_center_pick_jdk_home "$JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION" 2>/dev/null || true)"
    runtime_jdk="$(java_control_center_pick_jdk_home "$JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION" 2>/dev/null || true)"

    if [[ -n "$build_jdk" && -n "$runtime_jdk" ]]; then
        JAVA_CONTROL_CENTER_BUILD_JDK="$build_jdk"
        JAVA_CONTROL_CENTER_RUNTIME_JDK="$runtime_jdk"
        export JAVA_CONTROL_CENTER_BUILD_JDK JAVA_CONTROL_CENTER_RUNTIME_JDK
        return 0
    fi

    echo "Error: unable to find the required Java installations after the automatic install attempt."
    echo "Build JDK ${JAVA_CONTROL_CENTER_REQUIRED_BUILD_VERSION}: ${build_jdk:-missing}"
    echo "Runtime JDK ${JAVA_CONTROL_CENTER_REQUIRED_RUNTIME_VERSION}: ${runtime_jdk:-missing}"
    return 1
}
