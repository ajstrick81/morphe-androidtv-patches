#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Twitch ATV (Starshot) — adb ad-delivery probe harness.
#
# The Twitch analog of nf-autotest.sh. Same shape: launch -> foreground-guard ->
# controlled playback -> sample logcat oracles -> report. Adapted for two facts
# that make Twitch different from the Netflix clone:
#
#  1. Twitch ATV is a WebVIEW app, not a native player. Its "ad oracle" is logcat,
#     from two sources:
#       * MORPHE-TW-ATV-WV  — the SHIPPING scrubber (TwitchAtvWebViewHelper). Emits
#                             "blanked stitched-ad segments" whenever it neutralizes
#                             an SSAI/SureStream ad in the weaver playlist. Needs no
#                             extra wiring — present in any patched build.
#       * MORPHE-PROBE      — the fetch/XHR shim (fetch-xhr-probe.js / TwitchAtvUrlProbe).
#                             Emits [AD-DECISION]/[AD-VOD]/[AD-3P]/[PLAYLIST]/[TRACKING]
#                             per request. Only present if you wired the probe in for
#                             this session (see README). This is what reveals a
#                             CLIENT-SIDE ad path the scrubber can't see.
#
#  2. Live streams have NON-DETERMINISTIC ad breaks — you cannot seek to a mid-roll
#     like a Netflix VOD. So this harness does not "force" an ad; it holds on a live
#     channel for a long window and samples which ad buckets ever appear. Run it on a
#     channel you expect to serve ads (not a Turbo/sub-only/ad-free stream).
#
# What it answers (the open question from docs/twitch-ad-delivery-map.md):
#   During playback, do we ever see MORPHE-PROBE [AD-DECISION] / [AD-VOD]  (a
#   client-side edge.ads.twitch.tv fetch the scrubber misses), or only [PLAYLIST]
#   plus MORPHE-TW-ATV-WV "blanked" lines (pure SSAI, fully covered)?
#
# Usage:  bash tw-adb-probe.sh <channel> [watch_minutes]
#         bash tw-adb-probe.sh xqc 8
# Requires: adb connected to the ATV device; a Morphe-patched Twitch ATV installed.
# ─────────────────────────────────────────────────────────────────────────────
set -u

PKG=tv.twitch.android.app
CHANNEL="${1:-}"
WATCH_MIN="${2:-8}"
[ -n "$CHANNEL" ] || { echo "usage: bash tw-adb-probe.sh <channel> [watch_minutes]"; exit 2; }

# ---- helpers (mirror nf-autotest.sh idioms) --------------------------------
K(){ adb shell input keyevent "$1" >/dev/null 2>&1; }
FG(){ adb shell dumpsys activity activities 2>/dev/null \
      | grep -oE 'topResumedActivity=ActivityRecord\{[a-f0-9]+ u0 [^ }]+' \
      | grep -oE '[^ ]+$' | head -1; }
POS(){ adb shell dumpsys media_session 2>/dev/null \
       | grep -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' \
       | grep -vE 'position=0$' | head -1; }
lg(){ echo "$(date +%H:%M:%S) $*"; }
guard(){ case "$(FG)" in *"$PKG"*) return 0;; *) lg "ABORT: Twitch not foreground (fg=$(FG)) — refusing input"; return 1;; esac; }

# ---- oracles (logcat, read since the -c clear at launch) -------------------
ssai_hits(){    adb logcat -d 2>/dev/null | grep -cE 'MORPHE-TW-ATV-WV.*blanked'; }
scrubber_up(){  adb logcat -d 2>/dev/null | grep -cE 'MORPHE-TW-ATV-WV.*scrubber active'; }
probe_up(){     adb logcat -d 2>/dev/null | grep -cE 'MORPHE-PROBE installed'; }
clientad_hits(){ adb logcat -d 2>/dev/null | grep -cE 'MORPHE-PROBE \[AD-(DECISION|VOD|3P)\]'; }
playlist_hits(){ adb logcat -d 2>/dev/null | grep -cE 'MORPHE-PROBE \[PLAYLIST\]'; }
errs(){ adb logcat -d 2>/dev/null | grep -icE 'FATAL|SIGABRT|ANR in '"$PKG"'|Oh bummer'; }

