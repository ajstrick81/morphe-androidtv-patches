#!/usr/bin/env bash
# Device graphics-memory health check for the Onn TV.
#
#   ./devicehealth.sh snapshot          # CMA / MemAvailable / uptime right now
#   ./devicehealth.sh scan [pkg]        # scan the log buffer for starvation
#   ./devicehealth.sh check [pkg]       # snapshot + scan, exit 1 if starved
#
# WHY THIS EXISTS
# ---------------
# On coffey (Amlogic S905X5M) a playback failure that looks exactly like a
# broken patch — infinite spinner, no picture — is often the DEVICE running out
# of contiguous graphics memory (CMA) rather than anything wrong with the APK.
# The decoder keeps churning and audio keeps advancing, but decoded frames can
# never get a display surface.
#
# It is invisible to the usual crash scan. There is no FATAL, no AndroidRuntime
# exception, no VerifyError, no DRM error. Grepping only for those reports a
# clean, healthy app while playback is dead. This has now been misdiagnosed as a
# patch bug twice (2026-10-31, 2026-11-08), the second time costing several
# pointless reinstalls, so the signature lives in the repo instead of in
# somebody's memory.
#
# The fix is a REBOOT, not a rebuild. CMA fragments over uptime and is consumed
# by other foreground apps (YouTube TV, cast mediashell, a long recent-tasks
# list); install/force-stop/relaunch churn makes it worse, so a long debugging
# session is itself a way to cause this.
#
# Run `check` BEFORE and AFTER any playback test. A test that starts starved
# produces no usable evidence either way.

source "$(dirname "$0")/lib.sh"

MODE="${1:-check}"
PKG_ARG="${2:-}"

# Thresholds, in MB. Measured on coffey: a starved device showed CmaFree 35MB
# of 536MB total with MemAvailable 425MB; after a reboot the same device showed
# CmaFree 121MB and MemAvailable 746MB and played fine. These sit between those
# two observations; override via the environment if the device changes.
CMA_FREE_MIN_MB="${CMA_FREE_MIN_MB:-60}"
MEM_AVAIL_MIN_MB="${MEM_AVAIL_MIN_MB:-500}"

# ── ADB target ────────────────────────────────────────────────────────
# lib.sh's load_device_config requires testing/config/device.env. This script
# is also useful straight from the manual E:\ loop where that file may not
# exist, so fall back rather than dying: ADB env var or MORPHE_ADB_TARGET, then
# the known coffey address.
ADB_BIN="${ADB:-adb}"
command -v "$ADB_BIN" >/dev/null 2>&1 \
    || die "adb not found. Put it on PATH or set ADB=/e/Android/Sdk/platform-tools/adb.exe"

if [[ -f "$CONFIG_DIR/device.env" ]]; then
    load_device_config
else
    ADB_TARGET="${MORPHE_ADB_TARGET:-192.168.12.210:5555}"
    warn "No config/device.env — using $ADB_TARGET (override with MORPHE_ADB_TARGET)"
fi

"$ADB_BIN" connect "$ADB_TARGET" >/dev/null 2>&1 || true
"$ADB_BIN" -s "$ADB_TARGET" get-state >/dev/null 2>&1 \
    || die "Device $ADB_TARGET is not reachable (asleep? DHCP drift? see onn-tv-ip-dhcp-drift)."
adbh() { "$ADB_BIN" -s "$ADB_TARGET" "$@"; }

# ── meminfo ───────────────────────────────────────────────────────────
# /proc/meminfo reports kB. CmaTotal/CmaFree are absent on kernels without CMA
# exposed, in which case the CMA half of this check simply reports unknown.
STARVED=0

