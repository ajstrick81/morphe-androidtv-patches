#!/usr/bin/env bash
# Patches an app APK/APKM with the locally-built bundle.
#
#   ./patch.sh <app-key> <input.apkm|input.apk> [extra CLI args...]
#
# Examples:
#   ./patch.sh disneyplus apks/disneyplus-26.9.2.apkm
#   ./patch.sh primevideo apks/primevideo.apkm -e "Clone Prime Video"
#
# Split bundles (.apkm/.apks/.xapk) are merged into a single APK with
# APKEditor first, since the Morphe CLI patches a single APK.

source "$(dirname "$0")/lib.sh"

APP_KEY="${1:-}"
INPUT="${2:-}"
[[ -n "$APP_KEY" && -n "$INPUT" ]] || die "Usage: ./patch.sh <app-key> <input.apkm|apk> [extra CLI args]"
shift 2
EXTRA_ARGS=("$@")

require_java
require_cli
require_bundle
[[ -f "$INPUT" ]] || die "Input file not found: $INPUT"

PKG="$(app_package "$APP_KEY")"
NAME="$(app_name "$APP_KEY")"
info "Target: ${NAME:-$APP_KEY}  ($PKG)"

# ── Merge split bundle to a single APK if needed ──────────────────────
MERGED=""
case "$INPUT" in
    *.apkm|*.apks|*.xapk|*.zip)
        [[ -f "$APKEDITOR_JAR" ]] || die "APKEditor needed to merge split bundles but not found — run scripts/setup.sh."
        MERGED="$OUT_DIR/${APP_KEY}-merged.apk"
        info "Merging split bundle → $MERGED"
        java -jar "$APKEDITOR_JAR" m -i "$INPUT" -o "$MERGED" -f >/dev/null
        SINGLE_APK="$MERGED"
        ;;
    *.apk)
        SINGLE_APK="$INPUT"
        ;;
    *)
        die "Unsupported input type: $INPUT (expected .apk / .apkm / .apks / .xapk)"
        ;;
esac

# ── Ensure a signing keystore exists (CLI reuses it if present) ───────
mkdir -p "$KEYSTORE_DIR"

OUT_APK="$OUT_DIR/${APP_KEY}-patched.apk"
info "Patching → $OUT_APK"
java -jar "$CLI_JAR" patch \
    --patches "$LOCAL_BUNDLE" \
    --out "$OUT_APK" \
    --keystore "$KEYSTORE" \
    --purge \
    "${EXTRA_ARGS[@]}" \
    "$SINGLE_APK"

ok "Patched APK ready: $OUT_APK"
info "Deploy it to the Onn TV with:  scripts/deploy.sh $APP_KEY"
