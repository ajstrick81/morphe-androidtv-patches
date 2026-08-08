#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Netflix clone — EXTENDED deterministic test matrix (NO blind navigation).
#
# Exercises many titles (new/old movies + shows) and scenarios — from-beginning,
# end-credits, start-over, pause/resume, and Continue-Watching resume — by
# DEEP-LINKING directly to specific title IDs (nflx/https VIEW intent), never by
# guessing at unseen UI. Foreground-guard before every input. Captures the in-app
# KILL/OBS ad-oracle + media-session position (continuity) + logcat errors.
#
# Titles are Netflix ORIGINALS (broadly available); if one isn't playable for this
# account/region, playback simply never starts and the harness logs it + moves on.
#
# Usage: bash nf-matrix.sh
# ─────────────────────────────────────────────────────────────────────────────
PKG=com.netflix.ninja.clone
ACT=$PKG/com.netflix.ninja.MainActivity

# id|label|kind  (kind is informational only)
TITLES=(
  "81231974|Wednesday|show-new"
  "80057281|Stranger Things|show-old"
  "81161626|Red Notice|movie-new"
  "80175798|The Irishman|movie-old"
  "80240715|Roma|movie-old"
)

K(){ adb shell input keyevent "$1" >/dev/null 2>&1; }
FG(){ adb shell dumpsys activity activities 2>/dev/null | grep -oE 'topResumedActivity=ActivityRecord\{[a-f0-9]+ u0 [^ }]+' | grep -oE '[^ ]+$' | head -1; }
POS(){ adb shell dumpsys media_session 2>/dev/null | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' | grep -vE 'position=0$' | head -1; }
lg(){ echo "$(date +%H:%M:%S) $*"; }
guard(){ case "$(FG)" in *"$PKG"*) return 0;; *) lg "  ABORT: clone not foreground (fg=$(FG))"; return 1;; esac; }

# Wait up to ~45s for 'apply DONE' (patches armed) after a launch.
wait_armed(){ local n=0; while [ $n -lt 15 ]; do adb logcat -d 2>/dev/null | grep -q 'KILL.*apply DONE' && return 0; sleep 3; n=$((n+1)); done; return 1; }

deeplink(){ adb shell am start -n "$ACT" -a android.intent.action.VIEW -d "$1" >/dev/null 2>&1; }

oracle(){ adb logcat -d 2>/dev/null | grep -oE 'OBS[0-9]+: KILLMARK=[0-9]+ rawRealPods=[0-9]+ rawDisplayAd=[0-9]+.*' | grep -vE 'KILLMARK=[01] rawRealPods=0 rawDisplayAd=0' | tail -6; }
errs(){ adb logcat -d 2>/dev/null | grep -icE 'tvq-pb|FATAL|has died.*clone|SIGABRT|ANR in com.netflix.ninja.clone'; }

lg "################ EXTENDED MATRIX START ################"
for entry in "${TITLES[@]}"; do
  id="${entry%%|*}"; rest="${entry#*|}"; label="${rest%%|*}"; kind="${rest##*|}"
  lg ""
  lg "======== [$label] ($kind) id=$id ========"
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1

  # ---- FROM BEGINNING: deep-link fresh launch ----
  lg "  launch (deep-link, from beginning)"
  deeplink "nflx://www.netflix.com/watch/$id"; sleep 6
  if ! guard; then deeplink "https://www.netflix.com/watch/$id"; sleep 6; fi
  wait_armed && lg "  patches armed: YES" || lg "  patches armed: NO (continuing, will still record)"

  # wait for playback
  S=""; n=0; while [ $n -lt 10 ] && [ -z "$S" ]; do S=$(POS); [ -z "$S" ] && sleep 3; n=$((n+1)); done
  if [ -z "$S" ]; then lg "  NO PLAYBACK (unavailable for account/region?) — skipping title"; K KEYCODE_BACK; sleep 2; continue; fi
  lg "  FROM-BEGINNING playing: $S"

  # ---- END CREDITS: big forward seek toward end ----
  guard || continue
  lg "  seek toward END CREDITS (FF x18)"
  for s in $(seq 1 18); do K KEYCODE_MEDIA_FAST_FORWARD; sleep 0.6; done
  K KEYCODE_DPAD_CENTER; sleep 8; lg "  near-end pos: $(POS)  (watching for post-credit auto-advance / errors)"
  sleep 6; lg "  post-credit pos: $(POS)  fg=$(FG)"

  # ---- PAUSE (pause-ad window) at end region ----
  guard && { lg "  pause 7s + resume"; K KEYCODE_MEDIA_PLAY_PAUSE; sleep 7; lg "  paused: $(POS)"; K KEYCODE_MEDIA_PLAY_PAUSE; sleep 5; lg "  resumed: $(POS)"; }

  # ---- START OVER: rewind to the beginning ----
  guard && { lg "  START OVER (RW x18)"; for s in $(seq 1 18); do K KEYCODE_MEDIA_REWIND; sleep 0.6; done; K KEYCODE_DPAD_CENTER; sleep 6; lg "  after start-over pos: $(POS)"; }

  lg "  errors so far: $(errs)"; oracle | sed 's/^/    ORACLE: /'
  guard && { K KEYCODE_BACK; sleep 2; K KEYCODE_BACK; sleep 2; }

  # ---- RESUME: relaunch same title, expect bookmark > 0 ----
  lg "  RESUME test: relaunch same title"
  adb shell am force-stop "$PKG" >/dev/null 2>&1; sleep 2
  deeplink "nflx://www.netflix.com/watch/$id"; sleep 6
  R=""; n=0; while [ $n -lt 8 ] && [ -z "$R" ]; do R=$(POS); [ -z "$R" ] && sleep 3; n=$((n+1)); done
  lg "  RESUME pos: ${R:-<none>}  (>0 position = bookmark honored)"
  guard && { K KEYCODE_BACK; sleep 2; K KEYCODE_BACK; sleep 2; }
done

# ---- Continue-Watching CENTER resume (row-0 tile) as a final continuity check ----
lg ""
lg "======== Continue-Watching resume (CENTER path) ========"
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_armed && lg "  patches armed: YES" || lg "  patches armed: NO"
guard && { K KEYCODE_DPAD_CENTER; sleep 4; K KEYCODE_DPAD_CENTER; sleep 12; lg "  CW resume pos: $(POS)"; K KEYCODE_BACK; sleep 2; K KEYCODE_BACK; sleep 2; }

lg ""
lg "################ MATRIX REPORT ################"
lg "non-baseline ad oracle across whole run:"
adb logcat -d 2>/dev/null | grep -oE 'OBS[0-9]+: KILLMARK=[0-9]+ rawRealPods=[0-9]+ rawDisplayAd=[0-9]+.*' | grep -vE 'KILLMARK=[01] rawRealPods=0 rawDisplayAd=0' | tail -20 | sed 's/^/  /' || echo "  (none)"
lg "GAID/FP/patch arming lines:"
adb logcat -d 2>/dev/null | grep -oE 'KILL *: (PATCH (GAID|FP|A|B)|apply DONE).*' | tail -6 | sed 's/^/  /'
lg "error lines:"
adb logcat -d 2>/dev/null | grep -iE 'tvq-pb|FATAL|has died.*clone|SIGABRT|ANR in com.netflix.ninja.clone' | tail -6 | sed 's/^/  /' | grep . || echo "  none"
lg "final fg: $(FG)"
lg "################ DONE ################"
