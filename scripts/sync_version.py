#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = REPO_ROOT / 'VERSION'
ARDUINO_HEADERS = [
    REPO_ROOT / 'R4_WIFI35' / 'app_version.generated.h',
    REPO_ROOT / 'R3_MonitorScreen28' / 'app_version.generated.h',
    REPO_ROOT / 'R3_MonitorScreen35' / 'app_version.generated.h',
    REPO_ROOT / 'R3_MEGA_MonitorScreen35' / 'app_version.generated.h',
]

def load_version() -> str:
    value = VERSION_FILE.read_text(encoding='utf-8').strip()
    if not value:
        raise SystemExit(f'Version file is empty: {VERSION_FILE}')
    return value


def write_if_changed(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text(encoding='utf-8') if path.exists() else None
    if existing == content:
        return False
    path.write_text(content, encoding='utf-8')
    return True


def sync_generated_files(version: str) -> list[str]:
    changed: list[str] = []
    escaped = version.replace('\\', '\\\\').replace('"', '\\"')
    header = f'#pragma once\n\n#define APP_VERSION "{escaped}"\n'
    for header_path in ARDUINO_HEADERS:
        if write_if_changed(header_path, header):
            changed.append(str(header_path.relative_to(REPO_ROOT)))
    return changed


def main() -> None:
    parser = argparse.ArgumentParser(description='Sync generated Arduino version files from the repo VERSION file.')
    parser.add_argument('--print-version', action='store_true')
    parser.add_argument('--sync', action='store_true')
    args = parser.parse_args()
    version = load_version()
    if args.print_version:
        print(version)
    if args.sync:
        changed = sync_generated_files(version)
        if changed:
            print('Synced: ' + ', '.join(changed))
        else:
            print('Generated version files already up to date.')
    if not args.print_version and not args.sync:
        print(version)


if __name__ == '__main__':
    main()
