# Pluto TV VOD Ad Removal — SOLVED & SHIPPED (2026-07-31)

> **✅ SOLVED.** Pluto VOD ads are now fully **removed** (not skipped). The stitched
> DASH manifest media3 fetches is a multi-period timeline with the ads as real
> `<Period>`s. media3 is **not obfuscated** in this build, so `skipAdsPatch` Hook 5
> hooks the return of `DashManifestParser.parse(Uri, InputStream)` and rewrites the
> parsed `DashManifest`: keep only content periods (content = a `pDRM` rendition
> BaseUrl; both ad flavors — `siloh…/_ad/creative/…` and `…/head(…)/sign/v1/…` — are
> un-DRM'd), re-base them contiguous, rebuild the manifest. Verified on-device (Onn
> 4K, "Angels & Demons"): **2:49:58 → 2:18:41, mid-rolls gone, playback clean across
> all period seams.** Post-parse seam never touches the streaming OkHttp client
> (wrapping that black-screened playback). Fail-open. Impl:
> `extensions/.../pluto/ads/PlutoDashManifestProbe.java` (`stripAdPeriods`) +
> `patches/.../pluto/ads/SkipAdsPatch.kt` (Hook 5) +
> `DashManifestParserParseFingerprint`. Diagnostic scaffolding (adprobe, clip-strip,
> manifest-probe) was removed before ship; the full trail lives in the
> `pluto-adbreaks-suppression` memory. Everything below is the historical journey.

---

# Pluto TV VOD Ad Removal — Session Handoff (2026-07-25)

## Goal
Fully **remove** (not skip) Pluto TV VOD ads on the Onn 4K TV. The shipped
`skipAdsPatch` only empties the ad-break metadata, so ads are FF-skippable but
the ad video still plays. This thread is about true removal.

## TL;DR of where we are
- Built a full diagnostic + removal toolchain (adprobe probe, HLS scanner,
  full-body dump, clip-strip rewriter) — all committed, compiles, works on-device.
- **Key finding:** the stitcher `session.json` `clips[]` array is **metadata, NOT
  the DASH playback timeline.** We rewrite it correctly (strip ad clips +
  re-baseline to the true 2:18 runtime) but the player ignores it and plays the
  full 2:40:59 with ads. The real ad timeline is the **stitched DASH manifest
  media3 fetches directly**, via a client we don't hook.
- **Working hypothesis (user's):** like Prime Video (movie vs TV-show mechanisms),
  Pluto likely has a **second ad path** we haven't hooked. Don't assume one path.

## The architecture (confirmed on-device, tv.pluto.android 5.66.0-leanback)
- Player = media3/ExoPlayer + Paramount Avia, DASH.
- Stitcher session: `GET https://cfd-v4-service-stitcher-dash-use1-1.prd.pluto.tv/v3/session.json?clientTime=…&includeExtendedEvents=false`
  → ~1.7 MB JSON: top-level `{id, serverTime, clips[], adBreaks[]}` (no top-level
  duration/window — timeline is per-clip).
  - `clips[]` = ordered content + ad clips. Ad clips tagged `type:"creative"` +
    `adPodId` + `creativeID`; content clips `type:"clip"`. Clips are **contiguous**
    by ISO `startTime`/`endTime`/`timelineStartAt` (each starts where prev ends).
    No media URL on clips (only a thumbnail `templateUrl`).
  - `adBreaks[]` = parallel ad metadata (slotStart/slotEnd, ads[] with creativeId).
- media3 fetches a **separate .mpd per clip** from
  `service-manifest-generator-use1-1.prd.pluto.tv/v1/fmp4/dash/{clipID}/.../{N}_ad/creative/{clipID}_ad/720p/.../main.mpd`
  — ad manifests are URL-distinguishable by the **`/_ad/creative/`** path segment.
  These are fetched by media3's OWN OkHttpDataSource client (NOT the stitcher
  session client we hook) and are **not logged in normal logcat**.