# ---- launch ----------------------------------------------------------------
lg "=== launch $PKG on channel '$CHANNEL' ==="
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
# Prefer the deep link (manifest handles twitch.tv VIEW intents); fall back to the
# launcher if the device doesn't route it.
adb shell am start -a android.intent.action.VIEW -d "https://www.twitch.tv/$CHANNEL" "$PKG" >/dev/null 2>&1
sleep 18
case "$(FG)" in
  *"$PKG"*) : ;;
  *) lg "deep link didn't foreground Twitch — trying launcher"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 18;;
esac
guard || { lg "could not bring Twitch to foreground — aborting"; exit 1; }

# Confirm the scrubber armed (shipping build). The probe shim is optional.
sleep 4
lg "scrubber active in logcat: $([ "$(scrubber_up)" -gt 0 ] && echo YES || echo 'no (is this a patched build?)')"
lg "fetch/XHR probe present:   $([ "$(probe_up)"    -gt 0 ] && echo YES || echo 'no (client-side ad detection OFF — wire the shim to enable)')"

# Nudge playback in case the channel page needs a CENTER to start.
guard && { K KEYCODE_DPAD_CENTER; sleep 6; }
S="$(POS)"; lg "playback position: ${S:-<none yet>}"

# ---- watch window (sample every 20s) ---------------------------------------
SAMPLES=$(( WATCH_MIN * 3 ))
lg "=== watching for ${WATCH_MIN} min (${SAMPLES} samples) — ad breaks are non-deterministic on live ==="
last_ssai=0; last_client=0
for i in $(seq 1 "$SAMPLES"); do
  guard || { lg "left foreground — stopping watch"; break; }
  sleep 20
  sh=$(ssai_hits); ch=$(clientad_hits); ph=$(playlist_hits); po="$(POS)"
  # Announce transitions loudly so a break is obvious in the run log.
  [ "$sh"  -gt "$last_ssai" ]   && lg "  >>> SSAI ad neutralized (scrubber) — total blanked=$sh"
  [ "$ch"  -gt "$last_client" ] && lg "  !!! CLIENT-SIDE ad request seen (AD-DECISION/VOD/3P) — total=$ch  <== path the scrubber MISSES"
  last_ssai=$sh; last_client=$ch
  lg "  sample $i/$SAMPLES: pos=${po:-?} ssai_blanked=$sh client_ad=$ch playlist=$ph"
done

# ---- clean stop + report ---------------------------------------------------
guard && { lg "=== stop ==="; K KEYCODE_BACK; sleep 2; K KEYCODE_BACK; sleep 2; }

lg "=== REPORT ==="
SH=$(ssai_hits); CH=$(clientad_hits); PH=$(playlist_hits); ER=$(errs)
lg "SSAI ads neutralized by scrubber (MORPHE-TW-ATV-WV blanked): $SH"
lg "client-side ad requests seen (MORPHE-PROBE AD-*):            $CH"
lg "playlist fetches observed (MORPHE-PROBE PLAYLIST):           $PH"
lg "logcat error lines (FATAL/SIGABRT/ANR/'Oh bummer'):          $ER"
echo
if [ "$(probe_up)" -eq 0 ]; then
  lg "VERDICT: inconclusive on client-side ads — the fetch/XHR shim was not present."
  lg "         Wire TwitchAtvUrlProbe.inject(view) (see README) and re-run to detect them."
elif [ "$CH" -gt 0 ]; then
  lg "VERDICT: CLIENT-SIDE ad path CONFIRMED. Some ads bypass the playlist scrubber."
  lg "         Capture the full URL(s):  adb logcat -d | grep 'MORPHE-PROBE \\[AD'"
  lg "         Next: evaluate signal-level seams in docs/twitch-ad-delivery-map.md."
else
  lg "VERDICT: no client-side ad requests observed in ${WATCH_MIN} min."
  lg "         Consistent with pure SSAI (scrubber covers the surface). Not proof of"
  lg "         absence — re-run longer / on a heavier-ad channel to raise confidence."
fi
lg "=== DONE ==="
