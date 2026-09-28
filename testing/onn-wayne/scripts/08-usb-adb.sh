#!/usr/bin/env bash
# EXPERIMENTAL — try to bring ADB up over the USB-C cable.
#
#   ./08-usb-adb.sh            # non-persistent: gone on reboot
#   ./08-usb-adb.sh --persist  # survives reboot (revertible)
#   ./08-usb-adb.sh --revert   # put the USB gadget config back
#
# The received wisdom is that this stick "has no ADB over USB". That is true of
# the shipping configuration, but it is a gadget-config choice, not a hardware
# limit: the same port speaks fastboot and fastbootd in device mode, so the
# controller is fully capable of it. With root we can ask Android to add the adb
# function to the USB gadget and see what the hardware actually does.
#
# It may simply not work. Nothing here is destructive — the worst case is a USB
# gadget that stays quiet, and a reboot (or --revert) restores the stock config.
# Wi-Fi ADB is unaffected either way, which is why the whole thing runs over it.

source "$(dirname "$0")/lib.sh"
adb_connect

MODE="${1:-}"

sur() { adbx shell "/debug_ramdisk/su -c '$*'" 2>&1 | tr -d '\r'; }

# ── Root gate ─────────────────────────────────────────────────────────
V="$(adbx shell '/debug_ramdisk/magisk -V' 2>/dev/null | tr -d '\r' || true)"
[[ -n "$V" ]] || die "This needs root (Magisk not found at /debug_ramdisk). Finish 05/06 first."
ok "Magisk $V"
manual "A GRANT prompt will appear on the TV. Accept it before the countdown ends."
pause_for_human "Remote in hand?"
[[ "$(sur id)" == *uid=0* ]] || die "Did not get root — the grant prompt probably timed out."
ok "Root shell confirmed"

STASH="$WORK_DIR/usb-gadget-original.txt"

if [[ "$MODE" == "--revert" ]]; then
    [[ -f "$STASH" ]] || die "No saved original config at $STASH — nothing to revert to."
    ORIG_PERSIST="$(grep '^persist=' "$STASH" | cut -d= -f2-)"
    info "Restoring persist.sys.usb.config='$ORIG_PERSIST' ..."
    sur "setprop persist.sys.usb.config '$ORIG_PERSIST'" >/dev/null
    sur "setprop sys.usb.config '$ORIG_PERSIST'" >/dev/null
    ok "Reverted. Reboot the stick to be sure it settles."
    exit 0
fi

# ── Record the stock state before touching anything ───────────────────
CUR="$(sur 'getprop sys.usb.config')"
PERSIST="$(sur 'getprop persist.sys.usb.config')"
{ echo "current=$CUR"; echo "persist=$PERSIST"; } > "$STASH"
info "Stock USB gadget config: sys=$CUR persist=$PERSIST  (saved to $STASH)"

echo
info "USB gadget functions the kernel exposes:"
sur 'ls /config/usb_gadget/ 2>/dev/null' | sed 's/^/  /'
sur 'cat /sys/class/android_usb/android0/state 2>/dev/null' | sed 's/^/  state: /'

pause_for_human "Plug the stick into this PC with the USB-C to USB-C cable, if it is not already."

# ── Try, least invasive first ─────────────────────────────────────────
try_and_check() {
    local label="$1"; shift
    info "Trying: $label"
    sur "$*" >/dev/null 2>&1 || true
    sleep 3
    adb devices | awk '$2=="device" && $1 !~ /:/ {print $1}' | head -n1
}

FOUND=""
FOUND="$(try_and_check "svc usb setFunctions adb" "svc usb setFunctions adb true")"
if [[ -z "$FOUND" ]]; then
    FOUND="$(try_and_check "sys.usb.config=adb" "setprop sys.usb.config adb")"
fi
if [[ -z "$FOUND" ]]; then
    FOUND="$(try_and_check "sys.usb.config=mtp,adb" "setprop sys.usb.config mtp,adb")"
fi

echo
if [[ -n "$FOUND" ]]; then
    ok "USB ADB is up. Serial: $FOUND"
    info "07-install-apk.sh picks this transport automatically when it is present."
    if [[ "$MODE" == "--persist" ]]; then
        CFG="$(sur 'getprop sys.usb.config')"
        sur "setprop persist.sys.usb.config '$CFG'" >/dev/null
        ok "Persisted as '$CFG'. Undo with ./08-usb-adb.sh --revert"
    else
        info "Not persisted — a reboot restores the stock config. Re-run with --persist to keep it."
    fi
    echo "ADB_SERIAL=\"$FOUND\"" >> "$CONFIG_DIR/wayne.env"
    ok "ADB_SERIAL written to config/wayne.env — comment it out to go back to Wi-Fi."
else
    warn "No USB ADB device appeared."
    info "Things worth checking before calling it settled:
      • The cable is USB-C to USB-C. A-to-C does not enumerate on this stick.
      • 'USB debugging' is on in Developer options.
      • Re-run with the AGH query log and 'adb devices' both visible; some gadget
        reconfigurations take a few seconds to enumerate.
      • Read what the kernel exposed above — if there is no adb function under
        /config/usb_gadget, the gadget config in this firmware genuinely omits it,
        and the next lever is a Magisk module that rewrites init.usb.rc."
    info "Restoring the stock config."
    sur "setprop sys.usb.config '$CUR'" >/dev/null 2>&1 || true
fi
