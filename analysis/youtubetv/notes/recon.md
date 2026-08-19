# YouTube TV (YouTube Unplugged) — Recon

- **Package:** `com.google.android.apps.youtube.unplugged`
- **Version:** 10.33.2 (versionCode 210330210)
- **Source:** APKMirror `..._2arch_..._apkmirror.com.apkm` (2-arch APKM bundle)
- **APK type:** APKM → split bundle. `base.apk` holds all app logic; the two
  `split_config.arm64_v8a.apk` / `split_config.armeabi_v7a.apk` splits are native
  `.so` only (no Java/Kotlin logic).
- **base.apk:** 19.3 MB, 5 dex (`classes.dex`..`classes5.dex`), ~55.9k smali methods.
- **Obfuscator:** R8 (short obfuscated class names: `aqpl`, `aqol`, `her`, ...).
  Google's own proto classes keep readable names.
- **Framework:** native Java/Kotlin (no React Native / Flutter).

## Ad-delivery mechanism — VERDICT

YouTube TV uses **Google Server-Stitched DAI (SSDAI / SSAI)** — server-side ad
insertion where ads are stitched into the *same* media segments/manifest as the
content, with an out-of-band metadata list telling the client which time ranges
are ads.

This is a **hybrid of the two hypotheses:**

- It **is** Google DAI (the `dai.google.com` family, same vendor as Paramount+),
  **but not** Paramount+'s client-side IMA `StreamManager` / `VideoStitcher`
  REST flow. There is **no** IMA SDK, no `VideoStitcher`, no
  `dai.google.com` StreamRequest anywhere in the app. The only
  `doubleclick.net` strings are the ordinary GMA/AdMob display-banner SDK
  (MRAID `native_ads.html`, `sdk-core-v40`), unrelated to the video ad path.
- Architecturally it's **closer to Pluto's SSAI** — ads baked into the stream,
  client reads a stitched-segment timeline to know where the ads are — but
  implemented entirely with YouTube's own player libraries, not a third-party
  SSAI vendor.

## Key classes (verified in decompiled base.apk)

### Proto layer — `com.google.android.apps.youtube.proto.streaming`
- **`ServerStitchedDaiInfoOuterClass$ServerStitchedDaiInfo`**
  - field `b` (int) — bitfield
  - field `c` (`bblg` repeated) — list of stitched-segment entries
  - field `d` (`bbjk` bytes) — opaque `daistate` token
- **`StitchedSegmentsMetadataOuterClass$StitchedSegmentsMetadataList`** — the
  list the player receives with all ad/content boundaries.
- **`StitchedSegmentsMetadataOuterClass$StitchedSegmentPortion`**
  - `c` → `VideoRegion` (region descriptor, incl. ad-vs-content type)
  - `d` → `TimeRangeOuterClass$TimeRange` (start/duration of the portion)
  - `e` (String) — segment / ad id
- **`StitchedSegmentsMetadataOuterClass$VideoRegion`** — int fields b–f
  (region type + geometry/timing).

### Runtime layer
- **`aqpl`** (obfuscated) — builds `ServerStitchedDaiInfo`; reads a `"daistate"`
  entry from an `agcl` store (`mo6587b(long)` @ ~L411) and assembles the
  stitched-segment list.
- **`aqol`** (obfuscated) = `.../player/features/serverstitch/VideoStatsMonitor`
  — `handleStitchedVideoTransition`, needs a "Vss base url"; fires VSS playback
  pings across stitched (ad↔content) transitions.
- **`PlaybackControllerWrapper`** (exo2/platypus)
  - `getSsdaiInfo(Time)` @ L2845 — player queries SSDAI info for a playback time.
  - `onStitchedSegmentsMetadataList(StitchedSegmentsMetadataList)` @ L4083 —
    entry point where the stitched-segment (ad-boundary) list is delivered into
    the player; forwards to `mo6315v(...)`.

## CORRECTION (deeper dig) — VideoRegion is NOT the ad-type carrier

Decoding the proto wire descriptors corrected an initial wrong assumption:

- `ServerStitchedDaiInfo` = `{ repeated scalar (field 1, `c`), bytes daistate
  token (field 2, `d`) }`. It does **not** embed `StitchedSegmentsMetadata`.
- `StitchedSegmentPortion` = `{ VideoRegion c, TimeRange d, String id e }`.
- `VideoRegion` = enum `b` + four int32s (`c,d,e,f`). In `aqar`
  (`m9404a`/`m9405b`/`m9407d`) those four ints are consumed as **screen
  geometry** (x / y / size / z-order) with `getResources().getConfiguration()
  .orientation` checks in `aqap`. So `StitchedSegmentsMetadata` /
  `VideoRegion` is the **spatial multiview layout** subsystem (YouTube TV
  Multiview — `PAytv_multiview_edit`, `PAytv_multiview_recommendation`), NOT the
  ad timeline. `VideoRegion.b`'s enum (wire values 0–4, mapped in `aqap` L736)
  is a region *role*, not ad-vs-content.

### The REAL ad-break timeline: Cuepoints (native-driven)

