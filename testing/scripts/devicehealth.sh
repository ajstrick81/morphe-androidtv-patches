#!/usr/bin/env bash
# Device graphics-memory health check for the Onn TV.
#
#   ./devicehealth.sh snapshot          # CMA / MemAvailable / uptime right now
#   ./devicehealth.sh scan [pkg]        # scan the log buffer for starvation
#   ./devicehealth.sh check [pkg]       # snapshot + scan, exit 10 if starved
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

# Sourcing lib.sh runs work of its own (mkdir -p, and it installs
# `set -euo pipefail`) BEFORE this script can override die(), so a failure in
# there would exit 1 while the override does not yet exist. Guarding the source
# maps that window to the setup code. Plain echo, not lib.sh's helpers — they
# may not be defined if this fails.
# shellcheck source=/dev/null
if ! source "$(dirname "$0")/lib.sh"; then
    echo "[x] devicehealth: failed to initialise $(dirname "$0")/lib.sh" >&2
    exit 4
fi

# The starvation VERDICT is exit 10, not 1 — see STARVED_EXIT below. Exit 1 was
# the verdict until round five of review showed the claim "no error can exit 1"
# was unprovable: the shell itself produces 1 from `set -e` on a failing
# assignment, from a failure inside lib.sh before the override is installed, and
# from paths nobody has enumerated yet. Inverting it is airtight where
# enumeration is not: the verdict uses a status nothing else emits, so an
# unexpected failure can never be read as "the device is starved".
STARVED_EXIT=10

# Errors still get their own code too, so a setup problem is distinguishable
# from a measurement result.
# lib.sh's die() exits 1, and it is reachable from lib.sh's own helpers — e.g.
# load_device_config() dies if device.env exists but sets neither DEVICE_SERIAL
# nor DEVICE_IP — so patching this script's own call sites was not enough: a
# malformed config would still have reported as "the device is starved".
# Overriding die() here makes that structurally impossible, whatever the origin.
# Errors are 4. The verdict is emitted explicitly at the bottom, never via a
# helper.
die()  { echo -e "${C_ERR}[✗]${C_OFF} $*" >&2; exit 4; }
fail() { die "$@"; }

MODE="${1:-check}"
PKG_ARG="${2:-}"

# Thresholds, in MB. Measured on coffey: a starved device showed CmaFree 35MB
# of 536MB total with MemAvailable 425MB; after a reboot the same device showed
# CmaFree 121MB and MemAvailable 746MB and played fine. These sit between those
# two observations; override via the environment if the device changes.
# These are PROVISIONAL, DEVICE-SPECIFIC TRIPWIRES, not a definition of
# starvation. Clearing them does not mean an allocation of a given size will
# succeed — allocation success depends on contiguity and request size, which
# this cannot see. Raw values are always printed so the numbers, not the
# verdict, are what you reason from.
CMA_FREE_MIN_MB="${CMA_FREE_MIN_MB:-60}"
MEM_AVAIL_MIN_MB="${MEM_AVAIL_MIN_MB:-500}"
# Also checked as a fraction of CmaTotal, so the tripwire still means something
# if CmaTotal changes. Measured: starved 35/536 = 6%, healthy 121/536 = 22%.
CMA_FREE_MIN_PCT="${CMA_FREE_MIN_PCT:-15}"

# ── ADB target ────────────────────────────────────────────────────────
# lib.sh's load_device_config requires testing/config/device.env. This script
# is also useful straight from the manual E:\ loop where that file may not
# exist, so fall back rather than dying.
#
# Precedence, highest first: MORPHE_ADB_TARGET, then device.env, then the known
# coffey address. MORPHE_ADB_TARGET is applied AFTER load_device_config on
# purpose — an earlier version only consulted it when device.env was absent, so
# an operator who set it while a config existed was silently checked against the
# configured device instead. Pointing a health check at the wrong device is
# worse than failing.
ADB_BIN="${ADB:-adb}"
command -v "$ADB_BIN" >/dev/null 2>&1 \
    || fail "adb not found. Put it on PATH or set ADB=/e/Android/Sdk/platform-tools/adb.exe"

if [[ -f "$CONFIG_DIR/device.env" ]]; then
    load_device_config
    if [[ -n "${MORPHE_ADB_TARGET:-}" && "$MORPHE_ADB_TARGET" != "$ADB_TARGET" ]]; then
        warn "MORPHE_ADB_TARGET=$MORPHE_ADB_TARGET overrides device.env ($ADB_TARGET)"
        ADB_TARGET="$MORPHE_ADB_TARGET"
    fi
else
    ADB_TARGET="${MORPHE_ADB_TARGET:-192.168.12.210:5555}"
    warn "No config/device.env — using $ADB_TARGET (override with MORPHE_ADB_TARGET)"
fi
info "Target:       $ADB_TARGET"

