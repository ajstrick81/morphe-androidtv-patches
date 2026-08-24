#!/usr/bin/env bash
# Phase 3a — patch a stock init_boot with Magisk, on the device.
#
#   ./04-patch-init-boot.sh [path-to-stock-init_boot.img]
#
# With no argument it picks the init_boot that matches the running build from
# images/, verifies its SHA256, and refuses to continue on a mismatch.
#
# Runs over Wi-Fi ADB (the stick has no USB ADB when booted). Nothing is
# flashed here — the output is a patched image sitting on your PC.
#
# Why on-device rather than the Magisk app: the app's file picker is unusable
# with a TV remote, and on this stick the Files app does not even register with
# it ("You don't have an app that can do this").

source "$(dirname "$0")/lib.sh"
adb_connect
load_env

FP="$(getprop ro.build.fingerprint)"
info "Running build: $FP"
require_unlocked

WANT_HASH="$(init_boot_hash_for_build "$FP")"

# ── Resolve the stock init_boot ───────────────────────────────────────
INIT_BOOT="${1:-}"
if [[ -z "$INIT_BOOT" ]]; then
    shopt -s nullglob
    for img in "$IMAGES_DIR"/*.img; do
        if [[ "$(sha256_of "$img")" == "$WANT_HASH" ]]; then INIT_BOOT="$img"; break; fi
    done
    shopt -u nullglob
    [[ -n "$INIT_BOOT" ]] || die "No init_boot in $IMAGES_DIR matching this build.
    Need sha256 $WANT_HASH
    Download it from the XDA root thread — the hash is in the filename."
fi

BUILD="$(verify_image init_boot "$INIT_BOOT")"
[[ "$(sha256_of "$INIT_BOOT")" == "$WANT_HASH" ]] \
    || die "That init_boot is genuine but belongs to $BUILD, not the build this stick is running.
    Patching the wrong build's ramdisk is not a supported path."
ok "init_boot verified: $(basename "$INIT_BOOT")  [$BUILD]"

[[ -n "${MAGISK_APK:-}" && -f "$MAGISK_APK" ]] || die "MAGISK_APK is unset or missing in config/wayne.env"
require_cmd unzip

# ── Install the Magisk app ────────────────────────────────────────────
if adbx shell pm list packages 2>/dev/null | tr -d '\r' | grep -q '^package:com.topjohnwu.magisk$'; then
    ok "Magisk app already installed"
else
    info "Installing $(basename "$MAGISK_APK") ..."
    adbx install -r "$MAGISK_APK" || die "Magisk install failed."
    ok "Magisk app installed"
fi

# ── Extract the 32-bit patch kit ──────────────────────────────────────
# This stick is armeabi-v7a. Pulling arm64 binaries fails silently, so the
# extract is pinned to armeabi-v7a and verified before anything is pushed.
KIT="$WORK_DIR/mp"
rm -rf "$KIT"; mkdir -p "$KIT"
info "Extracting armeabi-v7a patch kit from the Magisk APK ..."
unzip -o -q "$MAGISK_APK" 'lib/armeabi-v7a/*' 'assets/*' -d "$KIT"
for pair in "libmagiskboot.so:magiskboot" "libmagiskinit.so:magiskinit" "libmagisk.so:magisk" "libinit-ld.so:init-ld"; do
    src="$KIT/lib/armeabi-v7a/${pair%%:*}"
    [[ -f "$src" ]] || die "Missing ${pair%%:*} in lib/armeabi-v7a — wrong ABI or a repacked APK. Get Magisk from the official releases page."
    cp "$src" "$KIT/${pair##*:}"
done
for a in boot_patch.sh util_functions.sh stub.apk; do
    [[ -f "$KIT/assets/$a" ]] || die "Missing assets/$a in the Magisk APK."
    cp "$KIT/assets/$a" "$KIT/$a"
done
ok "Patch kit ready (32-bit)"

# ── Push and patch on-device ──────────────────────────────────────────
info "Pushing the kit to /data/local/tmp/mp ..."
adbx shell 'rm -rf /data/local/tmp/mp; mkdir -p /data/local/tmp/mp'
adbx push "$KIT/magiskboot" "$KIT/magiskinit" "$KIT/magisk" "$KIT/init-ld" \
          "$KIT/boot_patch.sh" "$KIT/util_functions.sh" "$KIT/stub.apk" /data/local/tmp/mp/ >/dev/null
adbx push "$INIT_BOOT" /sdcard/Download/init_boot.img >/dev/null
ok "Pushed"

info "Patching on-device ..."
PATCH_LOG="$WORK_DIR/boot_patch.log"
set +e
adbx shell 'cd /data/local/tmp/mp && chmod 755 magiskboot magiskinit magisk init-ld boot_patch.sh && sh boot_patch.sh /sdcard/Download/init_boot.img' 2>&1 | tee "$PATCH_LOG"
RC=${PIPESTATUS[0]}
set -e
[[ $RC -eq 0 ]] || die "boot_patch.sh failed — see $PATCH_LOG"

# The single line that separates a good patch from a double-patched brick.
grep -q 'Stock boot image detected' "$PATCH_LOG" \
    || die "boot_patch.sh did not report 'Stock boot image detected'.
    You patched an already-patched image. Start over from a fresh stock init_boot."
grep -q 'Repack to boot image' "$PATCH_LOG" || die "No repack line in the output — the patch did not complete."
ok "Clean patch: stock image in, repacked image out"

OUT="$WORK_DIR/init_boot-magisk.img"
adbx pull /data/local/tmp/mp/new-boot.img "$OUT" >/dev/null || die "Could not pull new-boot.img"
ok "Patched image: $OUT  (sha256 $(sha256_of "$OUT"))"

echo
info "Next: ./05-flash-root.sh   — plug in the C-to-C cable first."
