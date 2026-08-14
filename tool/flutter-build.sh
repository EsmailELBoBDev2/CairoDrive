#!/usr/bin/env bash
#
# Build CairoDrive with API keys injected as --dart-define.
#
# Key resolution order, per key:
#   1. environment variable   (CI: GitHub Actions secrets)
#   2. app/android/local.properties   (developer machine, git-ignored)
#
# Values are never echoed — only whether each key was found.
#
# Usage:
#   tool/flutter-build.sh apk --release
#   tool/flutter-build.sh appbundle --release
#   tool/flutter-build.sh apk --debug
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/app"
LOCAL_PROPERTIES="$APP_DIR/android/local.properties"

# Read a key from local.properties without printing it.
from_local_properties() {
  local name="$1"
  [ -f "$LOCAL_PROPERTIES" ] || return 0
  sed -n "s/^${name}=//p" "$LOCAL_PROPERTIES" | head -1
}

resolve_key() {
  local name="$1"
  local value="${!name:-}"
  if [ -z "$value" ]; then
    value="$(from_local_properties "$name")"
  fi
  printf '%s' "$value"
}

GOOGLE_PLACES_API_KEY="$(resolve_key GOOGLE_PLACES_API_KEY)"
MAGICLANE_API_KEY="$(resolve_key MAGICLANE_API_KEY)"

report() {
  if [ -n "$2" ]; then echo "  $1: present"; else echo "  $1: ABSENT"; fi
}
echo "CairoDrive build — key resolution:"
report GOOGLE_PLACES_API_KEY "$GOOGLE_PLACES_API_KEY"
report MAGICLANE_API_KEY "$MAGICLANE_API_KEY"

if [ -z "$MAGICLANE_API_KEY" ]; then
  echo "WARNING: MAGICLANE_API_KEY absent — the map engine will not initialise." >&2
fi
if [ -z "$GOOGLE_PLACES_API_KEY" ]; then
  echo "WARNING: GOOGLE_PLACES_API_KEY absent — search will use the offline engine only." >&2
fi

DART_DEFINES=()
[ -n "$GOOGLE_PLACES_API_KEY" ] && \
  DART_DEFINES+=("--dart-define=GOOGLE_PLACES_API_KEY=$GOOGLE_PLACES_API_KEY")
[ -n "$MAGICLANE_API_KEY" ] && \
  DART_DEFINES+=("--dart-define=MAGICLANE_API_KEY=$MAGICLANE_API_KEY")

cd "$APP_DIR"
# `set -x` is deliberately NOT used: it would echo the key values.
exec flutter build "$@" "${DART_DEFINES[@]}"