"$ADB_BIN" connect "$ADB_TARGET" >/dev/null 2>&1 || true
"$ADB_BIN" -s "$ADB_TARGET" get-state >/dev/null 2>&1 \
    || fail "Device $ADB_TARGET is not reachable (asleep? DHCP drift? see onn-tv-ip-dhcp-drift)."
adbh() { "$ADB_BIN" -s "$ADB_TARGET" "$@"; }

# ── meminfo ───────────────────────────────────────────────────────────
# /proc/meminfo reports kB. CmaTotal/CmaFree are absent on kernels that don't
# expose CMA, and that case must NOT be reported as healthy: the whole point of
# this script is to assess contiguous-graphics-memory risk, so being unable to
# see it is an UNKNOWN result, not a pass.
#
# Two kinds of "unknown" are reported separately, because they are not equally
# serious and a caller may reasonably accept one but not the other:
#
#   0  pass — no starvation signature, and every value read clears the
#      provisional tripwires
#  10  STARVED — either the measured values are below the tripwires, or the
#      starvation signature is present in the log. Note the signature is
#      historical evidence: if it is there, the recent playback test is suspect
#      even when memory has since recovered (killing the app frees CMA).
#      Deliberately NOT 1: see STARVED_EXIT at the top.
#
#   Any other status (1 included, plus 126/127/128+n) is an UNEXPECTED FAILURE.
#   Callers must treat anything outside {0, 2, 3, 4, 10} as an error, never as
#   a starvation verdict.
#   2  TELEMETRY UNKNOWN — the memory values themselves could not be read
#      (missing CmaFree/CmaTotal or MemAvailable). The main risk is
#      unassessable. Treat as "not a pass" unless you know this kernel never
#      reports CMA.
#   4  COULD NOT RUN — setup or connectivity failure (adb missing, device
#      unreachable, bad usage). Deliberately NOT 1: lib.sh's die() exits 1,
#      which this script reserves for a starvation verdict, so every die path
#      here would otherwise be indistinguishable from "the device is starved".
#      An automated gate would have rebooted the TV over a typo in the mode
#      argument. Use fail() for these, never die().
#   3  SCAN INCONCLUSIVE — memory was readable and passed, but the log buffer
#      was empty so the signature could not be looked for. This is the EXPECTED
#      result immediately after `adb logcat -c`, which logs.sh and our own test
#      procedure both do, so it must not be conflated with code 2. A strict
#      gate can still refuse anything non-zero; a caller that just cleared the
#      buffer can accept 3.
STARVED=0
UNKNOWN_TELEMETRY=0
SCAN_INCONCLUSIVE=0

snapshot() {
    local meminfo kb
    # A failed OR empty read is TELEMETRY UNKNOWN, not a hard exit, and
    # returning instead of exiting lets `check` still run the log scan — the
    # signature can be found even when /proc/meminfo could not be read.
    #
    # The assignment MUST be guarded by `if !`. Under `set -euo pipefail` a
    # failing pipeline in a plain assignment exits the shell immediately, with
    # the pipeline's status, before any following emptiness check can run — so
    # `meminfo=$(...)` on its own line would have exited 1 (the old starvation
    # code) whenever adb failed, defeating the whole point of handling this
    # gracefully. Verified: `set -euo pipefail; v="$(false | tr -d x)"` exits 1.
    if ! meminfo="$(adbh shell cat /proc/meminfo 2>/dev/null | tr -d '\r')"; then
        warn "Reading /proc/meminfo failed — memory telemetry unavailable"
        warn "(an adb/transport failure, as distinct from a kernel that omits the CMA fields)."
        UNKNOWN_TELEMETRY=1
        return
    fi
    if [[ -z "$meminfo" ]]; then
        warn "Could not read /proc/meminfo — memory telemetry unavailable"
        warn "(an adb/read failure, as distinct from a kernel that omits the CMA fields)."
        UNKNOWN_TELEMETRY=1
        return
    fi

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

    if [[ -n "$cma_free" && -n "$cma_total" && "$cma_total" -gt 0 ]]; then
        local pct=$(( cma_free * 100 / cma_total ))
        info "CMA free:     ${cma_free}MB of ${cma_total}MB (${pct}%)   tripwire: >= ${CMA_FREE_MIN_MB}MB and >= ${CMA_FREE_MIN_PCT}%"
        if (( cma_free < CMA_FREE_MIN_MB || pct < CMA_FREE_MIN_PCT )); then
            warn "CMA is low — contiguous graphics allocations are likely to fail."
            STARVED=1
        fi
    else
        warn "CMA free:     NOT REPORTED by this kernel — the main risk cannot be assessed."
        UNKNOWN_TELEMETRY=1
    fi

    if [[ -n "$mem_avail" && -n "$mem_total" ]]; then
        info "MemAvailable: ${mem_avail}MB of ${mem_total}MB   tripwire: >= ${MEM_AVAIL_MIN_MB}MB"
        if (( mem_avail < MEM_AVAIL_MIN_MB )); then
            warn "System memory is low."
            STARVED=1
        fi
    else
        warn "MemAvailable: NOT REPORTED — cannot assess system memory."
        UNKNOWN_TELEMETRY=1
    fi
}

