#!/usr/bin/env bash
# Builds the patch bundle (.mpp) from the working tree and stages it as
# testing/out/patches-local.mpp for patch.sh to use.
#
#   ./build.sh          incremental build
#   ./build.sh --clean  clean build (what CI does)

source "$(dirname "$0")/lib.sh"

require_java

GRADLE_TASKS=(":patches:buildAndroid")
[[ "${1:-}" == "--clean" ]] && GRADLE_TASKS=("clean" "${GRADLE_TASKS[@]}")

info "Building patch bundle from working tree ..."
(cd "$REPO_ROOT" && ./gradlew "${GRADLE_TASKS[@]}")

# Grab the newest bundle the build produced.
BUNDLE="$(find "$REPO_ROOT/patches/build/libs" -name '*.mpp' -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | head -n1 | cut -d' ' -f2-)"
[[ -n "$BUNDLE" ]] || die "Build finished but no .mpp found under patches/build/libs — check the Gradle output."

cp -f "$BUNDLE" "$LOCAL_BUNDLE"
ok "Local bundle staged: $LOCAL_BUNDLE  (from $(basename "$BUNDLE"))"

# Show what's inside so you can confirm your patch made it in.
if [[ -f "$CLI_JAR" ]]; then
    info "Patches in this bundle:"
    java -jar "$CLI_JAR" list-patches "$LOCAL_BUNDLE" | sed 's/^/    /' || true
else
    warn "Morphe CLI not downloaded yet (scripts/setup.sh) — skipping bundle listing."
fi
