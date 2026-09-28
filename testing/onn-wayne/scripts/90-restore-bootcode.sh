#!/usr/bin/env bash
# Appendix B — restore the bootloader's own fastboot by writing the factory
# bootcode back with dd. Requires root.
#
#   ./90-restore-bootcode.sh <factory-bootcode.img>
#
# ONLY for a stick that was unlocked and then let an OTA install. You do not
# need this to be rooted — everything in phases 3–6 goes through fastbootd.
#
# 🛑 Writing bootcode overwrites the bootloader. The wrong file, or the right
# file on the wrong device, bricks it permanently and there is no recovery.
# Tested only on UKRB.251217.001.A1 and UKRB.260113.027.A1. Anything newer ships
# its own bootcode and is uncharted.

source "$(dirname "$0")/lib.sh"
adb_connect

IMG="${1:-}"
[[ -n "$IMG" ]] || die "Usage: ./90-restore-bootcode.sh <factory-bootcode.img>"

FACTORY="e9646ad2e8dd6e7126e159648d654c8dec5e674268a8e0208427f89c7458b3d1"
BUILD="$(verify_image bootcode "$IMG")"
HASH="$(sha256_of "$IMG")"
info "bootcode image: $(basename "$IMG")  [$BUILD]"

if [[ "$HASH" != "$FACTORY" ]]; then
    warn "This is NOT the factory bootcode — it is the OTA one, whose fastboot is dead.
    Writing it puts the stick back to the as-updated state. That is a legitimate thing
    to want and a terrible thing to do by accident."
    confirm_phrase "WRITE OTA BOOTCODE" "You are about to disable this stick's bootloader fastboot."
fi

# ── Gates ─────────────────────────────────────────────────────────────
FP="$(getprop ro.build.fingerprint)"
info "Running build: $FP"
case "$FP" in
    *UKRB.251217.001.A1*|*UKRB.260113.027.A1*) ok "Build is one of the two this is tested on." ;;
    *) die "Build '$FP' is outside what anyone has tested. A newer OTA ships its own bootcode;
    writing the 251217 one underneath it has never been tried and might not boot." ;;
esac

require_unlocked

manual "A GRANT prompt will appear on the TV. Accept it before the countdown ends."
pause_for_human "Remote in hand?"
ID="$(adbx shell '/debug_ramdisk/su -c id' 2>&1 | tr -d '\r')"
[[ "$ID" == *uid=0* ]] || die "No root. This writes through dd and needs it. ($ID)"
ok "Root confirmed"

# Sanity-check the partition geometry before writing to a raw block device.
SZ2="$(adbx shell '/debug_ramdisk/su -c "blockdev --getsize64 /dev/block/mmcblk0p2"' 2>/dev/null | tr -d '\r')"
info "mmcblk0p2 (bootcode_a) size: ${SZ2:-unknown} bytes; image is $(wc -c < "$IMG") bytes"
[[ -n "$SZ2" && "$SZ2" =~ ^[0-9]+$ && "$(wc -c < "$IMG")" -le "$SZ2" ]] \
    || die "Image does not fit mmcblk0p2, or the size could not be read. Refusing to dd."

echo
confirm_phrase "OVERWRITE BOOTLOADER" "About to dd this image over BOTH bootcode slots (mmcblk0p2 and mmcblk0p3). The OTA overwrote both, so both get restored."

info "Pushing image ..."
adbx push "$IMG" /data/local/tmp/bootcode.img >/dev/null

PUSHED="$(adbx shell "/debug_ramdisk/su -c 'sha256sum /data/local/tmp/bootcode.img'" 2>/dev/null | awk '{print $1}' | tr -d '\r')"
[[ "$PUSHED" == "$HASH" ]] || die "The pushed copy hashes differently ($PUSHED). Aborting before any write."
ok "On-device copy verified byte-for-byte"

info "Writing ..."
adbx shell "/debug_ramdisk/su -c 'dd if=/data/local/tmp/bootcode.img of=/dev/block/mmcblk0p2 && dd if=/data/local/tmp/bootcode.img of=/dev/block/mmcblk0p3 && sync'" \
    || die "dd failed. Do NOT reboot — re-run and get a clean write first."
ok "Both bootcode slots written"

info "Rebooting ..."
adbx reboot || true

echo
manual "When it comes back: plug in the C-to-C cable, then
      adb reboot bootloader
      fastboot getvar unlocked      # 'unlocked: yes' instead of 'unknown reason'"
info "Or just run ./01-status.sh with the stick in the bootloader."
