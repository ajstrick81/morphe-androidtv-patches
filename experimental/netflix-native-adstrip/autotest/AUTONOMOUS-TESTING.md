# Autonomous patch testing (drive the app + monitor logs) — workflow capability

A reusable capability for this repo: the agent can drive a patched app over `adb`
(launch, resume a title, seek, pause/resume) **and** read the data oracle at the
same time — so ad-suppression + playback + resume can be validated without a human
holding the remote. First proven on the Netflix clone (`nf-autotest.sh`).

## What it validates (Netflix example)
- Patches self-applied at launch (`KILL … apply DONE A/B/FP`).
- Playback continuity (media-session `position` advancing at ~1x, no stalls).
- Resume/pause correctness (position preserved across pause→resume).
- Ad suppression under stress (seek to mid-rolls, pause for pause-ads) via the
  in-app oracle: `KILLMARK≥2` (real manifest ad break emptied), `rawDisplayAd>0`
  (server pause-ad suppressed), `rawRealPods>0` (raw pre-roll pod present).
- No `tvq-pb` / crashes / ANRs.

## HARD RULES (learned the hard way — do not skip)
Blind, open-ended navigation on a logged-in account is DANGEROUS. In an early run
a free-roaming driver (DOWN×N / RIGHT×N + CENTER) wandered into an Add-Profile /
sign-up flow and launched a *different* app (Apple TV), because once the target
app got backgrounded, the keypresses landed on the launcher. Rules that prevent
this:

1. **No open-ended directional navigation.** Only `CENTER`-to-resume
   (Continue-Watching) + in-player keys (`MEDIA_FAST_FORWARD`, `MEDIA_PLAY_PAUSE`,
   `BACK`). Never sweep rows/tiles blindly — the screen is a secure surface
   (no screenshots, no accessibility tree), so you cannot see what you select.
2. **Foreground-guard before every input.** Check the top activity; if the target
   package is not foreground, ABORT immediately — never send keys blind.
3. **Verify before stressing.** After the resume, confirm the media session is
   actually `PLAYING` with `position>0`. If not, `BACK` once and stop — do not
   keep poking.
4. **Bounded + clean-stop.** Fixed cycle count, then `BACK` to home. Re-check
   foreground at the end.
5. **Read-only oracle.** Ad/error/position monitoring is pure logcat + dumpsys —
   zero input. Keep the driving minimal and the observing rich.

## Run it
```bash
bash experimental/netflix-native-adstrip/autotest/nf-autotest.sh [cycles]   # default 4
```
Prereqs: the clone (`com.netflix.ninja.clone`) installed + logged in, stock
Netflix enabled (clone reads its signature), device on `adb`. The bundled gadget
script self-logs the `KILL/OBS` oracle, so no frida attach is needed.

## Generalizing to other apps
The pattern is app-agnostic; only the specifics change:
- `PKG`/`ACT`, the "resume a known-good item" action, and the oracle signal.
- Playback position: `dumpsys media_session` works for any app that publishes a
  MediaSession with a real `position` (Netflix does; verify per app).
- Ad/behavior oracle: reuse the app's in-process monitor (like `killads.js`'s
  OBS line) or a read-only logcat signal. If an app HAS a screen you can read
  (standard Android views / accessibility tree), navigation can be less blind —
  but keep the foreground-guard and verify-before-act rules regardless.

Keep the same hard rules. The value is "drive minimally, observe richly, never
send input you can't verify the target for."
