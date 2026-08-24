#!/usr/bin/env bash
# Install a Morphe-patched APK onto the rooted stick.
#
#   ./07-install-apk.sh <path-to-patched.apk> [package.name]
#
# This is the bridge back to what the rest of this repo does. It prefers a USB
# ADB transport if one exists (see 08-usb-adb.sh) and falls back to Wi-Fi ADB,
# which is the normal case on this stick.
#
# The stock app is uninstalled first because the signatures differ — that wipes
# that app's data on the stick.

source "$(dirname "$0")/lib.sh"
require_cmd adb

APK="${1:-}"
[[ -n "$APK" && -f "$APK" ]] || die "Usage: ./07-install-apk.sh <path-to-patched.apk> [package.name]"
PKG="${2:-}"

# Prefer a USB serial if the stick is presenting one; otherwise Wi-Fi.
USB_SERIAL="$(adb devices | awk '$2=="device" && $1 !~ /:/ {print $1; exit}')"
if [[ -n "$USB_SERIAL" ]]; then
    ADB_TARGET="$USB_SERIAL"
    ok "Using USB ADB transport: $ADB_TARGET"
else
    adb_connect
    info "USB ADB is not present — using Wi-Fi. That is expected on a stock wayne."
fi

# Read the package name out of the APK when it was not given.
if [[ -z "$PKG" ]]; then
    if command -v aapt2 >/dev/null 2>&1; then
        PKG="$(aapt2 dump packagename "$APK" 2>/dev/null | tr -d '\r')"
    elif command -v aapt >/dev/null 2>&1; then
        PKG="$(aapt dump badging "$APK" 2>/dev/null | awk -F"'" '/^package: name=/{print $2; exit}')"
    fi
fi

if [[ -n "$PKG" ]]; then
    info "Package: $PKG"
    if adbx shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "^package:${PKG}$"; then
        warn "Uninstalling the existing $PKG — its local data on the stick goes with it."
        adbx uninstall "$PKG" >/dev/null 2>&1 && ok "Removed" || warn "Uninstall failed; install may still work."
    fi
else
    warn "Package name unknown (no aapt/aapt2 on PATH). Skipping the uninstall step —
    if the install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, pass the package name."
fi

info "Installing $(basename "$APK") ..."
adbx install -r -g "$APK" || die "Install failed — see the ADB output above."
ok "Installed"

if [[ -n "$PKG" ]]; then
    adbx shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
        && ok "Launched — check the TV." \
        || info "Could not auto-launch; open it from the TV home screen."
fi
