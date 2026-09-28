#!/usr/bin/env bash
# Shared helpers for the onn wayne / RTD1325 USB toolkit.
# Source from every script:  source "$(dirname "$0")/lib.sh"
#
# Transport model for this stick — this is the whole reason the toolkit exists:
#   • fastboot (bootloader) : USB-C only.  Used for `flashing unlock`.
#   • fastbootd (userspace) : USB-C only.  Used for flashing init_boot.
#   • adb                   : Wi-Fi only when booted to Android. The stick does
#                             not present ADB over USB in normal boot.
# So a "USB install" here means: USB carries every irreversible flash, Wi-Fi ADB
# carries the read-only checks and the on-device Magisk patch step.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WAYNE_DIR="$(dirname "$SCRIPT_DIR")"
IMAGES_DIR="$WAYNE_DIR/images"
CONFIG_DIR="$WAYNE_DIR/config"
WORK_DIR="$WAYNE_DIR/work"
MANIFEST="$IMAGES_DIR/manifest.tsv"
FINGERPRINTS="$IMAGES_DIR/fingerprints.tsv"

mkdir -p "$IMAGES_DIR" "$WORK_DIR"

# ── Logging ───────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    C_INFO='\033[1;34m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_STOP='\033[1;35m'; C_OFF='\033[0m'
else
    C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_STOP=''; C_OFF=''
fi
info()   { echo -e "${C_INFO}[*]${C_OFF} $*"; }
ok()     { echo -e "${C_OK}[✓]${C_OFF} $*"; }
warn()   { echo -e "${C_WARN}[!]${C_OFF} $*"; }
die()    { echo -e "${C_ERR}[✗]${C_OFF} $*" >&2; exit 1; }
manual() { echo -e "${C_STOP}[HANDS]${C_OFF} $*"; }

# Steps that need a human at the TV / router. Scripts never fake these.
# Set WAYNE_ASSUME_YES=1 to skip the pause (only for steps you have already done).
pause_for_human() {
    manual "$*"
    if [[ "${WAYNE_ASSUME_YES:-0}" == "1" ]]; then
        warn "WAYNE_ASSUME_YES=1 — continuing without confirmation."
        return 0
    fi
    read -r -p "    Type 'done' when this is true (anything else aborts): " reply
    [[ "$reply" == "done" ]] || die "Aborted at manual step."
}

# Destructive steps require the exact confirmation phrase, typed out.
confirm_phrase() {
    local phrase="$1"; shift
    warn "$*"
    if [[ "${WAYNE_CONFIRM:-}" == "$phrase" ]]; then
        warn "WAYNE_CONFIRM matched — proceeding."
        return 0
    fi
    read -r -p "    Type exactly '$phrase' to proceed: " reply
    [[ "$reply" == "$phrase" ]] || die "Confirmation phrase not given. Nothing was written."
}

# ── Config ────────────────────────────────────────────────────────────
load_env() {
    local f="$CONFIG_DIR/wayne.env"
    [[ -f "$f" ]] || die "Missing $f — copy config/wayne.env.example to config/wayne.env and edit it."
    # shellcheck source=/dev/null
    source "$f"
}

# ── Host tools ────────────────────────────────────────────────────────
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 not found on PATH. ${2:-}"; }

require_platform_tools() {
    require_cmd adb "Install Android platform-tools 34+ (adb pair needs 33+)."
    require_cmd fastboot "Install Android platform-tools 34+."
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# verify_image <role> <file>  → prints the build id the image belongs to.
# Hard-fails on any hash not in manifest.tsv. There is no override flag: an
# unrecognised bootcode or init_boot is exactly the thing that bricks a stick.
verify_image() {
    local role="$1" file="$2" hash line
    [[ -f "$file" ]] || die "Image not found: $file"
    hash="$(sha256_of "$file")"
    line="$(grep -v '^#' "$MANIFEST" | awk -F'\t' -v r="$role" -v h="$hash" '$1==r && $2==h' | head -n1)"
    if [[ -z "$line" ]]; then
        echo "" >&2
        die "SHA256 mismatch for $role image:
      file : $file
      sha256: $hash
    This hash is not in $MANIFEST. Re-download from the XDA thread and check
    the hash in the filename. Never flash an unverified image."
    fi
    echo "$line" | cut -f3
}

# ── USB / fastboot ────────────────────────────────────────────────────
# Resolves the USB fastboot serial (WUSAxxxxxxxxxx). Empty if nothing attached.
fastboot_serial() {
    fastboot devices 2>/dev/null | awk '$2=="fastboot"{print $1; exit}'
}

fb() {
    local s; s="$(fastboot_serial)"
    [[ -n "$s" ]] || die "No device in fastboot over USB. Check: USB-C to USB-C cable (A-to-C will not work), and that the stick is in fastboot (the screen is BLACK — that is normal)."
    fastboot -s "$s" "$@"
}

# wait_for_fastboot [timeout_seconds]
wait_for_fastboot() {
    local timeout="${1:-60}" waited=0
    info "Waiting up to ${timeout}s for the stick to appear over USB fastboot ..."
    while [[ $waited -lt $timeout ]]; do
        if [[ -n "$(fastboot_serial)" ]]; then ok "Fastboot device: $(fastboot_serial)"; return 0; fi
        sleep 2; waited=$((waited + 2))
    done
    die "No fastboot device after ${timeout}s.
    • Is the C-to-C cable in the PC and the stick?
    • On Linux, does 'fastboot devices' need udev rules? Run scripts/00-preflight.sh --udev
    • The fastboot screen is black; a blank TV does not mean it failed to boot."
}

# Which fastboot are we in? 'bootloader' (unlock only) or 'fastbootd' (flashing only).
fastboot_mode() {
    local v
    v="$(fb getvar is-userspace 2>&1 | awk -F': ' '/^is-userspace/{print $2; exit}' | tr -d '\r')"
    case "$v" in
        yes) echo "fastbootd" ;;
        no)  echo "bootloader" ;;
        *)   echo "unknown" ;;
    esac
}

