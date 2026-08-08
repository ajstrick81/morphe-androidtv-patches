# Autonomous app testing — brute-force validate a patched Android TV app

A reusable way to **drive a patched streaming app over `adb` and read a data
oracle at the same time**, so ad-suppression, playback, and resume can be
validated under stress without a human on the remote. First proven on the
Netflix clone; generalized here into a profile-driven runner you can point at
Prime Video and other apps.

> **The one-line philosophy:** *drive minimally, observe richly, and never send
> input you can't verify the target for.* The TV screen is a secure surface you
> often cannot screenshot or read via the accessibility tree — so the danger is
> not the app, it's your own blind keypresses.

---

## Files

```
experimental/autotest/
  app-autotest.sh          # the generic engine (guardrailed)
  profiles/
    netflix.env            # Netflix clone (self-stamping KILL/OBS oracle)
    primevideo.env         # Prime Video (continuity + error oracle, no self-stamp)
    _template.env          # copy this to add a new app
```

Run:

```bash
adb connect <device-ip>:5555
bash experimental/autotest/app-autotest.sh <profile> [cycles]
# e.g.
bash experimental/autotest/app-autotest.sh netflix 5
bash experimental/autotest/app-autotest.sh primevideo 4
```

---

## What it does each run

1. **Launch** the patched app (from the launcher, or via a deep link if the
   profile sets one) and clear logcat.
2. **Confirm armed** — if the profile has an `ARMED_RE`, wait up to ~45s for the
   patch's self-applied log line (Netflix: `apply DONE`). No signal → abort.
3. **Controlled resume** — press only the profile's `RESUME_KEYS` (default: two
   `DPAD_CENTER`s to resume the focused Continue-Watching tile).
4. **Verify playback** — read `dumpsys media_session` for a real
   `state=PLAYING, position>0`. If nothing is playing, back out cleanly and stop.
5. **In-player stress** — for N cycles: fast-forward toward mid-rolls, then
   pause (pause-ad window) and resume, checking position continuity each step.
6. **Clean stop** — `BACK` to home, re-check foreground.
7. **Report** — non-baseline oracle lines + any error/crash/ANR lines.

Every input is preceded by a **foreground-guard**: if the target package isn't
the top activity, the run aborts rather than send a keypress blind.

---

## HARD RULES (learned the hard way — do not skip)

In an early Netflix run a free-roaming driver (`DPAD_DOWN×N` / `RIGHT×N` +
`CENTER`) wandered into an Add-Profile / sign-up flow and even launched a
*different* app (Apple TV) — because once the target got backgrounded, the
keypresses landed on the launcher. These rules exist to make that impossible:

1. **No open-ended directional navigation.** Only `RESUME_KEYS` (a fixed, known
   entry action) + in-player media keys (`MEDIA_FAST_FORWARD`,
   `MEDIA_PLAY_PAUSE`, `BACK`). Never sweep rows/tiles blindly.
2. **Foreground-guard before every input.** If the target package is not the top
   activity, ABORT. The engine's `guard()` enforces this.
3. **Verify before stressing.** Confirm `media_session` is actually `PLAYING`
   with `position>0` before any seek/pause. If not, `BACK` once and stop.
4. **Bounded + clean-stop.** Fixed cycle count, then `BACK` to home; re-check
   foreground at the end.
5. **Read-only oracle.** All ad/error/position monitoring is pure
   `logcat` + `dumpsys` — zero input. Keep the driving minimal, the observing rich.

---

## Writing a profile for a new app

Copy `profiles/_template.env` to `profiles/<app>.env` and set the fields. Only
`PKG` is required. The interesting decisions:

### 1. How do you enter known-good playback safely?
- **Best:** a Continue-Watching tile focused at launch → `RESUME_KEYS="DPAD_CENTER DPAD_CENTER"`.
- **Deep link:** set `LAUNCH` to a `VIEW` URL for a specific title. Deterministic
  and blind-nav-free — **but** many apps (Netflix included) open a *details page*
  and don't auto-play, so you may still need one player keypress. Test the deep
  link once by hand and see whether `media_session` reaches `PLAYING`.
- **Never** script row/tile navigation you can't see.

### 2. What's the oracle?
This is the crux, and apps differ:

| Oracle type | Example | How to use |
|---|---|---|
| **Self-stamping** (best) | Netflix `killads.js` logs `OBS…: KILLMARK=…` | Set `ORACLE_RE` + `ORACLE_BASELINE_RE`. `KILLMARK≥2` = a real server ad break was emptied. |
| **Behavioral** | playback stayed continuous, position advanced ~1x, no `tvq-pb` | Leave `ORACLE_RE` empty; rely on continuity + `ERROR_RE`. |
| **Network** | ad-host requests absent (PCAPdroid / `read_network`) | Out of band; capture separately and diff. |

> **Beware the broken oracle.** On Netflix, "did an ad play?" *lies* — the server
> empty-fills ~2/3 of ad breaks even unpatched. That's why the self-stamp exists:
> it fires only when a *populated* break is emptied by the patch. When you add a
> new app, ask "could this signal be true even if my patch did nothing?" before
> trusting it. If yes, find a signal the patch itself emits.

### 3. What counts as breakage?
Set `ERROR_RE` to the app's crash/ANR/playback-error signatures (ExoPlayer apps:
add `ExoPlaybackException`; Netflix: `tvq-pb`). A clean run has **zero** matches.

---

## Prime Video specifics (for your next run)

- Package `com.amazon.amazonvideo.livingroom`; it's an **in-place** patch (native
  `libpvhook.so`), **not** a clone — nothing to keep side-by-side.
- No launch-time "armed" log and no self-stamping ad oracle, so `primevideo.env`
  validates **continuity + no errors** under seek/pause stress. That catches the
  real risks (black screens, `ExoPlaybackException`, resume-position drift — see
  the known aggressive-seek edge case in the README).
- If you want a positive ad-kill signal, instrument `libpvhook` to log a marker
  and set `ORACLE_RE` to it — same pattern as Netflix's `KILLMARK`.
- Confirm your device's home row 0 is a resumable title before trusting
  `RESUME_KEYS`; if not, switch `primevideo.env` to a deep link.

---

## Interpreting a report

- **Netflix good run:** `apply DONE …`, sustained `KILLMARK=2` with
  `rawDisplayAd>0` (server sent pause-ads, all suppressed), position advancing
  across pause→resume, `errors: none`.
- **Any app bad run:** playback never reached `PLAYING` (resume keys wrong or a
  black screen), position stuck/rewinding unexpectedly, or non-empty `errors:`.

Keep runs short and repeatable; the value is in doing many cheap, bounded passes
after every rebuild — not one long unguarded session.
