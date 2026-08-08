#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Netflix clone — GUARDRAILED autonomous ad/playback test (no blind navigation).
#
# Drives ONLY safe, verified actions: launch -> confirm patches armed -> a single
# controlled resume of Continue-Watching -> IN-PLAYER stress (seek to mid-rolls,
# pause/resume) -> clean stop. Monitors media-session position (continuity) and
# the in-app KILL/OBS oracle (ad suppression) + logcat for errors.
#
# HARD RULES (learned the hard way — see AUTONOMOUS-TESTING.md):
#  * NEVER do open-ended directional navigation (DOWN/RIGHT wandering hits
#    profile/account/other-app tiles). Only CENTER-to-resume + in-player keys.
#  * FOREGROUND-GUARD before every input: if the clone is not foreground, ABORT
#    (so stray keys can't land on the launcher / another app).
#  * VERIFY playback actually started before stressing; back out safely if not.
#
# Usage: bash nf-autotest.sh [cycles]     (default 4 in-player stress cycles)
# ─────────────────────────────────────────────────────────────────────────────
PKG=com.netflix.ninja.clone
ACT=$PKG/com.netflix.ninja.MainActivity
CYCLES=${1:-4}

K(){ adb shell input keyevent "$1" >/dev/null 2>&1; }
FG(){ adb shell dumpsys activity activities 2>/dev/null | grep -oE 'topResumedActivity=ActivityRecord\{[a-f0-9]+ u0 [^ }]+' | grep -oE '[^ ]+$' | head -1; }
POS(){ adb shell dumpsys media_session 2>/dev/null | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' | grep -vE 'position=0$' | head -1; }
lg(){ echo "$(date +%H:%M:%S) $*"; }
guard(){ case "$(FG)" in *"$PKG"*) return 0;; *) lg "ABORT: clone not foreground (fg=$(FG)) — refusing to send input"; return 1;; esac; }

lg "=== launch + confirm patches armed ==="
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 22
armed=$(adb logcat -d 2>/dev/null | grep -cE 'KILL.*apply DONE')
lg "patches armed: $([ "$armed" -gt 0 ] && echo YES || echo 'NO — aborting')"
[ "$armed" -gt 0 ] || exit 1

guard || exit 1
lg "=== controlled resume (CENTER x2 on Continue-Watching) ==="
K KEYCODE_DPAD_CENTER; sleep 4; K KEYCODE_DPAD_CENTER; sleep 12
S=$(POS)
if [ -z "$S" ]; then lg "no playback started — backing out, exiting"; K KEYCODE_BACK; sleep 2; exit 0; fi
lg "playing: $S"

for c in $(seq 1 "$CYCLES"); do
  guard || break
  lg "--- cycle $c: seek to mid-rolls (FF x6) ---"
  for s in 1 2 3 4 5 6; do K KEYCODE_MEDIA_FAST_FORWARD; sleep 1; done
  K KEYCODE_DPAD_CENTER; sleep 6; lg "cycle $c seeked: $(POS)"
  guard || break
  lg "--- cycle $c: pause (pause-ad window) + resume ---"
  K KEYCODE_MEDIA_PLAY_PAUSE; sleep 7; lg "cycle $c paused: $(POS)"
  K KEYCODE_MEDIA_PLAY_PAUSE; sleep 8; lg "cycle $c resumed: $(POS)"
  errs=$(adb logcat -d 2>/dev/null | grep -icE 'tvq-pb|FATAL|has died.*clone|SIGABRT')
  [ "$errs" -gt 0 ] && lg "cycle $c WARNING: $errs error line(s) in logcat"
done

guard && { lg "=== clean stop (back to home) ==="; K KEYCODE_BACK; sleep 2; K KEYCODE_BACK; sleep 2; }

lg "=== REPORT ==="
lg "ad deliveries + kills (non-baseline OBS):"
adb logcat -d 2>/dev/null | grep -oE 'OBS[0-9]+: KILLMARK=[0-9]+ rawRealPods=[0-9]+ rawDisplayAd=[0-9]+.*' | grep -vE 'KILLMARK=[01] rawRealPods=0 rawDisplayAd=0' | tail -12
lg "errors:"; adb logcat -d 2>/dev/null | grep -iE 'tvq-pb|FATAL|has died.*clone|SIGABRT' | tail -4 | grep . || echo "  none"
lg "final fg: $(FG)"
lg "=== DONE ==="
