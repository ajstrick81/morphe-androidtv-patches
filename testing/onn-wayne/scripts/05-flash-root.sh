#!/usr/bin/env bash
# Phase 3b — flash the Magisk-patched init_boot over USB, via fastbootd.
#
#   ./05-flash-root.sh [path-to-patched-init_boot.img]
#
# Three hard rules, all enforced here rather than trusted to the operator:
#   1. fastbootd ('adb reboot fastboot'), never bootloader fastboot.
#   2. Only the ACTIVE slot, read live from 'fastboot getvar current-slot'.
#      This is a Virtual A/B device — the inactive slot is not a bootable copy
#      of your build, so a wrong-slot flash is not fixed by switching slots, and
#      that is what preceded the one permanent brick reported on the OTA build.
#   3. The image must be one this toolkit produced from a verified stock image.

source "$(dirname "$0")/lib.sh"
require_platform_tools

IMG="${1:-$WORK_DIR/init_boot-magisk.img}"
[[ -f "$IMG" ]] || die "Patched image not found: $IMG — run ./04-patch-init-boot.sh first."
info "Image: $IMG  (sha256 $(sha256_of "$IMG"))"

# A patched image must NOT hash to a known stock image — that would mean the
# patch step silently produced a copy of the input.
if grep -v '^#' "$MANIFEST" | awk -F'\t' -v h="$(sha256_of "$IMG")" '$2==h' | grep -q .; then
    die "That file is a STOCK image, not a patched one. Flashing it would not give you root.
    (To deliberately roll back to stock, use ./99-rollback-init-boot.sh.)"
fi

# ── Get into fastbootd ────────────────────────────────────────────────
if [[ -z "$(fastboot_serial || true)" ]]; then
    if adb_try; then
        require_unlocked
        pause_for_human "Plug the stick into this PC with the USB-C to USB-C cable NOW."
        info "Rebooting to fastbootd (adb reboot fastboot — NOT 'reboot bootloader') ..."
        adbx reboot fastboot || true
    else
        pause_for_human "Put the stick in fastbootd ('adb reboot fastboot') and plug in the C-to-C cable."
    fi
fi

wait_for_fastboot 90
require_fastboot_mode fastbootd

# ── Read the active slot live ─────────────────────────────────────────
SLOT="$(fb getvar current-slot 2>&1 | awk -F': ' '/^current-slot/{print $2; exit}' | tr -d '\r')"
case "$SLOT" in
    a|b) ok "Active slot: $SLOT" ;;
    *)   die "Could not read current-slot (got '${SLOT:-empty}'). Refusing to guess which slot to flash." ;;
esac
TARGET="init_boot_$SLOT"

echo
confirm_phrase "FLASH $TARGET" "About to write $(basename "$IMG") to $TARGET on $(fastboot_serial)."

set +e
OUT="$(fb flash "$TARGET" "$IMG" 2>&1)"
RC=$?
set -e
echo "$OUT"
[[ $RC -eq 0 && "$OUT" != *"unknown reason"* ]] || die "Flash failed. Nothing else has been written.
    If this was bootloader fastboot rather than fastbootd, that is the cause."
ok "Flashed $TARGET"

info "Rebooting ..."
fb reboot || warn "Reboot failed — pull power and plug back in."

echo
manual "The stick reboots into Android. Re-connect ADB (the port changed):
      ./02-adb-pair.sh --reconnect"
manual "Then ./06-verify-root.sh — and keep the TV remote in hand, the su call pops
    a Magisk grant prompt with a countdown."
