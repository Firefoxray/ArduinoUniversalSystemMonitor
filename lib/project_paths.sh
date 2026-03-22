#!/usr/bin/env bash

resolve_script_path() {
    local source_path="$1"
    while [[ -L "$source_path" ]]; do
        local source_dir
        source_dir="$(cd -P "$(dirname "$source_path")" && pwd)"
        source_path="$(readlink "$source_path")"
        [[ "$source_path" != /* ]] && source_path="$source_dir/$source_path"
    done
    printf '%s\n' "$source_path"
}

script_dir_from_source() {
    local resolved_source
    resolved_source="$(resolve_script_path "$1")"
    cd -P "$(dirname "$resolved_source")" && pwd
}

find_repo_root() {
    local start_dir="$1"
    local cursor=""

    cursor="$(cd -P "$start_dir" 2>/dev/null && pwd)" || return 1
    while [[ -n "$cursor" && "$cursor" != "/" ]]; do
        if [[ -f "$cursor/README.md" && (( -f "$cursor/install.sh" && -f "$cursor/update.sh" ) || ( -f "$cursor/scripts/install.sh" && -f "$cursor/scripts/update.sh" )) ]]; then
            printf '%s\n' "$cursor"
            return 0
        fi
        cursor="$(dirname "$cursor")"
    done

    if [[ -f "/README.md" && (( -f "/install.sh" && -f "/update.sh" ) || ( -f "/scripts/install.sh" && -f "/scripts/update.sh" )) ]]; then
        printf '/\n'
        return 0
    fi

    return 1
}

project_version_file() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$project_dir/VERSION"
}

read_project_version() {
    local project_dir="${1:-$(pwd)}"
    local version_file
    version_file="$(project_version_file "$project_dir")"

    if [[ -f "$version_file" ]]; then
        local version
        version="$(tr -d '\r' < "$version_file" | head -n 1)"
        if [[ -n "$version" ]]; then
            printf '%s\n' "$version"
            return 0
        fi
    fi

    printf 'unknown version\n'
}

config_dir() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$project_dir/config"
}

monitor_default_config_path() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$(config_dir "$project_dir")/monitor_config.default.json"
}

monitor_shared_config_path() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$(config_dir "$project_dir")/monitor_config.json"
}

monitor_local_config_path() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$(config_dir "$project_dir")/monitor_config.local.json"
}

monitor_runtime_script_path() {
    local project_dir="${1:-$(pwd)}"
    printf '%s\n' "$project_dir/UniversalArduinoMonitor.py"
}

resolve_project_dir() {
    local preferred_dir="${1:-}"
    local script_source="${2:-}"
    local candidate=""
    local found=""

    if [[ -n "$preferred_dir" && -d "$preferred_dir" ]]; then
        found="$(find_repo_root "$preferred_dir" 2>/dev/null || true)"
        [[ -n "$found" ]] && { printf '%s\n' "$found"; return 0; }
    fi

    if [[ -n "$script_source" ]]; then
        candidate="$(script_dir_from_source "$script_source")"
        found="$(find_repo_root "$candidate" 2>/dev/null || true)"
        [[ -n "$found" ]] && { printf '%s\n' "$found"; return 0; }
    fi

    found="$(find_repo_root "$PWD" 2>/dev/null || true)"
    [[ -n "$found" ]] && { printf '%s\n' "$found"; return 0; }

    if [[ -n "$preferred_dir" ]]; then
        printf '%s\n' "$preferred_dir"
    elif [[ -n "$script_source" ]]; then
        script_dir_from_source "$script_source"
    else
        pwd
    fi
}
