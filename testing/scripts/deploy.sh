#!/usr/bin/env bash
# Installs a patched APK onto the Onn TV over ADB and launches it.
#
#   ./deploy.sh <app-key> [path-to-apk]
#
# If no APK path is given it uses testing/out/<app-key>-patched.apk.
# The stock app is uninstalled first (signatures differ), so this wipes
# that app's local data on the TV — fine for a test box.

source "$(dirname "$0")/lib.sh"

APP_KEY="${1:-}"
[[ -n "$APP_KEY" ]] || die "Usage: ./deploy.sh <app-key> [path-to-apk]"
APK="${2:-$OUT_DIR/${APP_KEY}-patched.apk}"
[[ -f "$APK" ]] || die "APK not found: $APK — run scripts/patch.sh $APP_KEY <input> first."

PKG="$(app_package "$APP_KEY")"
NAME="$(app_name "$APP_KEY")"

adb_connect

info "Uninstalling any existing ${NAME:-$APP_KEY} ($PKG) ..."
adbx uninstall "$PKG" >/dev/null 2>&1 && ok "Removed old install" || info "Nothing to uninstall"

info "Installing $(basename "$APK") ..."
adbx install -r -g "$APK" || die "Install failed — see ADB output above."
ok "Installed $PKG"

# Launch via monkey (no need to know the exact launcher activity).
info "Launching on the TV ..."
adbx shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    && ok "Launched — check your TV screen." \
    || warn "Could not auto-launch; open ${NAME:-$APP_KEY} from the TV home screen."

info "Watch its logs with:  scripts/logs.sh $APP_KEY"
