#!/usr/bin/env bash
set -Eeuo pipefail
SOURCE="$0"
while [[ -L "$SOURCE" ]]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
  LINK="$(readlink "$SOURCE")"
  if [[ "$LINK" = /* ]]; then SOURCE="$LINK"; else SOURCE="$DIR/$LINK"; fi
done
REPO="$(cd -P "$(dirname "$SOURCE")" && pwd)"
PYTHON="$REPO/.venv/bin/python3"
if [[ ! -x "$PYTHON" ]] || ! "$PYTHON" -c 'import PySide6, psutil' >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
fi
if ! "$PYTHON" -c 'import PySide6, psutil' >/dev/null 2>&1; then
  echo "PySide6 GUI dependencies missing. The headless sender is unaffected." >&2
  echo "Install: $REPO/.venv/bin/python3 -m pip install -r $REPO/requirements-qt.txt" >&2
  exit 2
fi
cd "$REPO"
exec "$PYTHON" -m uasm_qt "$@"
