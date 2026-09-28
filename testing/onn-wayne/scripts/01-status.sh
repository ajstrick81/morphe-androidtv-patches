#!/usr/bin/env bash
# Reads the stick's current state without changing anything, and says which
# phase comes next. Safe to run at any time, in any mode.
#
#   ./01-status.sh          # human readable
#   ./01-status.sh --json   # machine readable (this is what an agent parses)

source "$(dirname "$0")/lib.sh"

JSON=0
[[ "${1:-}" == "--json" ]] && JSON=1

TRANSPORT="none"; SERIAL=""; FB_MODE=""; SLOT=""; UNLOCKED=""
FINGERPRINT=""; BOOTSTATE=""; MAGISK=""; NEXT=""; NOTE=""

# ── USB first: fastboot is the only transport that rides the cable ────
if command -v fastboot >/dev/null 2>&1; then
    SERIAL="$(fastboot_serial || true)"
fi

if [[ -n "$SERIAL" ]]; then
    TRANSPORT="usb-fastboot"
    FB_MODE="$(fastboot_mode 2>/dev/null || echo unknown)"
    RAW_SLOT="$(fb getvar current-slot 2>&1 || true)"
    RAW_UNLOCKED="$(fb getvar unlocked 2>&1 || true)"
    SLOT="$(echo "$RAW_SLOT" | awk -F': ' '/^current-slot/{print $2; exit}' | tr -d '\r')"
    UNLOCKED="$(echo "$RAW_UNLOCKED" | awk -F': ' '/^unlocked/{print $2; exit}' | tr -d '\r')"
    # A stripped bootloader answers every getvar with "unknown reason" rather than
    # a value, so the absence of a value is the signal — not its content.
    if [[ "$RAW_UNLOCKED" == *"unknown reason"* || "$RAW_SLOT" == *"unknown reason"* ]]; then
        UNLOCKED="unknown reason (fastboot stripped)"
        NOTE="Every getvar fails with 'unknown reason' — this is a bootloader whose fastboot the OTA stripped. If the stick was unlocked before that OTA, root via fastbootd still works; if it was not, unlock is gone for good."
    fi
    case "$FB_MODE" in
        bootloader) NEXT="03-unlock.sh  (bootloader fastboot: unlock only)" ;;
        fastbootd)  NEXT="05-flash-root.sh  (fastbootd: flashing only)" ;;
        *)          NEXT="Determine which fastboot you are in before flashing anything." ;;
    esac
else
    # ── Fall back to Wi-Fi ADB ────────────────────────────────────────
    if [[ -f "$CONFIG_DIR/wayne.env" ]]; then
        load_env
        T="$(adb_target 2>/dev/null || true)"
        if [[ -n "$T" ]]; then
            if [[ -z "${ADB_SERIAL:-}" ]]; then adb connect "$T" >/dev/null 2>&1 || true; fi
            if adb -s "$T" get-state >/dev/null 2>&1; then
                TRANSPORT="wifi-adb"; SERIAL="$T"; ADB_TARGET="$T"
                FINGERPRINT="$(getprop ro.build.fingerprint)"
                BOOTSTATE="$(getprop ro.boot.verifiedbootstate)"
                SLOT="$(getprop ro.boot.slot_suffix)"
                MAGISK="$(adbx shell '/debug_ramdisk/magisk -V' 2>/dev/null | tr -d '\r' || true)"
                if [[ "$BOOTSTATE" == "green" ]]; then
                    NEXT="03-unlock.sh  (plug in USB-C, then 'adb reboot bootloader')"
                elif [[ -n "$MAGISK" ]]; then
                    NEXT="06-verify-root.sh  (Magisk $MAGISK already reports in)"
                else
                    NEXT="04-patch-init-boot.sh"
                fi
                case "$FINGERPRINT" in
                    *UKRB.251217.001.A1*) : ;;
                    *UKRB.260113.027.A1*) NOTE="Running the first OTA build. Unlock is only still available if it happened before the update." ;;
                    *) NOTE="Build is outside what this toolkit was written against. Treat every flash as uncharted." ;;
                esac
            fi
        fi
    fi
    if [[ "$TRANSPORT" == "none" ]]; then
        NEXT="Nothing reachable. Booted to Android → check Wi-Fi ADB (the connect port changes every reboot). Flashing → plug the C-to-C cable in and reboot to fastboot."
    fi
fi

if [[ $JSON -eq 1 ]]; then
    printf '{\n'
    printf '  "transport": "%s",\n'         "$TRANSPORT"
    printf '  "serial": "%s",\n'            "$SERIAL"
    printf '  "fastboot_mode": "%s",\n'     "$FB_MODE"
    printf '  "current_slot": "%s",\n'      "$SLOT"
    printf '  "unlocked": "%s",\n'          "$UNLOCKED"
    printf '  "verifiedbootstate": "%s",\n' "$BOOTSTATE"
    printf '  "fingerprint": "%s",\n'       "$FINGERPRINT"
    printf '  "magisk_version": "%s",\n'    "$MAGISK"
    printf '  "note": "%s",\n'              "$NOTE"
    printf '  "next": "%s"\n'               "$NEXT"
    printf '}\n'
    exit 0
fi

echo
info "── Device state ──────────────────────────────────────────"
echo "  transport          : $TRANSPORT"
[[ -n "$SERIAL"      ]] && echo "  serial / target    : $SERIAL" || true
[[ -n "$FB_MODE"     ]] && echo "  fastboot mode      : $FB_MODE" || true
[[ -n "$FINGERPRINT" ]] && echo "  build fingerprint  : $FINGERPRINT" || true
[[ -n "$BOOTSTATE"   ]] && echo "  verifiedbootstate  : $BOOTSTATE" || true
[[ -n "$SLOT"        ]] && echo "  active slot        : $SLOT" || true
[[ -n "$UNLOCKED"    ]] && echo "  fastboot 'unlocked': $UNLOCKED" || true
[[ -n "$MAGISK"      ]] && echo "  magisk             : $MAGISK" || true
[[ -n "$NOTE"        ]] && { echo; warn "$NOTE"; } || true
echo
info "Next: $NEXT"
exit 0
