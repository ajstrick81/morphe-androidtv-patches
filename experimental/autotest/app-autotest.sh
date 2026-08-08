#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# app-autotest.sh — GENERIC guardrailed autonomous patch tester for Android TV.
#
# Drives a patched streaming app over adb (launch → resume a known-good item →
# in-player stress: seek / pause / resume → clean stop) while reading a data
# oracle from logcat + dumpsys, so ad-suppression + playback + resume can be
# validated without a human on the remote. Generalized from the Netflix
# nf-autotest.sh. See docs/AUTONOMOUS_APP_TESTING.md for the method + hard rules.
#
# Usage:
#   bash app-autotest.sh <profile> [cycles]
#     <profile>  name of a file in ./profiles/<profile>.env (e.g. netflix, primevideo)
#     [cycles]   in-player stress cycles (default 4)
#
# A profile is a small env file that sets (only PKG is required):
#   PKG        package name of the installed (patched) app                 [required]
#   ACT        launch component "pkg/activity"        (default: resolve via monkey)
#   LAUNCH     "monkey" | a deep-link URL to `am start -a VIEW -d`   (default monkey)
#   RESUME_KEYS space-sep keyevents to resume a known-good item
#                                          (default: "DPAD_CENTER DPAD_CENTER")
#   ARMED_RE   logcat regex proving the patch self-applied (optional; skipped if unset)
#   ORACLE_RE  logcat regex for the ad/behavior oracle line (optional)
#   ORACLE_BASELINE_RE  oracle lines to treat as "nothing happened" (filtered out)
#   ERROR_RE   logcat regex for crash/ANR/playback-error lines
#                        (default: "FATAL|ANR in <PKG>|SIGABRT|SIGSEGV|has died")
#
# HARD RULES (enforced below — do not weaken):
#   • No open-ended directional navigation. Only the profile's RESUME_KEYS +
#     in-player media keys. The TV screen is a secure surface you cannot see.
#   • Foreground-guard before EVERY input; abort if the target isn't foreground.
#   • Verify playback (media_session position>0) before any stress.
#   • Bounded cycles, then clean BACK to home.
#   • The oracle is READ-ONLY (logcat + dumpsys); it never sends input.
# ─────────────────────────────────────────────────────────────────────────────
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PROFILE="${1:-}"
CYCLES="${2:-4}"
[ -n "$PROFILE" ] || { echo "usage: bash app-autotest.sh <profile> [cycles]"; echo "profiles:"; ls "$HERE/profiles" 2>/dev/null | sed 's/\.env$//;s/^/  /'; exit 2; }
PF="$HERE/profiles/$PROFILE.env"
[ -f "$PF" ] || { echo "no such profile: $PF"; exit 2; }
# shellcheck disable=SC1090
. "$PF"
[ -n "${PKG:-}" ] || { echo "profile $PROFILE: PKG is required"; exit 2; }

ACT="${ACT:-}"
LAUNCH="${LAUNCH:-monkey}"
# ATTACH=1 (env or profile): skip launch+resume and attach to a title the user
# has ALREADY started playing. For apps whose home screen has no reliable
# blind-resume entry (e.g. Prime Video), start playback by hand, then run:
#   ATTACH=1 bash app-autotest.sh <profile> [cycles]
ATTACH="${ATTACH:-0}"
RESUME_KEYS="${RESUME_KEYS:-DPAD_CENTER DPAD_CENTER}"
ARMED_RE="${ARMED_RE:-}"
ORACLE_RE="${ORACLE_RE:-}"
ORACLE_BASELINE_RE="${ORACLE_BASELINE_RE:-}"
ERROR_RE="${ERROR_RE:-FATAL|ANR in $PKG|SIGABRT|SIGSEGV|has died}"

