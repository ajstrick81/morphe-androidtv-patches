#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# health-monitor.sh — GENERIC, app-independent stream babysitter for Android TV.
#
# Plays a patched streaming app for a long stretch and continuously verifies the
# stream stays HEALTHY and AD-FREE — not just that a patch "Applied". Every tick
# it records playback state/position (stall + error detection), scans logcat for
# crash/ANR and for ad-defense vs. ad-leak signals, and saves a screenshot (the
# only ground truth for a visual ad with no log signal). Optionally ROTATES
# through a list of titles every N minutes so one run covers multiple pieces of
# content — content-specific ad leaks only show on *some* titles.
#
# Born from the HBO Max #125 session (v1.29.3): patched HBO looked clean on the
# first title, so we babysat ~36 min across several movies to be sure. This tool
# generalizes that. Companion to app-autotest.sh (short stress test); this is the
# long-haul continuity/ad watch.
#
# Usage:
#   bash health-monitor.sh <profile> [duration_min]
#     <profile>       file in ./profiles/<profile>.env (e.g. hbomax, netflix)
#     [duration_min]  total run length in minutes (default 60)
#
# Profile schema (superset-compatible with app-autotest profiles; only PKG req'd):
#   PKG            installed (patched) package name.                     [required]
#   LAUNCH         "monkey" (open from launcher) | "attach" (monitor what is
#                  ALREADY playing, no launch/rotation).          (default monkey)
#   ACT            explicit "pkg/activity" for launch (optional; else resolved).
#   RESUME_KEYS    keyevents (no KEYCODE_ prefix) to start playback after a launch
#                  or a title switch.                  (default "DPAD_CENTER DPAD_CENTER")
#   TITLES         newline/space list of DEEP LINKS to rotate through, each an
#                  `am start -a VIEW -d <url>` target (scheme://... or https://...).
#                  Empty = single-title mode (launch/attach once, no rotation).
#                  Deep links are the ONLY safe rotation vector — NO blind D-pad
#                  nav (the TV is a surface you cannot see).
#   ROTATE_MIN     minutes per title before switching to the next.      (default 18)
#   TICK_SECS      seconds between health snapshots.                     (default 30)
#   STRESS_EVERY_MIN  run a play->BACK->resume->seek->play stress cycle every N
#                  minutes to exercise the resume/seek seams where ad re-insertion
#                  hides (0 = off).                                       (default 0)
#   SEEK_KEYS      keyevents used for the seek beat of the stress cycle.
#                                       (default "MEDIA_FAST_FORWARD MEDIA_FAST_FORWARD")
#   PLAYBACK_PROBE "position" (require advancing position) | "state" (players that
#                  pin position at 0 but publish state=PLAYING).    (default position)
#   SUPPRESS_RE    logcat regex = HEALTHY ad-defense activity (guard fired / empty
#                  ad response). Informational; counted per tick.        (optional)
#   AD_LEAK_RE     logcat regex that should NEVER appear = a real ad rendering /
#                  ad timeline being built. A match raises a hard ALERT. (optional)
#   ERROR_RE       crash/ANR/playback-error regex.
#                        (default "FATAL|ANR in <PKG>|SIGABRT|SIGSEGV|has died|ExoPlaybackException")
#   SERIAL         adb device serial (else uses the single connected device / $ANDROID_SERIAL).
#
# HARD RULES (do not weaken — inherited from app-autotest):
#   • Rotation uses profile deep links ONLY. No open-ended directional navigation.
#   • Foreground-guard before EVERY input; abort inputs if target isn't foreground.
#   • Oracle is READ-ONLY (logcat + dumpsys + screencap); it never drives the UI
#     except the bounded RESUME_KEYS after an explicit launch/rotation.
#   • Screenshots are ground truth for "is an ad on screen" — always captured.
# ─────────────────────────────────────────────────────────────────────────────
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PROFILE="${1:-}"
DURATION_MIN="${2:-60}"
[ -n "$PROFILE" ] || { echo "usage: bash health-monitor.sh <profile> [duration_min]"; echo "profiles:"; ls "$HERE/profiles" 2>/dev/null | sed 's/\.env$//;s/^/  /'; exit 2; }
PF="$HERE/profiles/$PROFILE.env"
[ -f "$PF" ] || { echo "no such profile: $PF"; exit 2; }
# shellcheck disable=SC1090
. "$PF"
[ -n "${PKG:-}" ] || { echo "profile $PROFILE: PKG is required"; exit 2; }

