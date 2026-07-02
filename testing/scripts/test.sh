#!/usr/bin/env bash
# End-to-end: build the bundle, patch the app, deploy to the TV, and
# start tailing logs — the full loop in one command.
#
#   ./test.sh <app-key> <input.apkm|apk> [extra CLI args...]
#
# Example:
#   ./test.sh disneyplus apks/disneyplus-26.9.2.apkm

source "$(dirname "$0")/lib.sh"

APP_KEY="${1:-}"
INPUT="${2:-}"
[[ -n "$APP_KEY" && -n "$INPUT" ]] || die "Usage: ./test.sh <app-key> <input.apkm|apk> [extra CLI args]"
shift 2

info "=== 1/4  Build patch bundle ==="
"$SCRIPT_DIR/build.sh"

info "=== 2/4  Patch $APP_KEY ==="
"$SCRIPT_DIR/patch.sh" "$APP_KEY" "$INPUT" "$@"

info "=== 3/4  Deploy to Onn TV ==="
"$SCRIPT_DIR/deploy.sh" "$APP_KEY"

info "=== 4/4  Stream logs (Ctrl-C to stop) ==="
"$SCRIPT_DIR/logs.sh" "$APP_KEY"
