#!/usr/bin/env bash
# Single entrypoint for the onn wayne / RTD1325 USB toolkit.
#
#   ./onn.sh status [--json]     where the stick is right now, and what is next
#   ./onn.sh preflight [--udev]  host tools, image hashes, OTA block check
#   ./onn.sh pair [--reconnect]  Wi-Fi ADB pairing / re-read the connect port
#   ./onn.sh unlock              USB: fastboot flashing unlock  (WIPES)
#   ./onn.sh patch [img]         Magisk-patch a stock init_boot on-device
#   ./onn.sh flash [img]         USB: flash the patched init_boot via fastbootd
#   ./onn.sh verify              confirm root
#   ./onn.sh install <apk> [pkg] install a Morphe-patched APK
#   ./onn.sh usb-adb [--persist|--revert]   experimental: ADB over the cable
#   ./onn.sh bootcode <img>      Appendix B: restore bootloader fastboot (root)
#   ./onn.sh rollback [img]      restore the stock init_boot (removes root)
#
# Run './onn.sh status' between every step. It reads the device rather than
# assuming, which is the difference between this and a list of commands.

set -euo pipefail
cd "$(dirname "$0")"

CMD="${1:-status}"; shift || true

case "$CMD" in
    status)    exec scripts/01-status.sh "$@" ;;
    preflight) exec scripts/00-preflight.sh "$@" ;;
    pair)      exec scripts/02-adb-pair.sh "$@" ;;
    unlock)    exec scripts/03-unlock.sh "$@" ;;
    patch)     exec scripts/04-patch-init-boot.sh "$@" ;;
    flash)     exec scripts/05-flash-root.sh "$@" ;;
    verify)    exec scripts/06-verify-root.sh "$@" ;;
    install)   exec scripts/07-install-apk.sh "$@" ;;
    usb-adb)   exec scripts/08-usb-adb.sh "$@" ;;
    bootcode)  exec scripts/90-restore-bootcode.sh "$@" ;;
    rollback)  exec scripts/99-rollback-init-boot.sh "$@" ;;
    -h|--help|help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) echo "Unknown command '$CMD'. Try './onn.sh help'." >&2; exit 1 ;;
esac
