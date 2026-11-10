#!/usr/bin/env bash
# Self-test for devicehealth.sh — no device needed.
#
#   ./devicehealth-selftest.sh
#
# Drives devicehealth.sh against a FAKE adb that returns canned /proc/meminfo
# and logcat output, and asserts the documented exit code for each case.
#
# WHY: four rounds of review on devicehealth.sh found four separate ways an
# error could be mistaken for a starvation verdict, and every one of them was a
# path nobody had executed — they were found by reading, and the last two were
# only provable with a shell probe. The exit contract is the whole value of a
# preflight gate, so it is worth having it regression-tested rather than
# re-audited. This cannot validate the log PATTERNS (that needs real device
# logs), only the contract.
#
# Contract under test:
#   0  pass            2  telemetry unknown     3  scan inconclusive
#   4  could not run   10  starved
#   anything else = unexpected failure, never a verdict

set -uo pipefail   # deliberately NOT -e: we want to observe failing exits

SELFTEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$SELFTEST_DIR/devicehealth.sh"
[[ -f "$SUT" ]] || { echo "cannot find devicehealth.sh next to this script" >&2; exit 4; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

# fake_adb <name> <meminfo-printf-or-FAIL> <logcat-line>
fake_adb() {
    local name="$1" meminfo="$2" logline="$3" path="$TMP/$1"
    {
        echo '#!/usr/bin/env bash'
        echo 'case "$*" in'
        echo '  *connect*)   exit 0 ;;'
        echo '  *get-state*) echo device; exit 0 ;;'
        if [[ "$meminfo" == "FAIL" ]]; then
            echo '  *"cat /proc/meminfo"*) exit 1 ;;'
        else
            echo "  *\"cat /proc/meminfo\"*) printf '%s' \"\$(cat <<'MEMEOF'"
            printf '%s\n' "$meminfo"
            echo "MEMEOF"
            echo ')"; exit 0 ;;'
        fi
        echo '  *uptime*)    echo " 10:00:00 up 2 days"; exit 0 ;;'
        if [[ "$logline" == "FAIL" ]]; then
            # logcat COMMAND failure, as distinct from a successful empty read.
            echo '  *logcat*)    exit 1 ;;'
        elif [[ -z "$logline" ]]; then
            echo '  *logcat*)    exit 0 ;;'
        else
            echo "  *logcat*)    echo '$logline'; exit 0 ;;"
        fi
        echo '  *) exit 0 ;;'
        echo 'esac'
    } > "$path"
    chmod +x "$path"
    echo "$path"
}

# expect <described> <wanted-exit> <adb-path> [mode]
expect() {
    local what="$1" want="$2" adb="$3" mode="${4:-check}" got
    ADB="$adb" MORPHE_ADB_TARGET=127.0.0.1:5555 bash "$SUT" "$mode" >"$TMP/out" 2>&1
    got=$?
    if [[ "$got" == "$want" ]]; then
        echo "  PASS  $what (exit $got)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL  $what — wanted exit $want, got $got"
        sed 's/^/          /' "$TMP/out"
        FAIL=$((FAIL + 1))
    fi
}

HEALTHY_MEM='MemTotal:        1904112 kB
MemAvailable:    1200000 kB
CmaTotal:         548864 kB
CmaFree:          123904 kB'

STARVED_MEM='MemTotal:        1904112 kB
MemAvailable:     435264 kB
CmaTotal:         548864 kB
CmaFree:           36096 kB'

NO_CMA_MEM='MemTotal:        1904112 kB
MemAvailable:    1200000 kB'

echo "devicehealth.sh exit-contract self-test"
echo

# 0 — healthy numbers, a populated log with no signature.
expect "healthy device, clean log -> pass" 0 \
    "$(fake_adb adb_ok "$HEALTHY_MEM" '11-09 10:00:00 I SomeTag: nothing interesting')"

# 10 — the 2026-10-31 real numbers, plus the signature.
expect "starved numbers + signature -> verdict" 10 \
    "$(fake_adb adb_starved "$STARVED_MEM" '11-09 10:00:00 E aml_cma_alloc heap-gfx: alloc failed ret:-12')"

# 10 — signature alone, memory already recovered (the historical-evidence case).
expect "signature only, memory recovered -> verdict" 10 \
    "$(fake_adb adb_sig_only "$HEALTHY_MEM" '11-09 10:00:00 E SurfaceUtils: error pushing blank frames: api_disconnect failed')"

# 2 — adb fails the read. This exited 1 (= old verdict code) before round five.
expect "failing /proc/meminfo read -> telemetry unknown" 2 \
    "$(fake_adb adb_memfail FAIL '11-09 10:00:00 I SomeTag: quiet')"

# 2 — kernel omits the CMA fields entirely.
expect "kernel without CMA fields -> telemetry unknown" 2 \
    "$(fake_adb adb_nocma "$NO_CMA_MEM" '11-09 10:00:00 I SomeTag: quiet')"

# 3 — good numbers, empty buffer. The expected state right after `logcat -c`.
expect "healthy numbers, empty log buffer -> scan inconclusive" 3 \
    "$(fake_adb adb_nolog "$HEALTHY_MEM" '')"

# 4 — logcat COMMAND fails. Must not borrow the benign "empty buffer" message:
# before round six this produced exit 3 and told the operator it was probably
# safe to proceed.
expect "failing logcat command -> could not run" 4 \
    "$(fake_adb adb_logfail "$HEALTHY_MEM" FAIL)"

# 4 — adb missing entirely.
expect "adb not found -> could not run" 4 "$TMP/definitely-not-adb"

# 4 — bad mode argument must NOT look like a verdict.
expect "unknown mode -> could not run" 4 \
    "$(fake_adb adb_mode "$HEALTHY_MEM" '11-09 10:00:00 I SomeTag: quiet')" "bogusmode"

# 4 — malformed device.env (lib.sh dies internally; must not surface as 1).
MALFORMED="$TMP/tree"
mkdir -p "$MALFORMED/scripts" "$MALFORMED/config"
cp "$SELFTEST_DIR/lib.sh" "$SUT" "$MALFORMED/scripts/"
printf '# neither DEVICE_IP nor DEVICE_SERIAL\nDEVICE_PORT=5555\n' > "$MALFORMED/config/device.env"
ADB="$(fake_adb adb_env "$HEALTHY_MEM" 'x')" bash "$MALFORMED/scripts/devicehealth.sh" check >"$TMP/out" 2>&1
got=$?
if [[ "$got" == 4 ]]; then
    echo "  PASS  malformed device.env -> could not run (exit 4)"
    PASS=$((PASS + 1))
else
    echo "  FAIL  malformed device.env — wanted exit 4, got $got"
    sed 's/^/          /' "$TMP/out"
    FAIL=$((FAIL + 1))
fi

echo
echo "  $PASS passed, $FAIL failed"
[[ "$FAIL" == 0 ]] || exit 1
echo "  Exit contract holds. (Log PATTERN accuracy still needs real device logs.)"