- **`CuepointListOuterClass$CuepointList`** = repeated
  **`CuepointContext`** — the live SSDAI ad-break markers.
- **`CuePointDataProviderWrapper`**
  (`.../media/interfaces/`) is a **JNI bridge to the native C++ player core**:
  - `long nativePtr`, `native void destroy(long)`,
    `native void nativeOnAdBreakFulfillmentStatusChanged(long, String, int, byte[], String[])`.
  - `NativeCallback.onCuepointList(byte[])` @ L116 — native → Java callback:
    parses `CuepointList` from bytes, iterates `CuepointContext`, and for each
    set entry dispatches `apow(new amxc(cuepointContext), id)` to a consumer.
  - Inner `enum CuePointStatus`: `OK(0)`, `RATE_CONTROL(1)`, `SERVER_ERROR(2)`,
    `INACTIVE(3)`, `DELETED(4)`, `RATE_CONTROL_BY_CLIENT(11)`,
    **`NO_DAI_CONFIG_FROM_GAB(12)`**, `CACHE_MISS(13)` — "DAI config from GAB"
    confirms Google DAI ad decisioning in the native/backend layer.
  - Inner `AdConfigData(bbjk token, List)` carries the DAI ad config.

**Implication:** ad-break *decisioning* lives in native code (platypus/C++),
same shape as the Netflix native ad-strip challenge in this repo — not a
pure-Java timeline. But the Java `onCuepointList(byte[])` boundary still receives
the serialized cuepoint list, which is a candidate Java-side interception point
(neuter it to deliver an empty `CuepointList`), and
`nativeOnAdBreakFulfillmentStatusChanged` is the fulfillment-report edge.

## Candidate patch surface (not yet implemented)

1. **Java boundary (shallow):** `CuePointDataProviderWrapper$NativeCallback
   .onCuepointList(byte[])` — drop/empty the parsed cuepoint list so no ad
   breaks are dispatched to the player. Needs testing: native side may still
   splice ad segments regardless of the Java callback.
2. **Native (deep):** the `nativePtr` C++ core that produces cuepoints and does
   `NO_DAI_CONFIG_FROM_GAB`-style fulfillment. Same class of problem as
   `experimental/netflix-native-adstrip/` — likely requires native hooking, not
   a smali patch.

**Next step:** decode `CuepointContext`'s fields (message @1, message @4,
int32 @6, string @8, message @9) to find the ad-break time offset + type, and
determine experimentally whether emptying the Java `onCuepointList` is
sufficient or whether the native core stitches ads independently.

## Slate / blackout ("I've seen it on live broadcasts")

No literal "slate" token exists, but there are **two** distinct slate-like
mechanisms — the one you remember is almost certainly the **blackout card**:

- **Blackout slate (regional sports blackouts):** a full subsystem —
  `com/.../unplugged/player/overlay/UnpluggedBlackoutsEventObserver` (obf.
  `moa`), `TenxBlackoutsRenderer`, `TenxBlackoutRange`,
  `createSimplifiedBlackoutsRenderer()`, and the log
  *"Channel=%s is in active blackout but no blackout renderer is returned!"*.
  This renders the "not available in your area" overlay during blackouts —
  the on-screen slate you've seen. Separate from ads; driven by a
  `BlackoutsRenderer` proto pushed from the server per channel.
- **Ad slate/filler = the "Enjoy the zen, we'll be right back" Zen Beach clip.**
  Confirmed mechanism: when a cuepoint fires an ad break that DAI can't fill
  (`NO_DAI_CONFIG_FROM_GAB` / `SERVER_ERROR`), the client plays a **server-
  delivered interstitial video** as filler. It is NOT a bundled asset — no
  "zen"/"Zen Beach"/"right back" string or media exists in the APK (the only
  `zen` hits are `gads:zenith:`, "frozen", and a base64 blob — all unrelated).
  The filler rides the mainline YouTube **interstitial** framework:
  - Strings: `isPlayingInterstitial`, `INTERSTITIAL_REQUESTED` →
    `INTERSTITIAL_PLAYING`, `interstitialPlayerResponse`, `interstitialCpn`,
    `interstitialVideoState`, `LD.playInterstitialVideo`.
  - State/logging in `aizp` (obf.) and `afso` (obf.) +
    `player/video/state/DirectorSavedState`: the player tracks
    `currentContentVideoId` vs `currentVideoId` and an `isPlayingInterstitial`
    flag — i.e. it swaps the content stream for the interstitial (Zen) video for
    the break duration, then returns to content.
  - Implication for patching: the Zen filler is a *symptom* of an unsold break,
    not the ad itself. Killing the cuepoint/ad-break upstream (see ad surface
    above) removes both real ads and the Zen filler; targeting the interstitial
    player alone would only skip the filler, not sold ads.

## Toolchain used (for reproducibility)

```
# APKM is a ZIP; extract, then work on base.apk only
unzip <file>.apkm -d splits/
# Java (reading): jadx 1.5.0
jadx -d decompiled splits/base.apk --deobf --show-bad-code -m restructure --no-res
# Smali (ground truth): baksmali 2.5.2, per dex
baksmali d classesN.dex -o smali/classesN
```
