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

## Candidate patch surface (not yet implemented)

The ad timeline is a **list of `StitchedSegmentPortion`** (VideoRegion +
TimeRange), delivered via `onStitchedSegmentsMetadataList(...)` and read back via
`getSsdaiInfo(Time)`. This is the same "empty-the-ad-cue-accessor-before-RETURN"
shape this repo already exploits for other SSAI apps (see
`java-ad-timeline-hook-methodology`): filter the portion list down to
content-only regions (or return an empty stitched list) so the player sees no ad
ranges. Because ads are stitched into the *same* segments as content, dropping
the whole list is NOT safe (would also drop content boundaries) — the correct
approach is to filter by `VideoRegion` type, keeping content regions and removing
ad regions. **Next step:** decode `VideoRegion`'s int fields to identify the
ad-vs-content type enum value before writing a fingerprint.

## Toolchain used (for reproducibility)

```
# APKM is a ZIP; extract, then work on base.apk only
unzip <file>.apkm -d splits/
# Java (reading): jadx 1.5.0
jadx -d decompiled splits/base.apk --deobf --show-bad-code -m restructure --no-res
# Smali (ground truth): baksmali 2.5.2, per dex
baksmali d classesN.dex -o smali/classesN
```