require_fastboot_mode() {
    local want="$1" have
    have="$(fastboot_mode)"
    if [[ "$have" == "unknown" ]]; then
        warn "Could not read is-userspace (older bootloader). Continuing on your say-so."
        return 0
    fi
    [[ "$have" == "$want" ]] || die "Wrong fastboot mode: in '$have', need '$want'.
    bootloader = 'adb reboot bootloader'  → unlock only
    fastbootd  = 'adb reboot fastboot'    → flashing only
    Flashing init_boot from the bootloader is one of the two mistakes that look like success."
}

# ── ADB (Wi-Fi) ───────────────────────────────────────────────────────
# The connect port changes on every reboot, so ADB_PORT is re-read each time.
adb_target() {
    if [[ -n "${ADB_SERIAL:-}" ]]; then echo "$ADB_SERIAL"; return; fi
    [[ -n "${DEVICE_IP:-}" ]] || die "DEVICE_IP not set in config/wayne.env"
    [[ -n "${ADB_PORT:-}" ]] || die "ADB_PORT not set in config/wayne.env (it changes on every reboot — re-read it from Wireless debugging)."
    echo "${DEVICE_IP}:${ADB_PORT}"
}

# adb_try — best-effort connect. Sets ADB_TARGET and returns 0, or returns 1.
# Never exits, so callers can branch on "is it booted to Android right now?".
adb_try() {
    command -v adb >/dev/null 2>&1 || return 1
    [[ -f "$CONFIG_DIR/wayne.env" ]] || return 1
    load_env
    local t
    t="$(adb_target 2>/dev/null)" || return 1
    [[ -n "$t" ]] || return 1
    if [[ -z "${ADB_SERIAL:-}" ]]; then
        adb connect "$t" >/dev/null 2>&1 || true
    fi
    if ! adb -s "$t" get-state >/dev/null 2>&1; then
        adb kill-server >/dev/null 2>&1 || true
        adb start-server >/dev/null 2>&1 || true
        if [[ -z "${ADB_SERIAL:-}" ]]; then adb connect "$t" >/dev/null 2>&1 || true; fi
    fi
    adb -s "$t" get-state >/dev/null 2>&1 || return 1
    ADB_TARGET="$t"
    return 0
}

# adb_connect — same thing, but a failure is fatal with the advice that matters.
adb_connect() {
    require_cmd adb
    load_env
    if adb_try; then
        ok "ADB connected to $ADB_TARGET"
        return 0
    fi
    die "ADB target $(adb_target 2>/dev/null || echo '<unset>') is not reachable.
    • ADB on this stick is Wi-Fi only when booted to Android — a USB cable will not help here.
    • The CONNECT port differs from the PAIRING port, and changes on every reboot.
      Re-read it from Settings → System → Developer options → Wireless debugging,
      then run ./02-adb-pair.sh --reconnect
    • PC and stick must be on the same subnet."
}

adbx() { adb -s "$ADB_TARGET" "$@"; }

getprop() { adbx shell getprop "$1" 2>/dev/null | tr -d '\r'; }

# ── Gates ─────────────────────────────────────────────────────────────
require_unlocked() {
    local state; state="$(getprop ro.boot.verifiedbootstate)"
    case "$state" in
        orange) ok "Bootloader unlocked (verifiedbootstate=orange)" ;;
        green)  die "Bootloader is still LOCKED (verifiedbootstate=green). Run 03-unlock.sh first — flashing now will not stick." ;;
        *)      die "Unexpected verifiedbootstate '$state'. Refusing to flash." ;;
    esac
}

# Prints the init_boot hash that matches the running build, or dies.
init_boot_hash_for_build() {
    local fp="$1" id hash state found=""
    while IFS=$'\t' read -r id hash state; do
        case "$id" in ''|\#*) continue ;; esac
        if [[ "$fp" == *"$id"* ]]; then found="$hash"; break; fi
    done < "$FINGERPRINTS"
    [[ -n "$found" ]] || die "Build '$fp' is not in $FINGERPRINTS. This toolkit was written against
    UKRB.251217.001.A1 and UKRB.260113.027.A1 only. Anything newer is uncharted —
    a newer OTA ships its own bootcode and init_boot, and nothing here is tested on it."
    echo "$found"
}