LAUNCH="${LAUNCH:-monkey}"
ACT="${ACT:-}"
RESUME_KEYS="${RESUME_KEYS:-DPAD_CENTER DPAD_CENTER}"
TITLES="${TITLES:-}"
ROTATE_MIN="${ROTATE_MIN:-18}"
TICK_SECS="${TICK_SECS:-30}"
STRESS_EVERY_MIN="${STRESS_EVERY_MIN:-0}"
SEEK_KEYS="${SEEK_KEYS:-MEDIA_FAST_FORWARD MEDIA_FAST_FORWARD}"
PLAYBACK_PROBE="${PLAYBACK_PROBE:-position}"
SUPPRESS_RE="${SUPPRESS_RE:-}"
AD_LEAK_RE="${AD_LEAK_RE:-}"
ERROR_RE="${ERROR_RE:-FATAL|ANR in $PKG|SIGABRT|SIGSEGV|has died|ExoPlaybackException}"
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"

# adb wrapper honoring an optional serial.
A(){ if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }

# Output dir for this run (status log + screenshots), timestamped.
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${HM_OUT:-$HERE/runs/$PROFILE-$STAMP}"
mkdir -p "$OUT"
ST="$OUT/status.log"

K(){ A shell input keyevent "KEYCODE_$1" >/dev/null 2>&1; }
FG(){ A shell dumpsys activity activities 2>/dev/null | grep -oE 'topResumedActivity=ActivityRecord\{[a-f0-9]+ u0 [^ }]+' | grep -oE '[^ ]+$' | head -1; }
guard(){ case "$(FG)" in *"$PKG"*) return 0;; *) return 1;; esac; }
lg(){ echo "$(date +%H:%M:%S) $*" | tee -a "$ST"; }

# Playback state line, scoped to PKG. "position" mode drops position=0 (idle);
# "state" mode keeps it (players that pin position at 0 while PLAYING).
PS(){
  if [ "$PLAYBACK_PROBE" = "state" ]; then
    A shell dumpsys media_session 2>/dev/null | grep -A2 "package=$PKG" | grep -m1 -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+'
  else
    # position mode: a PLAYING session pinned at position=0 is idle/stale, not
    # real playback — drop it so a lingering ended session doesn't read as a stall.
    A shell dumpsys media_session 2>/dev/null | grep "state=PlaybackState" | grep -m1 -E 'PLAYING|PAUSED|STOPPED|BUFFERING' | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' | grep -vE 'position=0$'
  fi
}

