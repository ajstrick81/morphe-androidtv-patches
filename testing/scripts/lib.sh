#!/usr/bin/env bash
# Shared helpers for the Morphe patch testing scripts.
# Source this from every script:  source "$(dirname "$0")/lib.sh"

set -euo pipefail

# ── Paths ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTING_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$TESTING_DIR")"

APKS_DIR="$TESTING_DIR/apks"
OUT_DIR="$TESTING_DIR/out"
TOOLS_DIR="$TESTING_DIR/tools"
KEYSTORE_DIR="$TESTING_DIR/keystore"
CONFIG_DIR="$TESTING_DIR/config"

CLI_JAR="$TOOLS_DIR/morphe-cli.jar"
APKEDITOR_JAR="$TOOLS_DIR/APKEditor.jar"
LOCAL_BUNDLE="$OUT_DIR/patches-local.mpp"
KEYSTORE="$KEYSTORE_DIR/morphe-test.keystore"

mkdir -p "$APKS_DIR" "$OUT_DIR" "$TOOLS_DIR" "$KEYSTORE_DIR"

# ── Logging ───────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    C_INFO='\033[1;34m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OFF='\033[0m'
else
    C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_OFF=''
fi
info() { echo -e "${C_INFO}[*]${C_OFF} $*"; }
ok()   { echo -e "${C_OK}[✓]${C_OFF} $*"; }
warn() { echo -e "${C_WARN}[!]${C_OFF} $*"; }
die()  { echo -e "${C_ERR}[✗]${C_OFF} $*" >&2; exit 1; }

# ── Config ────────────────────────────────────────────────────────────
# Loads DEVICE_IP / DEVICE_PORT / DEVICE_SERIAL from config/device.env.
load_device_config() {
    local env_file="$CONFIG_DIR/device.env"
    [[ -f "$env_file" ]] || die "Missing $env_file — copy device.env.example to device.env and edit it."
    # shellcheck source=/dev/null
    source "$env_file"
    if [[ -n "${DEVICE_SERIAL:-}" ]]; then
        ADB_TARGET="$DEVICE_SERIAL"
    else
        [[ -n "${DEVICE_IP:-}" ]] || die "DEVICE_IP is not set in device.env"
        ADB_TARGET="$DEVICE_IP:${DEVICE_PORT:-5555}"
    fi
}

# app_package <app-key>  → prints the android package name
app_package() {
    local key="$1"
    local line
    line="$(grep -v '^#' "$CONFIG_DIR/apps.conf" | grep "^${key}|" | head -n1)" \
        || die "Unknown app key '$key'. Known keys: $(grep -v '^#' "$CONFIG_DIR/apps.conf" | cut -d'|' -f1 | tr '\n' ' ')"
    echo "$line" | cut -d'|' -f2
}

# app_name <app-key>  → prints the friendly display name
app_name() {
    grep -v '^#' "$CONFIG_DIR/apps.conf" | grep "^${1}|" | head -n1 | cut -d'|' -f3
}

# ── Tool checks ───────────────────────────────────────────────────────
require_java() {
    command -v java >/dev/null 2>&1 || die "java not found. Install a JDK 17+ (e.g. Temurin)."
    local ver
    ver="$(java -version 2>&1 | head -n1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    [[ "$ver" -ge 17 ]] 2>/dev/null || warn "Java $ver detected — the build and CLI expect Java 17+."
}

require_adb() {
    command -v adb >/dev/null 2>&1 || die "adb not found. Install Android platform-tools and add them to PATH."
}

require_cli() {
    [[ -f "$CLI_JAR" ]] || die "Morphe CLI not found at $CLI_JAR — run scripts/setup.sh first."
}

require_bundle() {
    [[ -f "$LOCAL_BUNDLE" ]] || die "No local patch bundle at $LOCAL_BUNDLE — run scripts/build.sh first."
}

# ── ADB helpers ───────────────────────────────────────────────────────
# Connects to the Onn TV (network target) unless a USB serial is set.
adb_connect() {
    require_adb
    load_device_config
    if [[ -n "${DEVICE_SERIAL:-}" ]]; then
        info "Using ADB serial $ADB_TARGET"
    else
        info "Connecting to $ADB_TARGET ..."
        adb connect "$ADB_TARGET" >/dev/null
    fi
    if ! adb -s "$ADB_TARGET" get-state >/dev/null 2>&1; then
        die "Device $ADB_TARGET is not reachable. Check that Developer options → USB/Network debugging is on, and accept the RSA prompt on the TV."
    fi
    ok "Connected to $ADB_TARGET"
}

adbx() { adb -s "$ADB_TARGET" "$@"; }
