#!/usr/bin/env bash
# capture.sh — drive PCAPdroid on the Onn TV over ADB to capture one app's
# network traffic, then pull the .pcap back for analysis. Reuses the same
# device config + ADB helpers as the rest of the testing harness (lib.sh).
#
#   ./capture.sh start <app-key> [--decrypt] [--name FILE.pcap]
#   ./capture.sh stop
#   ./capture.sh pull  [--name FILE.pcap]
#   ./capture.sh run   <app-key> <seconds> [--decrypt] [--name FILE.pcap]
#
# `run` is the one-shot: start → wait N seconds → stop → pull into out/.
# `start`/`stop`/`pull` are the manual pieces when you want to time an ad
# break by hand (recommended — see testing/capture-runbook.md).
#
# Prerequisites on the TV (one time):
#   • PCAPdroid installed  (package com.emanuelef.remote_capture)
#   • PCAPdroid → Settings → grant the "Control permission" (lets adb drive it)
#   • For --decrypt: install the PCAPdroid *mitm addon* and trust its CA.
#     NOTE: Netflix's Cronet validates/pins certs, so its core MSL / appboot /
#     manifest flows will likely REFUSE to decrypt. --decrypt still helps for
#     un-pinned side flows (some telemetry / ad beacons). Even without it, the
#     capture gives DNS + TLS SNI (hostnames are plaintext) — which is enough
#     to map the ad footprint. See the runbook.
#
# This script only *drives* the capture and retrieves the file; upload the
# resulting testing/out/<name>.pcap for analysis.

source "$(dirname "$0")/lib.sh"

PCAPDROID_PKG="com.emanuelef.remote_capture"
CAPTURE_CTRL="$PCAPDROID_PKG/.activities.CaptureCtrl"
DEFAULT_NAME="netflix-capture.pcap"

# Candidate on-device locations PCAPdroid may write the pcap_file to,
# across versions / storage configs. `pull` searches these newest-first.
DEVICE_DIRS=(
    "/sdcard/Download/PCAPdroid"
    "/sdcard/Download"
    "/sdcard/Android/data/$PCAPDROID_PKG/files"
    "/sdcard/Movies/PCAPdroid"
)

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

# ── arg parsing ───────────────────────────────────────────────────────
CMD="${1:-}"; shift || true
APP_KEY=""; WAIT_SECS=""; NAME="$DEFAULT_NAME"; DECRYPT=""

# positional args depend on the command
case "$CMD" in
    start|run) APP_KEY="${1:-}"; shift || true ;;
esac
if [[ "$CMD" == "run" ]]; then WAIT_SECS="${1:-}"; shift || true; fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --decrypt) DECRYPT=1 ;;
        --name)    NAME="${2:-}"; shift ;;
        *) warn "Ignoring unknown arg: $1" ;;
    esac
    shift
done

pcapdroid_installed() {
    adbx shell pm path "$PCAPDROID_PKG" >/dev/null 2>&1
}

start_capture() {
    [[ -n "$APP_KEY" ]] || die "Usage: ./capture.sh start <app-key> [--decrypt] [--name FILE.pcap]"
    local pkg; pkg="$(app_package "$APP_KEY")"
    pcapdroid_installed || die "PCAPdroid ($PCAPDROID_PKG) is not installed on the TV."

    info "Starting PCAPdroid capture → app_filter=$pkg  file=$NAME  decrypt=${DECRYPT:-0}"
    # PCAPdroid CaptureCtrl intent API. String extras via -e, boolean via --ez.
    # shellcheck disable=SC2086
    adbx shell am start -n "$CAPTURE_CTRL" \
        -e action start \
        -e pcap_dump_mode pcap_file \
        -e app_filter "$pkg" \
        -e pcap_name "$NAME" \
        ${DECRYPT:+--ez tls_decryption true} \
        >/dev/null
    ok "Capture requested. If PCAPdroid prompts for VPN/consent on the TV, accept it."
    info "Play the app, hit an ad break, note the wall-clock times, then: ./capture.sh stop"
}

stop_capture() {
    info "Stopping PCAPdroid capture ..."
    adbx shell am start -n "$CAPTURE_CTRL" -e action stop >/dev/null
    ok "Stop requested."
}

pull_capture() {
    mkdir -p "$OUT_DIR"
    local found=""
    for d in "${DEVICE_DIRS[@]}"; do
        # newest file matching the name (or any .pcap if exact name absent)
        local hit
        hit="$(adbx shell "ls -t $d/$NAME $d/*.pcap $d/*.pcapng 2>/dev/null | head -n1" | tr -d '\r' || true)"
        if [[ -n "$hit" ]]; then found="$hit"; break; fi
    done
    [[ -n "$found" ]] || die "Couldn't locate a pcap on the device. Open PCAPdroid on the TV — it shows the saved file's path — and pull it manually:  adbx pull '<path>' $OUT_DIR/"
    info "Pulling $found → $OUT_DIR/"
    adbx pull "$found" "$OUT_DIR/" >/dev/null
    ok "Saved to $OUT_DIR/$(basename "$found")"
    echo "$OUT_DIR/$(basename "$found")"
}

case "$CMD" in
    start) adb_connect; start_capture ;;
    stop)  adb_connect; stop_capture ;;
    pull)  adb_connect; pull_capture ;;
    run)
        [[ -n "$WAIT_SECS" ]] || die "Usage: ./capture.sh run <app-key> <seconds> [--decrypt] [--name FILE.pcap]"
        adb_connect; start_capture
        info "Capturing for ${WAIT_SECS}s ... (Ctrl-C won't stop the device capture; use ./capture.sh stop)"
        sleep "$WAIT_SECS"
        stop_capture
        sleep 2
        pull_capture
        ;;
    *) usage ;;
esac