# ── log scan ──────────────────────────────────────────────────────────
# These are SPECIFIC, CASE-SENSITIVE SUBSTRINGS — they are NOT regex-anchored,
# and an earlier comment here wrongly called them "anchored". What protects
# against incidental matches is the specificity of each phrase, not an anchor:
# a short loose token is what causes trouble (an unanchored, case-INsensitive
# 'IMA' matches "primary" inside audio_hw_hal_primary lines, which buried real
# hits once already). grep -E is case-sensitive by default, which is relied on.
#
# NOT YET VALIDATED against a corpus of this device's logs for false positives;
# the device is offline. Treat a lone hit as a prompt to read the surrounding
# lines, not as proof on its own.
#
# DELIBERATELY SENSITIVE RATHER THAN SPECIFIC. The costs are wildly asymmetric:
# a false positive means an unnecessary reboot (two minutes), while a false
# negative means the gate passes a starved device and a whole session gets
# misdiagnosed — which is the exact failure this script was written after. So
# where the two trade off, this errs toward catching it. 'api_disconnect failed'
# is the clearest example: it is kept tag-free even though the only form we have
# observed is on a SurfaceUtils line, because a starvation that logs it under
# another tag must not slip through. If log-corpus validation later shows it
# firing on benign surface teardown, tighten it then, with evidence.
#
# 'mali_gralloc' needs only the system-heap fallback phrasing: the observed line
# ("mali_gralloc libdmabufheap allocation failed, falling back to system heap")
# is already matched by the bare 'libdmabufheap allocation failed' alternative,
# so repeating that inside a mali_gralloc branch was dead weight.
STARVE_PATTERNS='aml_cma_alloc|cma_alloc failed|libdmabufheap allocation failed|mali_gralloc.*falling back to system heap|SurfaceUtils.*error pushing blank frames|api_disconnect failed|ion_alloc.*fail'

# Advisory only — never affects the exit status. Seeing 'kAtClient' or 'res chg'
# somewhere in the buffer does not establish that frames are reaching the client
# right now; they may be from an earlier, healthy playback. Context, not proof.
HEALTHY_PATTERNS='ServiceDeviceTask.*INs=|kAtClient|res chg'

scan() {
    local pkg="$1" buf hits healthy
    if [[ -n "$pkg" ]]; then
        info "Scanning the log buffer (starvation is system-wide, so not pid-filtered)"
    fi
    buf="$(adbh logcat -d -v time 2>/dev/null | tr -d '\r')" || true
    if [[ -z "$buf" ]]; then
        warn "Log buffer empty — the starvation signature could not be looked for."
        SCAN_INCONCLUSIVE=1
        return
    fi

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
        info "Healthy-decode markers seen ($(echo "$healthy" | wc -l | tr -d ' ') lines) — advisory only; they may predate the test you care about."
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
    *)        fail "Usage: $(basename "$0") {snapshot|scan|check} [package]" ;;
esac

# Precedence: a positive starvation finding outranks either unknown, and a
# telemetry failure (code 2) outranks a merely inconclusive scan (code 3),
# because the former means the main risk is unmeasurable while the latter only
# means the historical log evidence is missing.
if (( STARVED )); then
    remediation
    # Emitted explicitly, not through die()/fail(): those mean "error" (exit 4),
    # while this is the measurement verdict (exit 10).
    echo -e "${C_ERR}[✗]${C_OFF} Device looks starved — a playback test now proves nothing." >&2
    exit "$STARVED_EXIT"
fi
if (( UNKNOWN_TELEMETRY )); then
    echo
    warn "TELEMETRY UNKNOWN (exit 2) — the contiguous-graphics-memory risk could"
    warn "not be assessed, because the memory values themselves were unreadable."
    warn "This is NOT an all-clear. Treat it as 'not a pass' unless you know this"
    warn "kernel never reports CMA; then read the raw values above and decide"
    warn "deliberately."
    exit 2
fi
if (( SCAN_INCONCLUSIVE )); then
    echo
    ok   "Memory values read fine and clear the provisional tripwires."
    warn "SCAN INCONCLUSIVE (exit 3) — the log buffer was empty, so the starvation"
    warn "signature could not be looked for. This is the EXPECTED result right"
    warn "after 'adb logcat -c'. The device looks adequate on the numbers; there is"
    warn "simply no log history to corroborate it. Safe to proceed if you just"
    warn "cleared the buffer; re-run after the test to check for the signature."
    exit 3
fi
ok "No starvation signature, and the values read clear the provisional tripwires."
info "That is a tripwire passing, not proof the device can allocate what playback needs."