launch_from_launcher(){
  if [ -n "$ACT" ]; then
    A shell am start -n "$ACT" >/dev/null 2>&1
  else
    # ATV apps register under LEANBACK_LAUNCHER; fall back to LAUNCHER.
    local comp
    comp=$(A shell "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $PKG" 2>/dev/null | tail -1 | tr -d '\r')
    case "$comp" in */*) A shell am start -n "$comp" >/dev/null 2>&1;;
      *) A shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" >/dev/null 2>&1 \
           || A shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1;; esac
  fi
}

# Open one title by deep link, then send RESUME_KEYS to begin playback.
open_title(){
  local url="$1"
  lg ">> switching title: $url"
  A shell am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1
  sleep 8
  if ! guard; then lg "  WARN: $PKG not foreground after deep link (fg=$(FG)); skipping resume keys"; return; fi
  for k in $RESUME_KEYS; do K "$k"; sleep 2; done
}

# Exercise the resume/seek seams where ad re-insertion hides: from the player,
# BACK out (to details/home) -> RESUME_KEYS to resume -> SEEK_KEYS to fast-forward
# -> let it settle. Media keys + profile RESUME_KEYS only, foreground-guarded — no
# blind nav. Any ad re-inserted on resume/seek then shows up in the ticks that
# follow (screenshot + SUPPRESS_RE/AD_LEAK_RE/ERROR_RE).
stress_cycle(){
  guard || { lg "  stress: $PKG not foreground (fg=$(FG)); skipping"; return; }
  lg ">> stress: BACK -> resume -> seek"
  K BACK; sleep 4
  if ! guard; then lg "  stress: BACK left $PKG (fg=$(FG)); skipping resume/seek"; return; fi
  for k in $RESUME_KEYS; do K "$k"; sleep 2; done
  sleep 3
  for k in $SEEK_KEYS; do K "$k"; sleep 2; done
}

# ── bring the stream up ──────────────────────────────────────────────────────
set -- $TITLES              # positional list of titles (word-split intentional)
NTITLES=$#
ROTATE=0
[ "$NTITLES" -ge 1 ] && [ "$LAUNCH" != "attach" ] && ROTATE=1

lg "=== Health Monitor: $PROFILE ($PKG) ==="
lg "duration=${DURATION_MIN}m tick=${TICK_SECS}s rotate=$([ $ROTATE = 1 ] && echo "${ROTATE_MIN}m across ${NTITLES} titles" || echo off) stress=$([ "$STRESS_EVERY_MIN" -gt 0 ] && echo "${STRESS_EVERY_MIN}m" || echo off) out=$OUT"

if [ "$LAUNCH" = "attach" ]; then
  lg "attach mode: monitoring content already playing (no launch)"
elif [ "$ROTATE" = 1 ]; then
  open_title "$1"          # first title
else
  lg ">> launching $PKG from launcher"
  launch_from_launcher; sleep 8
  if guard; then for k in $RESUME_KEYS; do K "$k"; sleep 2; done; else lg "  WARN: launch did not foreground $PKG"; fi
fi

# ── monitor loop ─────────────────────────────────────────────────────────────
TOTAL_TICKS=$(( DURATION_MIN * 60 / TICK_SECS ))
TICKS_PER_ROT=$(( ROTATE_MIN * 60 / TICK_SECS )); [ "$TICKS_PER_ROT" -lt 1 ] && TICKS_PER_ROT=1
TICKS_PER_STRESS=0
[ "$STRESS_EVERY_MIN" -gt 0 ] && { TICKS_PER_STRESS=$(( STRESS_EVERY_MIN * 60 / TICK_SECS )); [ "$TICKS_PER_STRESS" -lt 1 ] && TICKS_PER_STRESS=1; }
titleidx=1; prevpos=-1; stall=0; alerts=0; leaks=0

A logcat -c >/dev/null 2>&1
for t in $(seq 1 "$TOTAL_TICKS"); do
  ts=$(date +%H:%M:%S)
  ps="$(PS)"
  state=$(echo "$ps" | grep -oE 'state=[A-Z]+\([0-9]\)' | head -1)
  pos=$(echo "$ps"   | grep -oE 'position=[0-9]+' | head -1 | cut -d= -f2)

  # recent logcat window (since last clear/tick)
  win="$OUT/.win"
  A logcat -d -v time >"$win" 2>/dev/null; A logcat -c >/dev/null 2>&1
  fatal=$(grep -icE "$ERROR_RE" "$win")
  suppress=0; [ -n "$SUPPRESS_RE" ] && suppress=$(grep -icE "$SUPPRESS_RE" "$win")
  leak=0;     [ -n "$AD_LEAK_RE" ] && leak=$(grep -icE "$AD_LEAK_RE" "$win")

  # stall detection: position frozen while PLAYING
  if [ "${pos:-x}" = "$prevpos" ] && echo "$state" | grep -q PLAYING; then stall=$((stall+1)); else stall=0; fi
  prevpos="${pos:-x}"

  alert=""
  [ "$stall" -ge 3 ]        && alert="${alert}[STALL x$stall] "
  [ -n "$state" ] || alert="${alert}[NO-STATE] "
  echo "$state" | grep -qE 'PLAYING|PAUSED|BUFFERING' || [ -z "$state" ] || alert="${alert}[STATE:$state] "
  [ "${fatal:-0}" -gt 0 ]   && alert="${alert}[FATAL:$fatal] "
  [ "${leak:-0}"  -gt 0 ]   && { alert="${alert}[AD-LEAK:$leak] "; leaks=$((leaks+leak)); }
  [ -n "$alert" ] && alerts=$((alerts+1))

  A exec-out screencap -p > "$OUT/tick_$(printf %03d "$t").png" 2>/dev/null

  printf '%s tick%03d %s pos=%s suppress=%s %s\n' \
    "$ts" "$t" "${state:-NONE}" "${pos:-?}" "$suppress" "${alert:+*** $alert}" | tee -a "$ST"

  # rotate to next title on schedule
  if [ "$ROTATE" = 1 ] && [ "$NTITLES" -gt 1 ] && [ $(( t % TICKS_PER_ROT )) -eq 0 ] && [ "$t" -lt "$TOTAL_TICKS" ]; then
    titleidx=$(( titleidx % NTITLES + 1 )); nexturl=$(eval "echo \${$titleidx}")
    open_title "$nexturl"; prevpos=-1; stall=0
    A logcat -c >/dev/null 2>&1
  fi

  # scheduled stress beat (skip if this tick also rotated, and not on the last tick)
  if [ "$TICKS_PER_STRESS" -gt 0 ] && [ $(( t % TICKS_PER_STRESS )) -eq 0 ] && [ "$t" -lt "$TOTAL_TICKS" ] \
     && ! { [ "$ROTATE" = 1 ] && [ "$NTITLES" -gt 1 ] && [ $(( t % TICKS_PER_ROT )) -eq 0 ]; }; then
    stress_cycle; prevpos=-1; stall=0
    A logcat -c >/dev/null 2>&1
  fi

  sleep "$TICK_SECS"
done

# ── summary ──────────────────────────────────────────────────────────────────
lg "=== DONE: $TOTAL_TICKS ticks, alerts=$alerts, ad-leaks=$leaks ==="
if [ "$alerts" -eq 0 ]; then
  lg "RESULT: HEALTHY — continuous playback, no stalls/errors, no ad leaks. Screenshots: $OUT"
else
  lg "RESULT: REVIEW — $alerts alert tick(s). Grep '\\*\\*\\*' in $ST and inspect the matching tick_*.png"
fi
