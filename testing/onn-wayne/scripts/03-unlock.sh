#!/usr/bin/env bash
# Phase 2 — unlock the bootloader over USB-C.
#
#   ./03-unlock.sh
#
# THIS WIPES THE DEVICE. fastboot flashing unlock does a factory data reset,
# and the stick comes back up in the setup wizard — meaning you run the whole
# OTA-blocked wizard again before anything else. Do it before you install or
# configure a single thing.

source "$(dirname "$0")/lib.sh"
require_platform_tools

echo
warn "Unlocking wipes the stick and drops it back into the setup wizard."
warn "You will have to redo the six-domain block + two-domain window pass."

# If it is still booted to Android, do the prerequisites and reboot for them.
if [[ -f "$CONFIG_DIR/wayne.env" ]] && [[ -z "$(fastboot_serial || true)" ]]; then
    load_env
    if adb_try; then
        FP="$(getprop ro.build.fingerprint)"
        info "Running build: $FP"
        case "$FP" in
            *UKRB.260113.027.A1*)
                warn "This stick is on the first OTA build. Its bootloader fastboot is stripped;
    'flashing unlock' will fail with 'unknown reason' and there is no way back.
    Running anyway only tells you what you already know." ;;
        esac
        STATE="$(getprop ro.boot.verifiedbootstate)"
        if [[ "$STATE" == "orange" ]]; then
            ok "Already unlocked (verifiedbootstate=orange). Nothing to do — go to 04-patch-init-boot.sh."
            exit 0
        fi
        pause_for_human "Settings → System → Developer options — confirm all three are ON:
      • OEM unlocking   (without this, 'flashing unlock' is refused)
      • USB debugging   (needed even though ADB does not ride the cable)
      • Wireless debugging"
        pause_for_human "Plug the stick into this PC with the USB-C to USB-C cable NOW.
    A-to-C will not initiate the boot logo on this stick."
        info "Rebooting to the bootloader ..."
        adbx reboot bootloader || true
    fi
else
    pause_for_human "Have the stick in bootloader fastboot, plugged in over USB-C to USB-C."
fi

wait_for_fastboot 90
require_fastboot_mode bootloader

echo
info "Pre-unlock state:"
fb getvar unlocked 2>&1 | head -n2 || true

confirm_phrase "WIPE AND UNLOCK" "About to run 'fastboot flashing unlock' — this factory-resets the stick."

set +e
OUT="$(fb flashing unlock 2>&1)"
RC=$?
set -e
echo "$OUT"

if [[ $RC -ne 0 || "$OUT" == *"unknown reason"* ]]; then
    die "Unlock failed.
    If every fastboot command returns 'unknown reason', the OTA already stripped this
    bootloader's fastboot and unlock is permanently gone (see Appendix C in the guide).
    Note that even 'fastboot reboot' fails in that state — pull the power to get out."
fi

ok "Bootloader unlocked."
info "Rebooting ..."
fb reboot || warn "Reboot command failed — pull power and plug back in."

echo
manual "The stick is wiped and will land in the setup wizard."
manual "Run the OTA-blocked wizard again (all six domains blocked; unblock only
    android.googleapis.com + play.googleapis.com in the sign-in window; re-block at
    the launcher), then re-enable Developer options + Wireless debugging — the wipe
    turned them off — and run:  ./02-adb-pair.sh"
info "Verify afterwards with ./01-status.sh — verifiedbootstate must read 'orange'."
