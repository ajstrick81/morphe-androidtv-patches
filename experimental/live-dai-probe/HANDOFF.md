# Handoff — Paramount+ live-sports slate: DAI probe & duration fix

> **Paste this whole file as the first message in a new session.** It carries the
> full context needed to run the probe, read the capture, and finish the fix.
> Repo: `ajstrick81/morphe-androidtv-patches`. Work branch:
> `claude/paramount-patch-install-guide-65v2vk`.

## TL;DR of the task

The Paramount+ patch replaces live-sports commercial breaks with Paramount's
"Commercial in Progress" slate. On some events (confirmed on a Frosinone vs
Juventus match; re-testable on a Torino vs Milan **replay** that also shows the
slate) the slate **does not play smoothly**. We built a diagnostic probe to
capture the real DAI ad-pod structure so we can replace a hardcoded duration
constant with a per-event derived value. **We need a capture from the user's
Android TV, then we finish the code change.**

## Why it (probably) glitches — hypothesis from the code

The slate rewrite lives in `patches/src/main/kotlin/app/morphe/patches/paramount/ParamountPatch.kt`,
"Patch 3" (`AviaNetworkInterceptorFingerprint`, ~lines 127–175). Mechanism:

- Live ads are Google DAI "pod serving": segment URLs shaped like
  `.../linear/pods/v1/.../<pod>/<slot>/<adIdx>/<hash>/<N>.ts|.aac`.
- The patch regex `/([0-9]+)/([0-9]+)/([0-9]+)/([0-9a-fA-F]{32})/` → `/$1/slate/0/$4/`
  rewrites each ad segment to the **slate** rendition, **index-for-index** (same `N`),
  **preserving the ad's hash** (`$4`).
- **The smoking gun:** for any rewritten segment with no query, it appends a
  **hardcoded `?d=4972`** (~4.972 s). That number was tuned on one event.

Two per-event-fragile assumptions fall out of this:

1. **Duration / count:** hardcoding `d=4972` assumes every segment is 4.972 s and
   that slate segment count × that duration fills the break. If this event's real
   segment duration or break length differs, the timeline drifts → stutter that
   tends to get worse the longer the break runs.
2. **Hash keying:** the rewrite keeps the **ad's** hash in the **slate** URL. If
   this event keys the slate under a different hash, some `slate/0/<adHash>/N`
   requests 404 → missing segments → holes/stutter.

The capture tells us which dominates.

## The probe tooling (already committed on the work branch)

- `patches/src/main/kotlin/app/morphe/patches/paramount/DaiProbePatch.kt`
  — opt-in, **default OFF** bytecode patch named **"Paramount+ DAI Probe (diagnostic)"**.
  Log-only pass at `AviaNetworkInterceptor.intercept()`: logs every
  `/linear/pods/v1/` request URL to logcat under tag **`MorpheDaiProbe`**,
  **playback unchanged (no rewrite)**. Run it EXCLUSIVELY so real ad pods play and
  their true structure is captured.
- `testing/scripts/capture-dai.sh` — tails+saves the probe output to
  `testing/out/dai-probe-<timestamp>.log` while a break plays.
- `testing/scripts/parse-dai-probe.py` — turns a capture into a per-slot table
  (segment counts, hashes, per-segment `?d=` durations) + a summary that states the
  ad-vs-slate mismatch directly.

## Run steps (USER runs these locally — needs adb to reach the TV)

A cloud/remote session CANNOT run these: no adb, no route to the TV. The user
must run them on the machine on the same LAN as the Android TV.

```bash
cd testing
# one-time:
./scripts/setup.sh
cp config/device.env.example config/device.env   # set DEVICE_IP to the TV
# build + install the probe (slate rewrite OFF; real ads will show — expected):
./scripts/build.sh
./scripts/patch.sh paramount apks/paramount-16.17.0.apkm \
    --exclusive -e "Paramount+ DAI Probe (diagnostic)"
./scripts/deploy.sh paramount
# capture:
./scripts/capture-dai.sh          # scrub to the break, let it fully play, Ctrl-C
# parse:
./scripts/parse-dai-probe.py out/dai-probe-*.log
```

Gotcha: if `build.sh` fails on the Morphe registry needing a token, set
`GITHUB_ACTOR`/`GITHUB_TOKEN` or `gpr.user`/`gpr.key` (see `testing/README.md`).

## What to hand back to the session

Paste the **parser output** (or attach the raw `testing/out/dai-probe-*.log`).
The decision hinges on the summary line **`per-segment ?d= in URL`**:

- **YES** → durations are in the URL. Fix Patch 3 to read each segment's real
  `?d=` and pass it through, instead of appending `?d=4972`. This is the intended
  change and can be drafted immediately against the real numbers.
- **NO** → durations live in the playlist body. Next step is a follow-up
  body-logging probe (log the ad **playlist** response, read `#EXTINF`/`SegmentTemplate`
  durations), then derive from that.

Also read from the table:
- `ad slots` and `total ad segments` vs `slate segments` — is the break longer /
  more slots than the slate covers?
- `distinct hashes` — do ad and slate **share** a hash, or does the slate use a
  different one (then the preserved-`$4`-hash rewrite is wrong for this event)?

## The intended code change (draft once numbers are in)

In `ParamountPatch.kt` Patch 3, the block at ~lines 149–160 that appends
`?d=4972` should instead **carry the ad segment's own duration onto the slate
URL**. Concretely: before rewriting, extract the original request URL's `d=`
query value (if present) and re-attach that value to the rewritten slate URL,
rather than a constant. If the original has no `d=`, fall back to deriving it
(e.g. from the playlist) rather than assuming 4972. Keep the existing
"don't double-append when a query already exists" guard. Validate on-device that
the slate now tracks the break length without drift.

## Key references

- Patch + mechanism writeup: `patches/.../paramount/ParamountPatch.kt` (Patch 3, ~L87–175)
- Fingerprints: `patches/.../paramount/Fingerprints.kt` (`AviaNetworkInterceptorFingerprint`)
- Slate patch shipped in release **v1.27.0** (commit `40b28ec`, PR #114) — NOT a
  v1.29.1 change (that was Twitch); the release notes not mentioning it is expected.
- Tracking issue / user thread: **#91** (reporter: `pixel8323`, watching soccer breaks daily).
- Project ethos (CLAUDE.md): dig one level deeper — "walls are usually not walls."
