# Live-Sports Ad-Break Slate — Reusable Playbook

Cracked on ESPN (`com.espn.score_center`, Disney DMP) 2026-09-04: a **pure-bytecode**
patch that detects live commercial breaks and covers them with a full-screen slate
(image or looping video-with-audio) + mutes the player. Verified on Onn 4K through
many real breaks. This doc generalizes the method so FoxOne, MLB.tv, and the next
live-sports app go faster.

Companion memories: `espn-isaddisabled-no-slate`, `foxone-tubi-shared-sdk`,
`mlbtv-live-dai-slate-recon`, `paramount-live-sports-ads-podserving`,
`ssai-portable-knowledge`, `health-monitor-technique`. ESPN detail:
`experimental/espn-mediax-native/SLATE_BYTECODE_PLAN.md`.

---

## 0. The core reframe (why this works where ad-removal doesn't)

Live national commercials are **server-side stitched passthrough SSAI**: the ad bytes
ARE the main stream (one CDN host, one encrypted timeline). There is no game underneath
and no ad-only segment/host to block — so DNS blocks, manifest strips, and "empty the ad
list" patches all fail or just make the ad play unlabeled.

**So don't remove the ad — MASK it.** Detect the break window, draw an opaque slate over
the video, and mute the player for the duration. Same on-screen result as a broadcast
"be right back" card, at a fraction of the risk (no CENC/segment/native surgery).

Ceiling is honest and worth stating to the user up front: **the ad still streams
underneath, hidden + silenced.** For live sports that's the real ceiling anyway.

---

## 1. Identify the ad-delivery model FIRST (decision tree)

Instrument a real break with logcat before writing any patch. Determine which bucket:

- **A. Passthrough SSAI, app-blind** (ESPN): no Kotlin ad event fires, `getBreaks()`-style
  APIs return empty, no on-screen ad countdown. Windows live ONLY in the manifest
  DateRanges (`EXT-X-DATERANGE`) delivered to an internal SDK callback. → §2 "manifest
  DateRange" path. **This is the hardest and the one this playbook unlocks.**
- **B. SSAI with a surfaced break/interstitial API**: the player exposes a break list or
  fires a break/interstitial event the app reacts to (e.g., an on-screen ad countdown).
  → hook that event/list directly; much easier.
- **C. Client-side IMA / DAI Pod-Serving** (MLB.tv, FoxOne/Tubi-class): a Google
  IMA/DAI SDK requests an ad decision and fires `onAdBreakStarted/Ended` callbacks, and/or
  a manifest rewriter can see `dclk_video_ads`/ad markers. → hook the IMA callbacks
  (primary) and/or the manifest rewriter as an independent trigger (MLB's
  `AdBreakOverlayHelper` already does this).

Quick classifier signals in logcat during a break:
- Ad markers in the manifest: `EXT-X-DATERANGE ... CLASS="...break..."`, `PASSTHROUGH_ADS`,
  `LIVE_AVAIL_DISTRIBUTOR`, SCTE-35, `#EXT-X-CUE-OUT`.
- IMA/DAI: `dclk_video_ads`, `imasdk`, `pubads`, `StreamManager`, `onAdBreakStarted`.
- Native SSAI engine chatter (Disney): `mel::break_session content_type: PassthroughAds`.

---

## 2. The six-step methodology (generalized from ESPN)

### Step 1 — Find the break-WINDOW signal (start + end, in SOME time base)
Priority order of sources, most-to-least reliable for live SSAI:
1. **Manifest DateRanges** delivered to a hookable method. For HLS these are
   `EXT-X-DATERANGE` with `START-DATE` / `END-DATE` / `PLANNED-DURATION`. Find the method
   that receives the parsed list (grep the app/SDK for `playlistRetrieved`, `DateRange`,
   `dateRanges`, `onCues`, `TimelineChanged`). ESPN: `SgaiPlaybackSession.playlistRetrieved(
   DateTime, List<DateRange>)`; each `DateRange` = `{id, map}`, map has `CLASS`,
   `START-DATE`, `PLANNED-DURATION`, `END-DATE`.
