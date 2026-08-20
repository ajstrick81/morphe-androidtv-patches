# YouTube TV (Unplugged) — Ad Architecture Reanalysis (SCTE-35 lens)

A first-pass **static reconnaissance** of the YouTube TV Android TV app, re-run
with the SCTE-35 / live-signaling perspective from
[`SCTE35_AD_SIGNALING_REFERENCE.md`](SCTE35_AD_SIGNALING_REFERENCE.md). Goal:
find where the ad seam is handled **in bytecode we can reach**, and separate the
real walls from the assumed ones.

**Method & caveat:** this pass is **string/symbol level** (`unzip` + `strings` +
grep across the dex and native libs) — `apktool`/`baksmali` weren't available in
this environment, so exact obfuscated method pins and control-flow are a TODO for
the disassembly pass (see [Next steps](#next-steps)). Everything below is
grounded in on-disk evidence from the two supplied splits.

Artifacts analyzed:
- `base.apk` — 19.3 MB, 5 dex (`classes.dex`..`classes5.dex`, ~31 MB dex total),
  no native libs.
- `split_config.armeabi_v7a.apk` — native libs only, no dex.

---

## TL;DR — this is NOT the Twitch wall

The Twitch blueprint's wall was a **native IVS media source** bytecode can't
reach. **YouTube TV is the opposite:** the entire playback + ad-decisioning
pipeline is **Java/`androidx.media3` (ExoPlayer)**, fed by a **Java** custom
source. The ad seam is client-guided (SGAI-shaped), not purely server-welded.
That means the classic **"empty the accessor"** technique (the one the Twitch
doc notes works on Pluto/Disney) has real targets here.

- ✅ **Media pipeline is `androidx.media3.exoplayer` in bytecode** — reachable.
- ✅ **SCTE-35 is parsed in Java** — ExoPlayer's `scte35` parser is present
  (`SCTE-35 TimeSignalCommand`, `SpliceInsertCommand`, `PrivateCommand`;
  `application/x-scte35`; DASH scheme `urn:scte:scte35:2014:bin`).
- ✅ **Ads are client-decisioned via `AdSlotRenderer` + `CuePoint` +
  `AdBreakRequest`** — an in-app surface we can neuter.
- ⚠️ **Residual wall:** anything genuinely **SSAI-welded** into the DASH stream by
  the custom `MediaPushMediaSource`, plus an ad-break-fulfillment hook that
  crosses into **native** (`nativeOnAdBreakFulfillmentStatusChanged` in
  `libgoogle3.so`). Same *shape* as Twitch's residual pre-roll — needs on-device
  confirmation of how much fill is welded vs. client-fetched.
- 🔌 **Network is Cronet** (native Chromium, `libcronet.151.0.7922.83.so`) — so
  manifest/segment/player-response fetches do **not** pass through a Java HTTP
  layer. Intercept **after** parse (in Java), not at the socket.

---

## The pipeline, as found

```
Cronet (native Chromium net stack)  ── fetches player-response, DASH manifest, segments
        │  bytes handed to Java
        ▼
InnerTube player-response parse (Java)
  com.google.android.libraries.youtube.innertube.model.ads.InstreamAd
  com.google.android.libraries.youtube.ads.model.{InstreamAdImpl,InstreamAdBreak}
        │  ad breaks derived from:  CuePoint + AdBreakIndex   ← the SCTE-driven seam
        │  "Neither InstreamAdBreak or (CuePoint + AdBreakIndex) is provided for the ABR slot"
        ▼
Ad-break request / decisioning (Java, "ABR" = Ad Break Request)
  createAdBreakRequestSlotFromCuePoint()
  getAdBreakResponseForAdBreakBlocking()
  SelectionResult{adSlotRenderers=[…]}     ← AdSlotRenderer list = the accessor to empty
        │
        ▼
Playback: androidx.media3 ExoPlayer
  fed by custom Java source:
  com.google.android.libraries.youtube.media.player.exo2.platypus.mediapush.MediaPushMediaSource
  SCTE-35 metadata parsed in-band (application/x-scte35) → ExoPlayer scte35 commands
        │
        └─ ad-break fulfillment status callback crosses to native:
           nativeOnAdBreakFulfillmentStatusChanged  (libgoogle3.so)
```

### Where things live (dex map)
| dex | Role (by symbol density) |
|---|---|
| `classes.dex` | ads model + scte (`scte35`=1, `cuepoint`=5, `mediapush`=3) |
| `classes2.dex` | scte parser strings + interstitial (`scte35`=2, `cuepoint`=3) |
| **`classes3.dex`** | **media/platypus core** — `mediapush`=64, `cuepoint`=36, `adSlotRenderer`=13. This is the ExoPlayer custom-source + ad-slot dex; **primary patch target.** |
| `classes4/5.dex` | no ad/media hits |

### Key packages/classes (evidence-backed)
- `…/media/player/exo2/platypus/mediapush/MediaPushMediaSource` — custom ExoPlayer
  `MediaSource` ("platypus"/"mediapush"). YouTube's server-driven segment push. **Java**, not native — the crucial contrast with Twitch IVS.
- `…/ads/model/InstreamAdImpl`, `…/ads/model/InstreamAdBreak`
  (`InstreamAdBreak: [breakType, adBreakIndex, offset, originalVideoId]`)
- `…/innertube/model/ads/InstreamAd`
- ExoPlayer: `androidx/media3/exoplayer/ExoPlayer`, `AdPlaybackState(adsId=…, adGroups=[])`
- SCTE: `application/x-scte35`, `urn:scte:scte35:2014:bin`, ExoPlayer scte35 command toStrings.
- Cast ad structs (second surface, casting only): `com.google.android.gms.cast.AdBreakInfo/AdBreakClipInfo/AdBreakStatus`.

---

## How the SCTE-35 lens paid off

The reference doc predicted the real trigger would be a **client-guided
cue → ad-request** surface, not a blockable ad host. That's exactly what's here:

- **The seam is the CuePoint.** Ad breaks are built from **`CuePoint + AdBreakIndex`** — the client reads the cue (the SCTE-signaled opportunity, surfaced in the player-response/manifest) and *itself* constructs an **Ad Break Request** to get fill. This is the SGAI ("server-guided") model the guide named, not opaque SSAI welding.
- **Because the client builds the request, bytecode sits in the loop.** If `adSlotRenderers` is empty / the CuePoint→slot conversion yields nothing / the InstreamAdBreak list is empty, there is no fill to play.

This is the "walls are usually assumptions" payoff: *"YouTube ads are server-side, so a client patch can't help"* is the assumption. The truth is the **decisioning is client-side**; only the final creative bytes (when welded) are server-side.

---

## Candidate levers (for the disassembly pass)

Ranked; all are "empty the accessor" variants, most reachable first:

1. **Empty `adSlotRenderers` at selection.** Force `SelectionResult{adSlotRenderers=[]}` / the `Null adSlotRenderers` path. No slots → no breaks. Highest leverage, in `classes3.dex`.
2. **Neuter CuePoint→slot conversion.** Make `createAdBreakRequestSlotFromCuePoint()` return no slot, or `getAdBreakResponseForAdBreakBlocking()` return an empty response.
3. **Empty `InstreamAdBreak` list at player-response parse.** In `…/ads/model` / `…/innertube/model/ads` — stop breaks being built at ingestion.
4. **ExoPlayer `AdPlaybackState`** — force to the `adGroups=[]` empty state if breaks reach the timeline.

**Do NOT** expect any of these to remove **welded** SSAI segments (if YouTube
stitches some fill into the MediaPush DASH stream). That's the residual wall —
measure it on device before assuming it exists or doesn't.

---

## Walls — real vs. assumed

| Wall | Verdict |
|---|---|
| "Native media source (like Twitch IVS)" | ❌ **Assumed, false.** Pipeline is `androidx.media3` + Java `MediaPushMediaSource`. Reachable. |
| "Ads are pure SSAI, unblockable" | ⚠️ **Partly.** Decisioning is client-side (reachable); only welded creative bytes (if any) are server-side. |
| "Cronet hides the traffic" | ⚠️ **Real for socket interception**, irrelevant for the chosen approach — we patch the **Java parse/decision layer** after Cronet. |
| `nativeOnAdBreakFulfillmentStatusChanged` native hook | ⚠️ **Real, bounded.** Some fulfillment status crosses to `libgoogle3.so`; may or may not gate playback. Confirm whether emptying (1)–(3) makes it a no-op. |

---

## Next steps

1. **Disassembly pass** (`apktool`/`baksmali` on a box that has them): pin the
   obfuscated classes/methods behind the strings above in `classes3.dex` —
   the `AdSlotRenderer` selection method, `createAdBreakRequestSlotFromCuePoint`,
   `InstreamAdBreak` builder — so we can write ReVanced-style fingerprints.
2. **On-device capture** (the one empirical question from the reference doc):
   record a live YouTube TV session, inspect the DASH manifest for
   `<EventStream …scte35…>` ad-Periods and count **welded vs. client-fetched**
   fill. Decides how much lever (1)–(3) can actually remove.
3. **Prototype lever #1** (empty `adSlotRenderers`) and measure ad reduction vs.
   any residual welded pre/mid-roll.
4. Fold pins into a `patches/.../youtubetv/…` patch scaffold.

---

## Sources / cross-refs
- [`SCTE35_AD_SIGNALING_REFERENCE.md`](SCTE35_AD_SIGNALING_REFERENCE.md) — the signaling foundation this pass applied, built on the SCTE-35 guide by **Andy Francis & Alex Zambelli** (Bitmovin/VidTech).
- [`TWITCH_AD_SUPPRESSION_BLUEPRINT.md`](TWITCH_AD_SUPPRESSION_BLUEPRINT.md) — contrast: native-source wall + "empty the accessor" prior art.
- On-disk evidence: `base.apk` dex strings, `split_config.armeabi_v7a.apk` native libs (this analysis).
