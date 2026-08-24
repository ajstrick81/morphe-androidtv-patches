#!/usr/bin/env bash
# Phase 0 — everything that must be true before the stick is powered on.
# Nothing here touches the device.
#
#   ./00-preflight.sh          # host tools, image hashes, DNS block check
#   ./00-preflight.sh --udev   # also emit Linux udev rules for the USB device

source "$(dirname "$0")/lib.sh"

EMIT_UDEV=0
[[ "${1:-}" == "--udev" ]] && EMIT_UDEV=1

FAIL=0
note_fail() { warn "$*"; FAIL=1; }

echo
info "── Host tools ────────────────────────────────────────────"
if command -v adb >/dev/null 2>&1; then
    ADB_VER="$(adb version | head -n1)"
    ok "adb: $ADB_VER"
    # 'adb pair' landed in platform-tools 33. Older builds fail confusingly.
    PT="$(adb version | awk -F'version ' '/Version/{print $2}' | cut -d. -f1)"
    [[ -n "$PT" && "$PT" -lt 33 ]] 2>/dev/null && note_fail "platform-tools $PT is too old — 'adb pair' needs 33+."
else
    note_fail "adb not found. Install Android platform-tools 34+."
fi
if command -v fastboot >/dev/null 2>&1; then
    ok "fastboot: $(fastboot --version | head -n1)"
else
    note_fail "fastboot not found. Install Android platform-tools 34+."
fi
if command -v unzip >/dev/null 2>&1; then
    ok "unzip present"
else
    note_fail "unzip not found (needed to open the Magisk APK)."
fi
if command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1; then
    ok "sha256 tool present"
else
    note_fail "no sha256sum/shasum — cannot verify images."
fi

echo
info "── Images in $IMAGES_DIR ─────────────────────────"
FOUND=0
shopt -s nullglob
for img in "$IMAGES_DIR"/*.img; do
    FOUND=1
    h="$(sha256_of "$img")"
    row="$(grep -v '^#' "$MANIFEST" | awk -F'\t' -v h="$h" '$2==h' | head -n1)"
    if [[ -n "$row" ]]; then
        ok "$(basename "$img")
      → $(echo "$row" | cut -f1)  $(echo "$row" | cut -f3)"
    else
        note_fail "$(basename "$img") — sha256 $h is NOT in the manifest. Do not flash it."
    fi
done
shopt -u nullglob
[[ $FOUND -eq 1 ]] || warn "No .img files yet. Download from the XDA thread into $IMAGES_DIR:
      • init_boot  bbdc73fb…  (UKRB.251217.001.A1)  — patch target + rollback
      • bootcode   e9646ad2…  (UKRB.251217.001.A1)  — insurance, Appendix B only
    Magisk APK goes there too (MAGISK_APK in config/wayne.env)."

if [[ -f "$CONFIG_DIR/wayne.env" ]]; then
    load_env
    if [[ -n "${MAGISK_APK:-}" && -f "$MAGISK_APK" ]]; then
        ok "Magisk APK: $(basename "$MAGISK_APK")"
        unzip -l "$MAGISK_APK" 'lib/armeabi-v7a/libmagiskboot.so' >/dev/null 2>&1 \
            && ok "  contains armeabi-v7a binaries (this stick is 32-bit)" \
            || note_fail "  no lib/armeabi-v7a in that APK — arm64 binaries are a silent failure on this stick."
    else
        warn "MAGISK_APK not set or missing in config/wayne.env."
    fi
else
    warn "config/wayne.env not created yet (cp config/wayne.env.example config/wayne.env)."
fi

echo
info "── OTA block check (the one mistake you cannot undo) ─────"
DOMAINS=(gvt1.com gvt2.com update.googleapis.com dl.google.com android.googleapis.com play.googleapis.com)
if command -v dig >/dev/null 2>&1; then
    for d in "${DOMAINS[@]}"; do
        ans="$(dig +short +time=3 +tries=1 "$d" 2>/dev/null | tail -n1)"
        if [[ -z "$ans" || "$ans" == "0.0.0.0" || "$ans" == "::" ]]; then
            ok "$d → blocked (${ans:-NXDOMAIN})"
        else
            note_fail "$d → $ans  — REAL IP. The block is not working. Do not power on the stick."
        fi
    done
    warn "This checked THIS machine's resolver. The stick must resolve through the same
    AdGuard Home instance — same SSID, and DNS forced at the router (redirect :53,
    block outbound TCP/UDP 853 so it cannot tunnel to DoT)."
else
    warn "dig not found — cannot verify the block from here. Check manually before powering on."
fi

if [[ $EMIT_UDEV -eq 1 ]]; then
    echo
    info "── Linux udev rules ──────────────────────────────────────"
    if command -v lsusb >/dev/null 2>&1; then
        info "Plug the stick in over USB-C and put it in fastboot, then pick its VID below:"
        lsusb
        read -r -p "    Vendor ID (4 hex digits, e.g. 18d1): " vid
        [[ "$vid" =~ ^[0-9a-fA-F]{4}$ ]] || die "That is not a 4-digit hex vendor ID."
        rule="/etc/udev/rules.d/99-onn-wayne.rules"
        echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"$(echo "$vid" | tr "A-Z" "a-z")\", MODE=\"0660\", GROUP=\"plugdev\"" > "$WORK_DIR/99-onn-wayne.rules"
        ok "Wrote $WORK_DIR/99-onn-wayne.rules"
        info "Install it with:
      sudo cp $WORK_DIR/99-onn-wayne.rules $rule
      sudo udevadm control --reload-rules && sudo udevadm trigger
      sudo usermod -aG plugdev \$USER   # then log out and back in"
    else
        warn "lsusb not found — not a Linux host, or usbutils is not installed. macOS needs no rules."
    fi
fi

echo
if [[ $FAIL -eq 0 ]]; then
    ok "Preflight clean."
    info "Next: Phase 1 is manual (setup wizard + the two-domain window) — see README.
      Then: ./01-status.sh"
else
    die "Preflight has failures above. Fix them before the stick is powered on."
fi