2. **A surfaced break list** (`getBreaks()`/`getPods()`), if non-empty for the stream.
   ESPN's is empty for pure passthrough — but KEEP polling it; other streams/apps populate
   it (it drives the native ad countdown when present).
3. **IMA/DAI `onAdBreakStarted/Ended`** callbacks (buckets B/C).
4. **A manifest-text rewriter** that flags a break whenever it sees ad markers in a media
   playlist (MLB pattern) — great IMA-independent fallback.

⚠️ Don't assume Kotlin break EVENTS exist. On ESPN, `BreakStartedEvent` /
`BreakContentStartedEvent` NEVER fire for passthrough. Instrument to confirm, don't guess.

### Step 2 — Get the PLAYHEAD + a common time reference
You need "where is playback now" in the SAME coordinate as the window. Options:
- Media-timeline position (e.g., a `TimelineProgressEvent.getPlayheadPosition()` in ms).
- Convert to ABSOLUTE epoch-ms when windows are wall-clock dates:
  `absPlayhead = playhead + zeroPositionProgramDateTime` (the PDT of position 0).
ESPN: window START/END are absolute ISO dates → compare against abs playhead. Watch a
startup transient where the zero-PDT reads 0 before the timeline settles.

### Step 3 — CONTAINMENT is the trigger
"Slate active" ⟺ `playhead ∈ [windowStart − tol, windowEnd + trailing]` for any known
window. This is coordinate-agnostic and works for passthrough where no event fires.
- **Accumulate** windows keyed by break ID; NEVER wholesale-clear on a refresh that omits
  a still-active break (they age out of later playlists mid-ad).
- Honor **END-DATE** when it arrives (authoritative over `PLANNED-DURATION`).
- Add a few seconds of **trailing hold** so the slate doesn't lift a beat early.
- Prune windows well behind the playhead so the map doesn't grow.

### Step 4 — OVERLAY host
Capture the player Activity (hook `onResume`/`attachSession`/lifecycle) and add a
full-screen View to `activity.findViewById(android.R.id.content)`. Build fresh per break if
media rotates. Support: user-supplied image / looping video-with-audio (from the app's
external files dir, no storage permission) / branded fallback card using the app's OWN
logo asset (`resources.getIdentifier("...logo","drawable",pkg)`) — no bundled artwork.

### Step 5 — MUTE at the PLAYER, not the system
`AudioManager.STREAM_MUSIC` muting does NOT silence the player's own audio track and is
deprecated. Find the player's volume/mute control and call it. ESPN:
`session.getPlaybackSession().getAudioControl().setMuted(true)` (synchronous). Muting the
player (not the system stream) also lets a video-overlay's OWN audio play through.

### Step 6 — Iterate on-device, diagnostic-first
Build a helper with a `DEBUG` flag that logs: each window ingest (count + first/last),
each playhead tick (throttled) with its abs value, and every show/hide transition. Watch a
real break, confirm the numbers line up, THEN tighten. Flip `DEBUG=false` to ship.

---

## 3. Reusable extension pattern

`EspnAdBreakOverlayHelper.kt` is the reference implementation — copy it per app and swap
the reflected method names. Structure:
- `registerActivity(Activity)` / `unregisterActivity` — overlay host, from lifecycle hooks.
- `setSession(Any)` — capture the player session; start a 1 s `getBreaks()` poll (Feed B1).
- `onDateRanges(List)` — manifest windows, id-keyed + accumulated (Feed B2, the passthrough
  path).
- `onPlayerEvent(Any)` — every player event; pull playhead (+ zero-PDT) from the
  timeline-progress event (Feed A).
- `evaluate()` — containment over all feeds → show/hide.
- Slate builder — media round-robin (video loops with sound) + branded fallback.
- Player-level mute via reflection.

All app-internal calls are **reflection by NAME** (avoid compiling against app modules).

---

## 4. Gotchas checklist (each cost us a build cycle on ESPN)

- [ ] **`install -r` does NOT restart a running app** — `am force-stop` then relaunch, or
      new code won't load.
