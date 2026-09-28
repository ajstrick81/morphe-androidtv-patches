#!/usr/bin/env bash
# Captures the Paramount+ DAI Probe output while you play through a commercial
# break, and saves it to a timestamped file for offline analysis.
#
# Prereq: build + install the app with the diagnostic probe enabled EXCLUSIVELY:
#
#   ./scripts/build.sh
#   ./scripts/patch.sh paramount apks/paramount-16.17.0.apkm \
#       --exclusive -e "Paramount+ DAI Probe (diagnostic)"
#   ./scripts/deploy.sh paramount
#
# Then run this, scrub to a "Commercial Break in Progress" slate on a live-sports
# replay (e.g. Torino vs Milan), let the whole break play out, and press Ctrl-C.
# The capture is written to out/dai-probe-<timestamp>.log — feed it to
# ./scripts/parse-dai-probe.py.
#
#   ./capture-dai.sh [output-file]

source "$(dirname "$0")/lib.sh"

PKG="$(app_package paramount)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${1:-$OUT_DIR/dai-probe-$STAMP.log}"

adb_connect

PID="$(adbx shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
[[ -n "$PID" ]] || die "$PKG isn't running. Launch Paramount+ on the TV first, then re-run."

info "Tailing DAI Probe for $PKG (pid $PID) → $OUT_FILE"
info "Now scrub to a commercial break on the TV. Press Ctrl-C when the break has fully played."

adbx logcat -c || true
# -s narrows to our probe tag; also keep the raw timestamp so we can order events.
adbx logcat -v time --pid "$PID" -s MorpheDaiProbe:I | tee "$OUT_FILE"
