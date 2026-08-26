# Health Monitor — app-independent stream babysitter (Android TV)

Plays a patched streaming app for a long stretch and continuously verifies the
stream stays **healthy** and **ad-free** — not just that a patch "Applied". It is
the long-haul companion to [`../autotest/app-autotest.sh`](../autotest/app-autotest.sh)
(which does a short seek/pause stress run).

Born from the HBO Max **#125** session (shipped in **v1.29.3**): a patched build
can look clean on the first title and still leak ads on *some* content, so we
babysat ~36 min across several movies to be sure. This generalizes that method to
any app via a small profile file.

## What each tick records (every `TICK_SECS`, default 30s)

- **Playback state + position** from `dumpsys media_session` — detects a **stall**
  (position frozen 3 ticks while PLAYING) or a bad state / player error.
- **Crash / ANR / playback faults** via `ERROR_RE` (`FATAL`, `ANR`, `SIGABRT`,
  `ExoPlaybackException`, …).
- **Ad-defense activity** via `SUPPRESS_RE` — a *healthy* signal (guard fired, ad
  request emptied); counted, not alerted.
- **Ad leaks** via `AD_LEAK_RE` — a signal that must **never** appear; a match is a
  hard alert.
- **A screenshot** (`tick_NNN.png`) — the only ground truth for a *visual* ad that
  produces no log line. Always review a few per run.

Everything lands in `runs/<profile>-<timestamp>/` (`status.log` + screenshots).
A run ends with `RESULT: HEALTHY` or `RESULT: REVIEW` (with the alert tick count).

## Title rotation

Content-specific ad leaks only show on *some* titles, so one run can **rotate**
through several. Rotation is driven **only by profile deep links** (`TITLES`),
switching every `ROTATE_MIN` minutes — never by blind D-pad navigation, because
the TV is a surface the agent cannot see (same hard rule as app-autotest). If a
profile has no `TITLES`, it runs in single-title mode; use `LAUNCH=attach` to
babysit content you started by hand.

## Stress beat (resume / seek)

Linear playback is the easy case for ad-blocking. Ad re-insertion tends to slip
through at **transitions** — a resume after BACK can trigger a fresh
manifest/ad-decision, and a fast-forward can land you *inside* an ad period or
force a segment re-request from the ad origin. Set `STRESS_EVERY_MIN` to run a
`play -> BACK -> resume -> seek -> play` cycle on that cadence; any ad re-inserted
then surfaces in the ticks (and screenshots) that follow. It uses media keys plus
the profile's `RESUME_KEYS` only, foreground-guarded — no blind navigation.

## Usage

```bash
# 60-minute watch using the hbomax profile
bash experimental/health-monitor/health-monitor.sh hbomax 60

# monitor whatever is already playing (no launch/rotation)
LAUNCH is set per-profile; for an ad-hoc attach, copy _template.env and set LAUNCH=attach
```

Run it backgrounded during a session and review `status.log` + a few screenshots
when it reports back; re-run for continuous coverage.

## Writing a profile

Copy [`profiles/_template.env`](profiles/_template.env) to `profiles/<app>.env` and
fill it in — only `PKG` is required. The schema is a superset of the app-autotest
profile, so existing knowledge (launch component, playback probe, error regex)
carries over. See [`profiles/hbomax.env`](profiles/hbomax.env) for a worked example.

## Safety (inherited hard rules)

- Rotation uses profile deep links only — **no open-ended directional navigation**.
- **Foreground-guard** before every input; inputs are refused if the target app
  isn't foreground.
- The oracle is **read-only** (logcat + dumpsys + screencap); the only UI input is
  the bounded `RESUME_KEYS` after an explicit launch or title switch.
- Never run `adb wm density/size` on the Onn (fatal) — this tool never does.

## Finding deep links for rotation

Deep links are app-specific. To discover them:

- `adb shell dumpsys package <PKG> | grep -iA2 'Schemes\|android.intent.action.VIEW'`
  to see the registered URL schemes / hosts.
- Open a title by hand and watch the intent: `adb logcat | grep -i 'am start\|VIEW'`.
- Test one: `adb shell am start -a android.intent.action.VIEW -d '<url>'` — a good
  deep link opens that title (some open a details page and need `RESUME_KEYS` to
  start playback, which the tool sends).