K(){ adb shell input keyevent "KEYCODE_$1" >/dev/null 2>&1; }
FG(){ adb shell dumpsys activity activities 2>/dev/null | grep -oE 'topResumedActivity=ActivityRecord\{[a-f0-9]+ u0 [^ }]+' | grep -oE '[^ ]+$' | head -1; }
# Playback probe. Default "position": require an ADVANCING media_session position
# (Netflix). "state": some native players (Prime Video's MPB engine) publish a
# MediaSession state but keep position pinned at 0 — so gate on the PKG-scoped
# state=PLAYING/PAUSED instead of position. Set PLAYBACK_PROBE=state per profile.
PLAYBACK_PROBE="${PLAYBACK_PROBE:-position}"
POS(){
  if [ "$PLAYBACK_PROBE" = "state" ]; then
    adb shell dumpsys media_session 2>/dev/null | grep -A1 "package=$PKG" | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' | head -1
  else
    adb shell dumpsys media_session 2>/dev/null | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' | grep -vE 'position=0$' | head -1
  fi
}
ISPLAY(){ POS | grep -q 'PLAYING'; }
lg(){ echo "$(date +%H:%M:%S) $*"; }
guard(){ case "$(FG)" in *"$PKG"*) return 0;; *) lg "  ABORT: $PKG not foreground (fg=$(FG)) — refusing input"; return 1;; esac; }
errcount(){ adb logcat -d 2>/dev/null | grep -icE "$ERROR_RE"; }
launch(){
  if [ "$LAUNCH" = "monkey" ]; then
    # Android TV apps register under LEANBACK_LAUNCHER, phone apps under LAUNCHER.
    # Pass both categories so monkey finds the launch activity either way (a TV
    # app like Prime Video has no LAUNCHER entry, so LAUNCHER-only launches nothing
    # and the foreground-guard then correctly aborts).
    adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  else
    if [ -n "$ACT" ]; then adb shell am start -n "$ACT" -a android.intent.action.VIEW -d "$LAUNCH" >/dev/null 2>&1
    else adb shell am start -a android.intent.action.VIEW -d "$LAUNCH" "$PKG" >/dev/null 2>&1; fi
  fi
}
wait_armed(){ # returns 0 if ARMED_RE seen within ~45s; 0 immediately if no ARMED_RE configured
  [ -n "$ARMED_RE" ] || return 0
  local n=0; while [ $n -lt 15 ]; do adb logcat -d 2>/dev/null | grep -qE "$ARMED_RE" && return 0; sleep 3; n=$((n+1)); done; return 1
}
oracle_dump(){ # print non-baseline oracle lines (if an oracle is configured)
  [ -n "$ORACLE_RE" ] || return 0
  local out; out=$(adb logcat -d 2>/dev/null | grep -oE "$ORACLE_RE")
  [ -n "$ORACLE_BASELINE_RE" ] && out=$(echo "$out" | grep -vE "$ORACLE_BASELINE_RE")
  echo "$out" | grep . | tail -12
}

lg "################ AUTOTEST [$PROFILE] pkg=$PKG cycles=$CYCLES ################"
adb get-state >/dev/null 2>&1 || { lg "no adb device — run 'adb connect <ip>:5555' first"; exit 1; }

if [ "$ATTACH" = "1" ]; then
  lg "=== ATTACH mode: using the title you already started ==="
  adb logcat -c >/dev/null 2>&1
  guard || { lg "target app is not foreground — start playback in $PKG first"; exit 1; }
  n=0; while [ $n -lt 10 ] && ! ISPLAY; do sleep 3; n=$((n+1)); done
  if ! ISPLAY; then cur=$(POS); lg "no active PLAYING session in $PKG (state: ${cur:-none}) — start a title playing, then re-run"; exit 0; fi
  S=$(POS); lg "attached, playing: $S"
else
  lg "=== launch + confirm armed ==="
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  launch
  sleep 22
  if wait_armed; then lg "patches armed: ${ARMED_RE:+YES}${ARMED_RE:-'(no arm signal configured — skipping check)'}"
  else lg "patches armed: NO ($ARMED_RE not seen) — aborting"; exit 1; fi

  guard || exit 1
  lg "=== controlled resume (keys: $RESUME_KEYS) ==="
  first=1
  for kk in $RESUME_KEYS; do [ $first -eq 1 ] && first=0 || sleep 4; K "$kk"; done
  sleep 12
  S=$(POS)
  if [ -z "$S" ]; then lg "no playback started — backing out (try ATTACH=1 for apps without a blind-resume entry)"; guard && K BACK; sleep 2; exit 0; fi
  lg "playing: $S"
fi

for c in $(seq 1 "$CYCLES"); do
  guard || break
  lg "--- cycle $c: seek (FF x6) ---"
  for s in 1 2 3 4 5 6; do K MEDIA_FAST_FORWARD; sleep 1; done
  K DPAD_CENTER; sleep 6; lg "cycle $c seeked: $(POS)"
  guard || break
  lg "--- cycle $c: pause + resume ---"
  K MEDIA_PLAY_PAUSE; sleep 7; lg "cycle $c paused: $(POS)"
  K MEDIA_PLAY_PAUSE; sleep 8; lg "cycle $c resumed: $(POS)"
  e=$(errcount); [ "$e" -gt 0 ] && lg "cycle $c WARNING: $e error line(s) in logcat"
done

guard && { lg "=== clean stop (BACK to home) ==="; K BACK; sleep 2; K BACK; sleep 2; }

lg "=== REPORT ==="
if [ -n "$ORACLE_RE" ]; then lg "oracle (non-baseline):"; oracle_dump | sed 's/^/  /' | grep . || echo "  (no non-baseline oracle lines)"; else lg "oracle: none configured for this profile"; fi
lg "errors:"; adb logcat -d 2>/dev/null | grep -iE "$ERROR_RE" | tail -4 | sed 's/^/  /' | grep . || echo "  none"
lg "final fg: $(FG)"
lg "################ DONE ################"