## What we built (branch `feat/pluto-adprobe-diagnostic`; rebase onto main before any PR)
Commits: `b7d3a1e` (adprobe probe + scanners), `72f1d19` (clip-strip + probe
read-only fix), `ff83f95` (re-baseline).
- `extensions/.../adprobe/AdSeamScanner.java` — portable JSON/keyword scanner + `scanRequest` + `dumpFull` (chunked full-body dump).
- `extensions/.../adprobe/HlsManifestScanner.java` — portable HLS/SCTE-35 structural scanner.
- `extensions/.../adprobe/StringResponseBody.java` — minification-safe okio-backed ResponseBody (avoids R8-stripped `ResponseBody.create`).
- `extensions/.../pluto/ads/PlutoAdProbe.java` + `PlutoAdProbeHelper.kt` — read-only session probe (tag `MORPHE-ADPROBE-PLUTO`).
- `extensions/.../pluto/ads/PlutoClipStrip.java` + `PlutoClipStripHelper.kt` — removal rewriter (strip ad clips + empty adBreaks + re-baseline), tag `MORPHE-PLUTO-CLIPSTRIP`.
- `patches/.../pluto/ads/{AdProbePatch,ClipStripPatch}.kt` + `Fingerprints.kt` (`StitcherSessionApiFingerprint`).
- Injection seam (resolved, unique): `Ltv/pluto/library/player/di/StitcherSessionJwtApiModule;->buildStitcherSessionApi(...)` — wraps the `IHttpClientFactory.getHttpClientJwtAware()` client. Covers HLS+DASH.

## The decisive result that redirected us
Clip-strip log: `STRIPPED 43 ad clips (kept 14 content, re-baselined to 8321s
contiguous)` = 2:18:41 = the real movie runtime. But player showed **2:40:59**
(= 2:18:41 + ~22 min ads). Only 1 session.json request, and we stripped it, yet
full timeline played → **session.json clips[] is metadata, not the playback timeline.**

## NEXT STEPS (in order)
1. **Decisive 1-line test:** make `PlutoClipStrip.isAdClip()` return true for
   everything (strip ALL clips incl. content). Re-patch, cold-install, play.
   - Playback breaks/empty → app DOES use our session (subtler bug to fix here).
   - Playback still full-with-ads → app ignores our session for playback →
     confirms the media3/DASH-manifest layer owns the timeline.
2. **Hook media3's DASH manifest fetch** (its OkHttpDataSource client — a DIFFERENT
   client than the stitcher session; find its DI provider in the decomp). Add a
   probe first (read-only) to SEE the master/child .mpd and confirm `_ad/creative`
   periods. Then strip ad Periods (DASH period surgery + continuity — the
   ghost-period ~50% desync race from PV applies; ads are cleanly `_ad/creative`).
3. **Watch for a second ad mechanism** (user's PV-analogy hypothesis) — don't
   assume the DASH manifest is the only path.

## Environment / recipe (see docs/AGENT_PATCH_WORKFLOW.md for full detail)
- Device: Onn 4K Plus "coffey", `adb connect 192.168.12.211:5555` (IP changed from .209; re-check).
  Pluto is free/no-login; sign mismatch → `adb uninstall tv.pluto.android` then install (loses local state only). Cold test = `am force-stop` + `pm clear` first.
- Decomp: `/d/Morphe/decompiled/pluto-decomp` (5.66.0-leanback, USB-slow; copy subtrees local, use ripgrep). APK: `/d/Morphe/apks/pluto-universal.apk`.
- CLI: `/d/Tools/morphe-cli-1.9.1-all.jar`. Build mpp: export GITHUB_ACTOR/GITHUB_TOKEN from `~/.gradle/gradle.properties`, then `./gradlew :patches:build -x test --exclude-task :patches:stub:compileJava`. Current branch version = **1.15.0** (delete stale mpps; pick newest by mtime).
- Patch (clip-strip only): `java -jar $CLI patch -f -e "Remove ads (clip strip, experimental)" -d "Ad-probe diagnostic (Pluto, read-only)" -d "Skip ads (session scrub, experimental)" -p <mpp> -o out.apk pluto-universal.apk`
  (the placeholder `OkHttpSessionClientBuilderFingerprint` scrub patch is default-on and ABORTS default runs — always `-d` it or `--continue-on-error`; chip open to fix it).
- Gotchas: Pluto's okhttp is R8-minified — NO `ResponseBody.create(MediaType,String)`, NO `MediaType.subtype()` (use `StringResponseBody` + `Content-Type` header string). logcat drops chunks on fast bursts (`dumpFull` lost ~15%); media3 fetch URLs not logged by default.

## Memory
See `pluto-adbreaks-suppression` (has the full trail + the 2026-07-25 update) and
`adprobe-session-handoff`. This work is unrelated to the twitch WIP on the other branch.
