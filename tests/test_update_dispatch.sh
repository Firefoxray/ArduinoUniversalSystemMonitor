#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/repo/scripts" "$tmp/bin"
cp "$root/uasm" "$tmp/repo/uasm"
printf 'raise RuntimeError("runtime must not load")\n' > "$tmp/repo/UniversalArduinoMonitor.py"
printf '#!/usr/bin/env bash\nprintf "repair reached\\n"\n' > "$tmp/repo/scripts/update.sh"
chmod +x "$tmp/repo/uasm"
ln -s "$tmp/repo/uasm" "$tmp/bin/uasm-update"
[[ "$("$tmp/repo/uasm" update)" == "repair reached" ]]
[[ "$("$tmp/bin/uasm-update")" == "repair reached" ]]
[[ "$("$tmp/repo/uasm" --update)" == "repair reached" ]]
echo "Update dispatch regression checks passed."
