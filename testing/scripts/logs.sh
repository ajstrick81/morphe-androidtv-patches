#!/usr/bin/env bash
# Streams filtered logcat from the Onn TV for a patched app — the fastest
# way to see whether ads are being blocked and whether the app crashes.
#
#   ./logs.sh <app-key> [extra logcat filter regex]
#
# Ctrl-C to stop. Clears the log buffer first so you only see fresh output.

source "$(dirname "$0")/lib.sh"

APP_KEY="${1:-}"
[[ -n "$APP_KEY" ]] || die "Usage: ./logs.sh <app-key> [extra grep regex]"
FILTER="${2:-}"

PKG="$(app_package "$APP_KEY")"
adb_connect

# Resolve the app's PID so we only tail its process (falls back to
# unfiltered if the app isn't running yet).
PID="$(adbx shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$PID" ]]; then
    info "Tailing logcat for $PKG (pid $PID). Ctrl-C to stop."
else
    warn "$PKG isn't running yet — showing all logs mentioning it. Launch the app on the TV."
fi

adbx logcat -c || true

if [[ -n "$PID" ]]; then
    if [[ -n "$FILTER" ]]; then
        adbx logcat --pid "$PID" | grep --line-buffered -iE "$FILTER"
    else
        adbx logcat --pid "$PID"
    fi
else
    adbx logcat | grep --line-buffered -iE "${FILTER:-$PKG}"
fi
