#!/usr/bin/env bash
# Phase 3c — confirm root, over Wi-Fi ADB.
#
#   ./06-verify-root.sh
#
# 'su' is not on PATH right after boot; Magisk lives under /debug_ramdisk.

source "$(dirname "$0")/lib.sh"
adb_connect

FP="$(getprop ro.build.fingerprint)"
info "Build: $FP"
case "$FP" in
    *UKRB.251217.001.A1*) ok "Still on the shipping build — the OTA block is holding." ;;
    *) warn "Build is no longer the shipping build. Check the six domains in AdGuard Home." ;;
esac

BOOTSTATE="$(getprop ro.boot.verifiedbootstate)"
if [[ "$BOOTSTATE" == "orange" ]]; then ok "verifiedbootstate=orange (unlocked)"; else warn "verifiedbootstate=$BOOTSTATE"; fi

echo
V="$(adbx shell '/debug_ramdisk/magisk -V' 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$V" ]]; then
    ok "Magisk reports version $V — that alone means you are rooted."
else
    die "/debug_ramdisk/magisk did not answer. Either the flash did not take, or the stick
    booted the other slot. Re-check ./01-status.sh and the slot you flashed."
fi

echo
manual "The next command pops a GRANT prompt on the TV with a countdown. Hit GRANT
    before it expires, or this looks like a root failure when it is not."
pause_for_human "Remote in hand?"

set +e
ID="$(adbx shell '/debug_ramdisk/su -c id' 2>&1 | tr -d '\r')"
set -e
echo "  $ID"
if [[ "$ID" == *"uid=0"* ]]; then
    ok "Root confirmed: $ID"
else
    warn "su did not return uid=0. Usually the grant prompt timed out — try again."
fi

echo
info "Finish Magisk setup on the TV — it has no launcher icon:
      Settings → Apps → See all apps → Magisk → Open → OK   (it may reboot once)"
info "Then back up $WORK_DIR/init_boot-magisk.img alongside your stock image."