snapshot() {
    local meminfo kb
    meminfo="$(adbh shell cat /proc/meminfo 2>/dev/null | tr -d '\r')"
    [[ -n "$meminfo" ]] || die "Could not read /proc/meminfo"

    field_mb() {
        kb="$(echo "$meminfo" | awk -v k="^$1:" '$0 ~ k {print $2; exit}')"
        if [[ -z "$kb" ]]; then echo ""; else echo $(( kb / 1024 )); fi
    }

    local cma_total cma_free mem_avail mem_total
    cma_total="$(field_mb CmaTotal)"
    cma_free="$(field_mb CmaFree)"
    mem_avail="$(field_mb MemAvailable)"
    mem_total="$(field_mb MemTotal)"

    info "Uptime:       $(adbh shell uptime 2>/dev/null | tr -d '\r' | sed 's/^ *//')"
    if [[ -n "$cma_free" && -n "$cma_total" ]]; then
        info "CMA free:     ${cma_free}MB of ${cma_total}MB   (want >= ${CMA_FREE_MIN_MB}MB)"
        if (( cma_free < CMA_FREE_MIN_MB )); then
            warn "CMA is low — contiguous graphics allocations will likely fail."
            STARVED=1
        fi
    else
        info "CMA free:     not reported by this kernel"
    fi
    if [[ -n "$mem_avail" && -n "$mem_total" ]]; then
        info "MemAvailable: ${mem_avail}MB of ${mem_total}MB   (want >= ${MEM_AVAIL_MIN_MB}MB)"
        if (( mem_avail < MEM_AVAIL_MIN_MB )); then
            warn "System memory is low."
            STARVED=1
        fi
    fi
}

# ── log scan ──────────────────────────────────────────────────────────
# Anchored, case-sensitive patterns. A loose regex here would be worse than
# nothing: an unanchored 'IMA' matches the word 'primary' in audio_hw_hal lines
# and buries the real hits, which is exactly how this was missed once already.
STARVE_PATTERNS='aml_cma_alloc|cma_alloc failed|libdmabufheap allocation failed|mali_gralloc.*(allocation failed|falling back to system heap)|error pushing blank frames|api_disconnect failed|ion_alloc.*fail'
HEALTHY_PATTERNS='ServiceDeviceTask.*INs=|kAtClient|res chg'

scan() {
    local pkg="$1" buf hits healthy
    if [[ -n "$pkg" ]]; then
        info "Scanning the log buffer (starvation is system-wide, so not pid-filtered)"
    fi
    buf="$(adbh logcat -d -v time 2>/dev/null | tr -d '\r')" || true
    [[ -n "$buf" ]] || { warn "Log buffer empty — nothing to scan."; return; }

    hits="$(echo "$buf" | grep -E "$STARVE_PATTERNS" || true)"
    if [[ -n "$hits" ]]; then
        warn "GRAPHICS-MEMORY STARVATION SIGNATURE FOUND — this is a device problem, not the patch:"
        echo "$hits" | tail -n 15 | sed 's/^/      /'
        STARVED=1
    else
        ok "No starvation signature in the current log buffer."
    fi

    healthy="$(echo "$buf" | grep -E "$HEALTHY_PATTERNS" || true)"
    if [[ -n "$healthy" ]]; then
        info "Healthy-decode markers present ($(echo "$healthy" | wc -l | tr -d ' ') lines) — frames are reaching the client."
    else
        info "No healthy-decode markers (INs=/OUTs= climbing to kAtClient, a clean 'res chg')."
        info "Expected if nothing has been played yet; suspicious if you just tried to play something."
    fi
}

remediation() {
    echo
    warn "Before trusting ANY playback result from this device:"
    echo "      1. adb -s $ADB_TARGET reboot      # clears CMA; the actual fix"
    echo "      2. wait for boot, then re-run:  $(basename "$0") snapshot"
    echo "      3. close other media apps (YouTube TV, cast mediashell) and clear recent tasks"
    echo "      4. only then run the playback test"
}

case "$MODE" in
    snapshot) snapshot ;;
    scan)     scan "$PKG_ARG" ;;
    check)    snapshot; echo; scan "$PKG_ARG" ;;
    *)        die "Usage: $(basename "$0") {snapshot|scan|check} [package]" ;;
esac

if (( STARVED )); then
    remediation
    die "Device looks starved — a playback test now proves nothing."
fi
ok "Device health looks adequate for a playback test."