- [ ] **R8 renames shared classes in the extension** (`kotlin.coroutines.Continuation` → `q`),
      breaking `getMethod(..., X::class.java)`. Look up methods by NAME; add `-keep` for any
      framework type you pass into the app across the merge boundary. Prefer a synchronous
      call chain to avoid passing a `Continuation` at all.
- [ ] **Injected-only extension classes get stripped by R8** — add a `-keep` rule listing the
      `@JvmStatic` entrypoints (see `extensions/proguard-rules.pro`).
- [ ] **Kotlin `object` + `@JvmStatic`** → call via `invoke-static L...;->m(...)V`; match the
      smali descriptor to the method's compiled signature (an `Any?` param is `Ljava/lang/Object;`).
- [ ] **Window units** — don't mix media-position and wall-clock; pick one per feed and be
      explicit. Guard the startup transient where zero-PDT is 0.
- [ ] **Wholesale-clearing windows** on each manifest refresh hides the slate mid-ad. Accumulate.
- [ ] **Mute layer** — player AudioControl, not AudioManager.
- [ ] **AppTarget must == versionName**; force-stop after install; scoped-storage: use the app's
      external files dir for user media (no permission needed).

---

## 5. Per-target starting points

### MLB.tv (`com.bamnetworks.mobile.android.gameday.atbat`) — bucket C, PARTIALLY DONE
Already has `AdBreakOverlayHelper` + `MlbManifestRewriter.signalAdBreak()` (manifest sees
`dclk_video_ads`) and IMA `onAdBreakStarted/Ended` backup — see `AtBatPatch.kt`. To reach
ESPN parity: (a) confirm the manifest-rewriter break signal fires reliably on live (field
test), (b) port the ESPN slate UX (looping video + player-level mute + timing/accumulation),
(c) if IMA callbacks are the clean edge, prefer them; else drive off the manifest signal
like ESPN drives off DateRanges. Memory: `mlbtv-live-dai-slate-recon`.

### FoxOne — bucket C (Tubi-class, Fox IMA-DAI SDK)
Shares the Fox IMA-DAI SDK with Tubi (`foxone-tubi-shared-sdk`); Tubi hooks 1/2/3/5/6 are
near drop-in, and there's a WIP auto-skip-by-seek (`foxone-autoskip-seek-technique`). Path:
identify the IMA `onAdBreakStarted/Ended` (or DAI `StreamManager`) callbacks, use them as
the trigger, and reuse the ESPN slate/mute helper. If live is passthrough-SSAI rather than
client-IMA, fall back to the §2 manifest-DateRange method.

### General new target
1. Instrument a real break (logcat) → classify (bucket A/B/C).
2. Locate the window signal (§2 step 1) + playhead (step 2).
3. Copy the extension helper, swap reflected names.
4. Hook: overlay host (lifecycle), window feed, playhead feed.
5. Player-level mute. Diagnostic-first. Force-stop + verify on device.
6. Health-monitor a 20–30 min run before shipping.

---

## 6. Verification: Health Monitor
Reuse `health-monitor-technique`. The ESPN run script
(`scratchpad/health_monitor.sh`) babysits ~26 min: per-minute heartbeat (app alive,
foreground activity), counts break cycles + which media loaded, screenshots each slate,
and tallies FATAL/ANR/videoErr/muteFail. Green run = ship. Ship = `DEBUG=false`, branch →
conventional commit → PR → `gh pr checks --watch` → merge → semantic-release.

### Ship-time TODO: make the slate OPTIONAL
Some users may prefer to watch live ads normally, and for passthrough the slate IS the
whole behavior — so gate it:
- **Patch option** `enableLiveSlate` (boolean). When false, skip the overlay/session/
  playhead injections and keep only `isAdDisabled` (VOD/interstitial suppression). This is
  the Morphe-idiomatic toggle, decided at patch time.
- **Runtime toggle** (complement): the helper checks for a marker file in the app's
  external files dir (e.g. `slate_off`) at break start and no-ops if present — lets users
  disable on-device without repatching. Cheap to add alongside the media-file lookup.
Default recommendation: slate ON (it's the point of the live patch), option to turn OFF.
