#!/usr/bin/env bash
# One-time setup: verifies host tools and downloads the jars the other
# scripts need into testing/tools/ (gitignored).
#
#   ./setup.sh          download latest Morphe CLI + APKEditor
#   ./setup.sh --check  only verify java/adb/jars, download nothing

source "$(dirname "$0")/lib.sh"

CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

# github_latest_jar <owner/repo> <dest-file>
# Downloads the first .jar asset of the repo's latest release.
github_latest_jar() {
    local repo="$1" dest="$2"
    info "Fetching latest release info for $repo ..."
    local url
    url="$(curl -fsSL "https://api.github.com/repos/$repo/releases/latest" \
        | grep -o '"browser_download_url": *"[^"]*\.jar"' \
        | head -n1 | sed 's/.*"\(https[^"]*\)"/\1/')"
    [[ -n "$url" ]] || die "Could not find a .jar asset in the latest release of $repo"
    info "Downloading $(basename "$url") ..."
    curl -fL --progress-bar -o "$dest" "$url"
    ok "Saved to $dest"
}

info "Checking host tools ..."
require_java
require_adb
ok "java and adb are available"

if $CHECK_ONLY; then
    [[ -f "$CLI_JAR" ]]       && ok "Morphe CLI present: $CLI_JAR"       || warn "Morphe CLI missing (run setup.sh without --check)"
    [[ -f "$APKEDITOR_JAR" ]] && ok "APKEditor present: $APKEDITOR_JAR"  || warn "APKEditor missing (run setup.sh without --check)"
    [[ -f "$CONFIG_DIR/device.env" ]] && ok "device.env configured" || warn "config/device.env not created yet — copy device.env.example"
    exit 0
fi

# Morphe CLI — patches and installs APKs.
github_latest_jar "MorpheApp/morphe-cli" "$CLI_JAR"

# APKEditor — merges APKMirror .apkm / .apks / .xapk split bundles into a
# single APK, which the CLI requires as input.
github_latest_jar "REAndroid/APKEditor" "$APKEDITOR_JAR"

if [[ ! -f "$CONFIG_DIR/device.env" ]]; then
    warn "No device config yet. Create it with:"
    echo "      cp $CONFIG_DIR/device.env.example $CONFIG_DIR/device.env"
    echo "      then edit DEVICE_IP to match your Onn TV."
fi

ok "Setup complete. Next: scripts/build.sh to build the local patch bundle."
