#!/usr/bin/env bash
# Roll back to the stock init_boot — i.e. remove root, keep everything else.
#
#   ./99-rollback-init-boot.sh [stock-init_boot.img]
#
# This is why the guide tells you to keep the stock image: it is the undo button
# for phase 5. Same fastbootd + active-slot rules as flashing the patched one.

source "$(dirname "$0")/lib.sh"
require_platform_tools

IMG="${1:-}"
if [[ -z "$IMG" ]]; then
    if adb_try; then
        WANT="$(init_boot_hash_for_build "$(getprop ro.build.fingerprint)")"
        shopt -s nullglob
        for f in "$IMAGES_DIR"/*.img; do
            if [[ "$(sha256_of "$f")" == "$WANT" ]]; then IMG="$f"; break; fi
        done
        shopt -u nullglob
    fi
fi
[[ -n "$IMG" ]] || die "Usage: ./99-rollback-init-boot.sh <stock-init_boot.img>"

BUILD="$(verify_image init_boot "$IMG")"
ok "Stock init_boot verified: $(basename "$IMG")  [$BUILD]"

if [[ -z "$(fastboot_serial || true)" ]]; then
    if adb_try; then
        pause_for_human "Plug in the USB-C to USB-C cable NOW."
        info "Rebooting to fastbootd ..."
        adbx reboot fastboot || true
    else
        pause_for_human "Put the stick in fastbootd and plug in the C-to-C cable."
    fi
fi

wait_for_fastboot 90
require_fastboot_mode fastbootd

SLOT="$(fb getvar current-slot 2>&1 | awk -F': ' '/^current-slot/{print $2; exit}' | tr -d '\r')"
case "$SLOT" in a|b) ok "Active slot: $SLOT" ;; *) die "Could not read current-slot. Refusing to guess." ;; esac

confirm_phrase "ROLLBACK init_boot_$SLOT" "About to restore the stock init_boot to init_boot_$SLOT — this removes root."
fb flash "init_boot_$SLOT" "$IMG" || die "Flash failed."
ok "Stock init_boot restored"
fb reboot || warn "Reboot failed — pull power."
