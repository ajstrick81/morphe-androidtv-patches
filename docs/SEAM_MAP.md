# Seam map

How each app delivers ads, where our patches cut in, and what proves it worked. Generated — do not edit by hand.

- **Code facts** (packages, targets, patches, fingerprints, runtime log lines) come from the sources via `node scripts/seam-map/extract.mjs`.
- **Mechanism notes** (engine, delivery, seam, device signature, drift) live in `docs/seam-map/overlay.json`.
- **Drift check** a new APK before building: `node scripts/seam-map/check.mjs <app> <apktool-output-dir>`. After an R8 rename, add `--deep --ref <last-good-apktool-dir>` to rank the renamed class first (it keeps its `.source` file name).
- Rebuild this page: `node scripts/seam-map/extract.mjs && node scripts/seam-map/render.mjs`.

**The four layers.** A seam is only understood when all four line up: *delivery* (how ads reach the player) → *code seam* (the fingerprint we match) → *patch evidence* (the log line our code prints when it acts) → *device signature* (what logcat shows when an ad actually plays). After an app update, patch evidence going quiet while the device signature appears is the fastest drift alarm.

## Engines

| Engine | Apps | How ads arrive | Seams that port within the family |
|---|---|---|---|
| **Google IMA / DAI client SDK** | paramount, mlbtv, tubi, foxone, rte, vix | The app's player asks Google's IMA SDK (client-side) or DAI (server-stitched pods) for ad breaks. The ad schedule surfaces in Java/Kotlin as cue points, ad-break lists or an AdsManager. | Empty the cue-point / ad-break accessor; neuter AdsManager.start; null the SSAI AdsLoader getter; redirect live pods to a slate. Seams found in one IMA app usually port to the next (Fox One = Tubi-class). |
| **Server-side stitching (SSAI) via manifest** | hbomax, pluto, espn, peacock | The CDN stitches ads into the stream itself. The only client-visible trace is the manifest (ad periods, DateRanges, ID3/beacon tags) and the host names that serve it. | Force a clean manifest (origin block, CDN fallback, ad-free URL swap); strip ad periods; or, for live passthrough SSAI, detect the break and MASK it (slate + mute). |
| **Proprietary native / JS ad engine** | primevideo, netflix, disney, twitchatv | The ad decision lives in the app's own runtime — a native library, a JS VM inside a .so, or a WebView — not in a standard SDK. | Find where the ad schedule crosses into the player (a response buffer, a JS function, an insertion-point accessor) and empty it there; native seams need re-pointing per version. |

## Coverage

| App | Engine | Targets | Patches | Fingerprints | Patch evidence logged | Device signature known |
|---|---|---|---:|---:|---|---|
| [disney](#disney) | Proprietary native / JS ad engine | 26.6.0+rc5-2026.04.21, 26.8.0+rc6-2026.05.20, 26.9.2+rc1-2026.06.12, 26.12.1+rc1-2026.07.15, 26.16.0+rc2-2026.09.08 | 2 | 6 | **no** | **no** |
| [espn](#espn) | Server-side stitching (SSAI) via manifest | 6.11.1 | 4 | 7 | yes (48 lines) | **no** |
| [foxone](#foxone) | Google IMA / DAI client SDK | 1.9.2 | 1 | 8 | **no** | **no** |
| [hbomax](#hbomax) | Server-side stitching (SSAI) via manifest | 7.9.0.61, 7.7.0.78, 7.5.0.73, 7.2.0.41 | 3 | 8 | yes (2 lines) | **no** |
| [mlbtv](#mlbtv) | Google IMA / DAI client SDK | 26.8.1.1 | 1 | 9 | yes (7 lines) | **no** |
| [netflix](#netflix) | Proprietary native / JS ad engine | 13.0.1 build 25028 | 6 | 2 | **no** | **no** |
| [paramount](#paramount) | Google IMA / DAI client SDK | 16.19.0, 16.17.0, 16.12.0, 16.8.0 | 1 | 3 | **no** | **no** |
| [peacock](#peacock) | Server-side stitching (SSAI) via manifest | 7.5.102, 7.6.100, 7.8.100 | 3 | 10 | yes (6 lines) | **no** |
| [pluto](#pluto) | Server-side stitching (SSAI) via manifest | 5.66.0-leanback | 5 | 10 | yes (26 lines) | yes |
| [primevideo](#primevideo) | Proprietary native / JS ad engine | 6.23.23+v15.5.0.70-armv7a | 7 | 5 | yes (37 lines) | yes |
| [rte](#rte) | Google IMA / DAI client SDK | 3.160.3 | 1 | 3 | **no** | **no** |
| [tubi](#tubi) | Google IMA / DAI client SDK | 10.28.5000 | 1 | 9 | **no** | **no** |
| [twitchatv](#twitchatv) | Proprietary native / JS ad engine | 13.0.0.2 | 1 | 1 | yes (5 lines) | **no** |
| [vix](#vix) | Google IMA / DAI client SDK | 4.46.0_tv, 4.47.2_tv | 2 | 2 | **no** | **no** |

## disney

**Package** `com.disney.disneyplus` · **Targets** `26.6.0+rc5-2026.04.21`, `26.8.0+rc6-2026.05.20`, `26.9.2+rc1-2026.06.12`, `26.12.1+rc1-2026.07.15`, `26.16.0+rc2-2026.09.08` · **Engine** Proprietary native / JS ad engine

| Layer | |
|---|---|
| Runtime | Disney media player (legacy DSS SDK and new com.disney.dmp). |
| Delivery | SGAI (server-guided ad insertion): the player receives insertion points / ranges that say where ads go. |
| Code seam | Insertion accessors `getPoints()` / `getRanges()` (legacy + DMP variants). |
| Patch action | Return empty insertion points/ranges → no breaks scheduled. 26.16: obfuscated MEL pause layer → pause ad made optional. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | Verified through 26.12.1; 26.16 pause-ad needed an optional patch (#167). |

**Gaps:** Re-anchor the 26.16 pause-ad seam. · No runtime log line. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Disney+ Android TV | `PauseAdStartedFingerprint` | `(custom matcher)`.— | `V` | `pauseData` | addInstructions `                     return-void                 ` |
| Disney+ Android TV | `InsertionGetPointsFingerprint` | `(custom matcher)`.— | `Ljava/util/List` |  | passed to helper/list |
| Disney+ Android TV | `InsertionGetRangesFingerprint` | `(custom matcher)`.— | `Ljava/util/List` |  | passed to helper/list |
| Disney+ Android TV | `DmpInsertionGetPointsFingerprint` | `(custom matcher)`.— | `Ljava/util/List` |  | passed to helper/list |
| Disney+ Android TV | `DmpInsertionGetRangesFingerprint` | `(custom matcher)`.— | `Ljava/util/List` |  | passed to helper/list |
| Suppress Household Prompt _(opt-in)_ | `OutOfHouseholdCheckFingerprint` | `(custom matcher)`.— | `Ljava/lang/Object;` |  | addInstructions |

</details>

## espn

**Package** `com.espn.score_center` · **Targets** `6.11.1` · **Engine** Server-side stitching (SSAI) via manifest

| Layer | |
|---|---|
| Runtime | ESPN player (MediaPlayerViewModel) with passthrough SSAI on live. |
| Delivery | Live: passthrough SSAI, breaks marked by DateRanges in the manifest. VOD: client flag. |
| Code seam | Live: DateRanges × playhead → break detector. VOD: `isAdDisabled`. `ProgramData.eventId` feeds the scoreboard slate. |
| Patch action | Live: mask (animated slate + mute). VOD: ads disabled. |
| Patch evidence | Tag MORPHE-ESPN-SLATE (slate on/off, mute, scoreboard). |
| Device signature | _unknown_ |
| Drift history | onProgramData needs an R8 -keep or NoSuchMethodError bounces live games. |

**Gaps:** Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Clone ESPN _(opt-in)_ | — | — | — |  |  |
| Disable auto-updates | — | — | — |  |  |
| ESPN Android TV | `InterstitialManagerInitFingerprint` | `(custom matcher)`.— | — |  | instructions `isAdDisabled` |
| ESPN live commercial-break slate | `PlayerActivityOnResumeFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p0 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |
| ESPN live commercial-break slate | `PlayerActivityOnPauseFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p0 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |
| ESPN live commercial-break slate | `SessionListenerOnEventFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p1 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |
| ESPN live commercial-break slate | `AttachSessionFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p1 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |
| ESPN live commercial-break slate | `ProgramDataAvailableFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p0 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |
| ESPN live commercial-break slate | `PlaylistRetrievedFingerprint` | `(custom matcher)`.— | — |  | addInstructions `invoke-static { p2 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;` |

Runtime evidence (tags `MORPHE-ESPN-SLATE`; first 12 of 48):

- D `registerActivity() — host container registered` — EspnAdBreakOverlayHelper.kt:203
- D `setSession(…)` — EspnAdBreakOverlayHelper.kt:218
- W `dump session failed: …` — EspnAdBreakOverlayHelper.kt:239
- W `getPlaybackSession failed: …` — EspnAdBreakOverlayHelper.kt:240
- W `dump playbackSession failed: …` — EspnAdBreakOverlayHelper.kt:242
- W `getDataSource failed: …` — EspnAdBreakOverlayHelper.kt:243
- W `dump dataSource failed: …` — EspnAdBreakOverlayHelper.kt:244
- D `META[…] …() = …` — EspnAdBreakOverlayHelper.kt:257
- W `getBreaks poll failed: …` — EspnAdBreakOverlayHelper.kt:287
- W `…() not found on …` — EspnAdBreakOverlayHelper.kt:295
- W `BreakInfo reflection failed: …` — EspnAdBreakOverlayHelper.kt:314
- D `getBreaks -> … breaks … playhead=…` — EspnAdBreakOverlayHelper.kt:322

</details>

## foxone

**Package** `com.fox.foxone` · **Targets** `1.9.2` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | Same Fox IMA-DAI SDK as Tubi. |
| Delivery | Client-side via the Fox SDK. |
| Code seam | Tubi hooks 1/2/3/5/6 are near drop-in; auto-skip-by-seek (dispatchAdPodStart + seekTo) explored. |
| Patch action | Skip ads. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | _unknown_ |

**Gaps:** Auto-skip-by-seek 3-patch WIP uncommitted. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `FoxImaAdEventListenerFingerprint` | `listeners/FoxImaAdListeners`.`adEventListener_delegate$lambda$0$0` | — | `adEvent` | addInstructions `return-void` |
| Skip ads | `FoxImaAdsLoadedListenerFingerprint` | `listeners/FoxImaAdListeners`.`adsLoadedListener_delegate$lambda$0$0` | — | `onAdsManagerLoaded` | addInstructions `return-void` |
| Skip ads | `FoxPlayerClearVodAdsFingerprint` | `player/FoxPlayer`.`clearVodAds` | — |  | addInstructions `return-void` |
| Skip ads | `FoxImaVodStreamRequestFingerprint` | `loaders/FoxImaStreamIdLoader`.`requestVODDAIUrl` | `V` | `requestVODDAIUrl() BEGIN...` | addInstructions `                 const-string v0, ` |
| Skip ads | `FoxImaLiveStreamRequestFingerprint` | `loaders/FoxImaStreamIdLoader`.`requestImaStreamId` | `V` | `requestImaStreamId BEGIN...` | addInstructions `                 const-string v0, ` |
| Skip ads | `YospaceDispatchAdEventFingerprint` | `listener/YospaceAnalyticEventObserver`.`dispatchAdEvent` | `V` |  | addInstructions `return-void` |
| Skip ads | `YospaceDispatchSlateEventFingerprint` | `listener/YospaceAnalyticEventObserver`.`dispatchSlateEvent` | `V` |  | addInstructions `return-void` |
| Skip ads | `YospaceSeekPolicyFingerprint` | `handler/YospaceSeekablePlaybackPolicyHandler`.`setHandleFastForwardSeek` | — |  | addInstructions `return-void` |

</details>

## hbomax

**Package** `com.wbd.hbomax` · **Targets** `7.9.0.61`, `7.7.0.78`, `7.5.0.73`, `7.2.0.41` · **Engine** Server-side stitching (SSAI) via manifest

| Layer | |
|---|---|
| Runtime | WBD/Discovery player (`com.discovery.player.*`). |
| Delivery | SSAI: ads stitched server-side; manifests from media.max.com / gmss / fwmrm (FreeWheel). |
| Code seam | Network origins (in-app block on media.max.com/gmss/fwmrm → clean `_fallback.mpd`) and the CDN-fallback coordinator (`isUsingAlternateCdn()` → true). |
| Patch action | Force the ad-free fallback manifest; Prefer Ad-Free Stream fixes the mid-roll 39999 fatal. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | 7.5.12 package rename; 7.7.0.78 dynamic-register fix (P2). Household prompt = server-side ExtraMember wall (not bytecode-beatable). |

**Gaps:** Keep the opt-in origin-block OFF (fatals mid-roll). · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| HBO Max - Disable Ads | `BoltNonLinearAdsRequestWriteSelfFingerprint` | `(custom matcher)`.— | — |  | addInstructions `return-void` |
| HBO Max - Disable Ads | `BoltDynamicAdFetcherInvokeSuspendFingerprint` | `(custom matcher)`.— | — |  | apply |
| HBO Max - Disable Ads | `SsaiInfoTimelineBuilderBuildAdBreaksFingerprint` | `(custom matcher)`.— | — |  | addInstructions `return-void` |
| HBO Max - Disable Ads | `SsaiInfoTimelineBuilderAccessorFingerprint` | `(custom matcher)`.— | — |  | addInstructions `return-void` |
| HBO Max - Disable Ads | `GenerateLiveTimelineEntriesForAdBreakFingerprint` | `(custom matcher)`.— | — |  | addInstructions |
| HBO Max - Disable Ads | `NowtilusEnabledFingerprint` | `(custom matcher)`.— | — |  | addInstructions `                 const/4 v0, 0x0                 return v0             ` |
| HBO Max - Block SSAI Ad Origins _(opt-in)_ | `DefaultHttpDataSourceOpenFingerprint` | `(custom matcher)`.— | `J` |  | addInstructions |
| HBO Max - Prefer Ad-Free Stream | `GetStreamInfoFingerprint` | `(custom matcher)`.— | — |  | addInstructions |

Runtime evidence (tags `HboStreamSelector`; first 12 of 2):

- I `remapping PRIMARY -> FALLBACK stream (ad-free manifest)` — HboStreamSelector.java:65
- W `preferFallbackType failed; using requested type` — HboStreamSelector.java:70

</details>

## mlbtv

**Package** `com.bamnetworks.mobile.android.gameday.atbat` · **Targets** `26.8.1.1` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | MLB.tv player. |
| Delivery | Live: Google DAI pod serving (same shape as Paramount live). |
| Code seam | Pod requests (slate-redirect near drop-in). |
| Patch action | Redirect live ad pods to a slate. |
| Patch evidence | Tag MORPHE-MLB-ADBREAK. |
| Device signature | _unknown_ |
| Drift history | _unknown_ |

**Gaps:** Early (recon-stage) seam. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| MLB At Bat Android TV | `VodStreamRequest3ArgFingerprint` | `(custom matcher)`.— | `Lcom/google/ads/interactivemedia/v3/api/StreamRequest;` |  | addInstructions |
| MLB At Bat Android TV | `VodStreamRequest4ArgFingerprint` | `(custom matcher)`.— | `Lcom/google/ads/interactivemedia/v3/api/StreamRequest;` |  | addInstructions |
| MLB At Bat Android TV | `SsaiMediaSourceStartupFingerprint` | `(custom matcher)`.— | `V` | `ImaServerSideAdInsertionMediaSource` | addInstructions `                 return-void             ` |
| MLB At Bat Android TV | `DaiStreamManagerHandlerFingerprint` | `(custom matcher)`.— | `V` | `IMA DAI Stream Event: ` `GSTREAM:DAI` | addInstructions `                 return-void             ` |
| MLB At Bat Android TV | `ExoMediaPlayerMetadataFingerprint` | `(custom matcher)`.— | `V` | `[ExoMediaPlayer] metadata received from stream` | addInstructions `                 return-void             ` |
| MLB At Bat Android TV | `SsaiDisplayContainerFingerprint` | `(custom matcher)`.— | — |  | apply `getAdViewGroup` |
| MLB At Bat Android TV | `AdBreakStartedFingerprint` | `(custom matcher)`.— | `V` |  | addInstructions `.trimIndent(),         )          AdBreakEndedFingerprint.method.addInstructions(             0,             ` |
| MLB At Bat Android TV | `AdBreakEndedFingerprint` | `(custom matcher)`.— | `V` |  | addInstructions |
| MLB At Bat Android TV | `OkHttpDataSourceOpenFingerprint` | `(custom matcher)`.— | `J` |  | apply `Ljava/io/InputStream;` |

Runtime evidence (tags `MORPHE-MLB-ADBREAK`, `MORPHE-MLB-MANIFEST`; first 12 of 7):

- D `registerAdViewGroup() — ad view group registered` — AdBreakOverlayHelper.kt:56
- W `ad break signaled but no ad view group registered — overlay cannot be shown` — AdBreakOverlayHelper.kt:99
- D `overlay shown — commercial break in progress` — AdBreakOverlayHelper.kt:106
- D `overlay hidden — commercial break ended` — AdBreakOverlayHelper.kt:111
- W `failed to read DataSpec uri via reflection` — MlbManifestRewriter.kt:117
- D `manifest census: segments=… adSegments=… ` — MlbManifestRewriter.kt:153
- D `stripped … ad segment(s) from manifest` — MlbManifestRewriter.kt:205

</details>

## netflix

**Package** `com.netflix.ninja` · **Targets** `13.0.1 build 25028` · **Engine** Proprietary native / JS ad engine

| Layer | |
|---|---|
| Runtime | JS VM inside libnetflix.so (V8 13.2, 32-bit). |
| Delivery | Ad metadata is produced and consumed in the app's JavaScript layer. |
| Code seam | `getAdMetadata` in the JS bundle (wildcard MASTER re-anchor), plus the ad-kill script. |
| Patch action | Strip ad metadata so no ad is scheduled; household prompt handled separately (opt-in). |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | Anchor drift on 13.0.1 (re-anchored at getAdMetadata); #166 re-minify drift fixed v1.36.0 (wildcard MASTER) and v1.37.1. Household prompt is a TIMING wall for string patches — needs a load-hook. |

**Gaps:** Pause-ad slips past PATCH B. · No runtime log line proves the seam fired — add one. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Clone Netflix | — | — | — |  |  |
| Suppress Household Prompt _(opt-in)_ | — | — | — |  |  |
| Minimize Network Fingerprint _(opt-in)_ | — | — | — |  |  |
| Disable Netflix CertCheck | `DexGuardCertCheckFingerprint` | `(custom matcher)`.— | `V` | `CertCheck failed, crash!!!` | addInstructions `return-void` |
| Remove Netflix ads (bundle engine) | — | — | — |  |  |
| Remove Netflix ads (loader) | `NetflixApplicationOnCreateFingerprint` | `ninja/NetflixApplication`.`onCreate` | `V` |  | addInstructions `                 const-string v0, ` |

</details>

## paramount

**Package** `com.cbs.ott` · **Targets** `16.19.0`, `16.17.0`, `16.12.0`, `16.8.0` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | AVIA player (com.paramount.android.avia) + Google IMA / DAI. |
| Delivery | VOD: client ad decision. Live: full-service IMA-DAI → one stitched stream (no CDN-fallback seam). |
| Code seam | VOD `shouldPlayAd()`; live: pod requests. |
| Patch action | VOD: shouldPlayAd → false. Live: pure slate-redirect (accepted ceiling — in-interceptor PTS alignment crashed weak hardware). |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | 16.17 ANR fixed in v1.19.0; fingerprints hardened for 16.19. |

**Gaps:** Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Paramount+ Android TV | `ShouldPlayAdFingerprint` | `(custom matcher)`.— | `Z` |  | addInstructions `                 const/4 v0, 0x0                 return v0             ` |
| Paramount+ Android TV | `PauseAdOverlayFingerprint` | `(custom matcher)`.— | `V` | ` not updating overlay.` | addInstructions `                 return-void             ` |
| Paramount+ Android TV | `AviaNetworkInterceptorFingerprint` | `(custom matcher)`.— | `Lokhttp3/Response;` |  | addInstructions |

</details>

## peacock

**Package** `com.peacocktv.peacockandroid` · **Targets** `7.5.102`, `7.6.100`, `7.8.100` · **Engine** Server-side stitching (SSAI) via manifest

| Layer | |
|---|---|
| Runtime | Peacock player with WebView + XTV client + OkHttp networking. |
| Delivery | DAI manifests via WebView (`/dai/pub/` paths). |
| Code seam | Manifest URL (`/dai/pub/` → `/pub/`), XTV client setters, OkHttp builder. |
| Patch action | Swap to the ad-free manifest path; block ad hosts in OkHttp. |
| Patch evidence | Extension log lines (see generated section). |
| Device signature | _unknown_ |
| Drift history | _unknown_ |

**Gaps:** Live not covered. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `MediaTailorProxyHostFingerprint` | `(custom matcher)`.— | `Ljava/lang/String;` |  | addInstructions `                 const-string v0, ` |
| Skip ads | `MediaTailorAdServiceMethodFingerprint` | `(custom matcher)`.— | `Ljava/lang/Object;` | `Could not build MT Advertising service` | addInstructions `                 const/4 v0, 0x0                 return-object v0             ` |
| Skip ads | `SsaiConfigurationProviderFingerprint` | `(custom matcher)`.— | `Lcom/sky/core/player/sdk/addon/SSAIConfigurationProvider;` |  | addInstructions `                 const/4 v0, 0x0                 return-object v0             ` |
| Skip ads | `HandleAdBreakStartedFingerprint` | `(custom matcher)`.— | `V` |  | addInstructions `                 return-void             ` |
| Skip ads | `GetOkHttpClientFingerprint` | `(custom matcher)`.— | `Lokhttp3/OkHttpClient;` |  | addInstructions |
| Skip ads | `FreewheelModuleSkipFingerprint` | `(custom matcher)`.— | — |  | apply |
| Skip ads | `XtvClientWrapFingerprint` | `(custom matcher)`.— | — |  | passed to helper/list |
| Skip ads | `XtvClientWrapTwoArgFingerprint` | `(custom matcher)`.— | — |  | passed to helper/list |
| Skip ads | `XtvClientWrapThreeArgFingerprint` | `(custom matcher)`.— | — |  | passed to helper/list |
| Skip ads | `NativeNetworkApiConstructorFingerprint` | `(custom matcher)`.— | — |  | passed to helper/list |
| Skip ads | `SdkRootOkHttpClientFingerprint` | — | — |  | passed to helper/list |
| Skip ads | `NewRelicInitFingerprint` | — | — |  | passed to helper/list |
| Clone Peacock _(opt-in)_ | — | — | — |  |  |
| Disable auto-updates | — | — | — |  |  |

Runtime evidence (tags `MORPHE-PCK-WV`; first 12 of 6):

- D `BLOCKED [` — PeacockWebViewHelper.java:223
- E `shouldInterceptRequest error: ` — PeacockWebViewHelper.java:228
- D `DAI clean manifest HTTP ` — PeacockWebViewHelper.java:302
- D `DAI->CLEAN manifest served (` — PeacockWebViewHelper.java:314
- E `DAI clean manifest fetch failed -> fall through: ` — PeacockWebViewHelper.java:321
- D `PeacockWebViewHelper.wrapClient() — Layer 7 active (randomized responses)` — PeacockWebViewHelper.java:331

</details>

## pluto

**Package** `tv.pluto.android` · **Targets** `5.66.0-leanback` · **Engine** Server-side stitching (SSAI) via manifest

| Layer | |
|---|---|
| Runtime | Paramount's AVIA player (AviaPlayer.startExoplayer) over media3/ExoPlayer; Pluto stitcher sessions. |
| Delivery | SSAI via Pluto's stitcher: VOD sessions carry ad breaks; live streams carry ID3 ad beacons. |
| Code seam | VOD: `StitcherSession.getAdBreaks()` + DASH ad-period strip + beacon/pause/clickable-ad binders. Live: `ID3AdsBeaconTracker.consumeID3` as the break DETECTOR. |
| Patch action | VOD: remove ad breaks entirely. Live: mask the break (black cover + mute), lifted when the show returns. |
| Patch evidence | `muteStream: OK` + `muteExo: OK` + `showCover` + `live ad break -> mask ON (mode=BOTH)` ~0.1 s after the first beacon; `live ad break -> mask OFF (last ad ended (QUARTILE_END, no next ad))` ~1.2 s after the last ad's QUARTILE_END (6.5 s no-tick fallback remains) (tag MORPHE-PLUTO-SLATE). |
| Device signature | Live (2026-09-24 captures, 5.66.0): the app's OWN verbose log `V/ID3AdsBeaconTracker consumeID3: ID3Tag(creativeId=…, currentIndexSec=N, maximumIndexSec=30\|15, typeFlags=[…])` every ~5 s per ad; `START_OF_MEDIA` = ad start, `QUARTILE_END` = ad end, creativeId changes per ad, next ad starts 80–104 ms after the previous QUARTILE_END. A decoder re-init at each splice. NOT a signature: resolution changes — HLS adaptive bitrate switches 720/360 all the time without ads. |
| Drift history | #144 fixed (positive ad-detect + <40% guard); #147 resume fixed; #152 live mask shipped, mute fixed v1.35.1. |

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Mask live ad breaks (black screen + mute) | `Id3ConsumeFingerprint` | `adsbeacontracker/ID3AdsBeaconTracker`.`consumeID3` | `V` |  | addInstructions `invoke-static { p1 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;` |
| Mask live ad breaks (black screen + mute) | `LeanbackHostOnCreateFingerprint` | `main/LeanbackMainHostActivity`.`onCreate` | `V` |  | addInstructions `invoke-static { p0 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;` |
| Mask live ad breaks (black screen + mute) | `LeanbackHostOnDestroyFingerprint` | `main/LeanbackMainHostActivity`.`onDestroy` | `V` |  | addInstructions `invoke-static { p0 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;` |
| Mask live ad breaks (black screen + mute) | `AviaControllerInitFingerprint` | `avia/AviaPlaybackController`.`<init>` | `V` |  | addInstructions `invoke-static { p1 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;` |
| Skip ads | `BeaconTrackerFireFingerprint` | `adsbeaconstracking/BeaconTracker`.`fire` | `V` | `adEventName` `urls` | addInstructions `return-void` |
| Skip ads | `PauseAdsShowFingerprint` | `binder/PauseAdsImageBinder`.`showPauseAdImageAfterInactivity` | `V` |  | addInstructions `return-void` |
| Skip ads | `ClickableAdsBindFingerprint` | `clickableads/ClickableAdsBinder$Companion`.`bind` | `Lio/reactivex/disposables/Disposable;` |  | addInstructions |
| Skip ads | `StitcherSessionGetAdBreaksFingerprint` | `data/StitcherSession`.`getAdBreaks` | `Ljava/util/List;` |  | addInstructions |
| Skip ads | `DashManifestParserParseFingerprint` | `manifest/DashManifestParser`.`parse` | `Landroidx/media3/exoplayer/dash/manifest/DashManifest;` |  | apply `skipAdsPatch Hook 5: no return-object in parse()` |
| Skip ads | `AviaStartExoplayerFingerprint` | `core/AviaPlayer`.`startExoplayer` | `V` |  | apply |
| Clone Pluto TV _(opt-in)_ | — | — | — |  |  |
| Override certificate pinning | — | — | — |  |  |
| Disable auto-updates | — | — | — |  |  |

Runtime evidence (tags `MORPHE-DASH-MF`, `MORPHE-PLUTO-SLATE`; first 12 of 26):

- I `dynamic (live) manifest -> untouched, periods=` — PlutoDashManifestProbe.java:144
- W `no content periods kept (of ` — PlutoDashManifestProbe.java:170
- W `GUARD tripped: kept ` — PlutoDashManifestProbe.java:180
- I `STRIPPED ` — PlutoDashManifestProbe.java:210
- E `strip failed -> original manifest (playback unaffected): ` — PlutoDashManifestProbe.java:214
- I `resume remap (#147): original=` — PlutoDashManifestProbe.java:275
- E `resume remap failed -> original position (resume unaffected): ` — PlutoDashManifestProbe.java:279
- D `setAviaPlayer(…)` — PlutoLiveSlateHelper.kt:97
- D `registerActivity(…)` — PlutoLiveSlateHelper.kt:108
- D `live ad break -> mask ON (mode=…)` — PlutoLiveSlateHelper.kt:171
- D `live ad break -> mask OFF (…)` — PlutoLiveSlateHelper.kt:183
- W `showCover: no content root — fragment activity unavailable` — PlutoLiveSlateHelper.kt:189

</details>

## primevideo

**Package** `com.amazon.amazonvideo.livingroom` · **Targets** `6.23.23+v15.5.0.70-armv7a` · **Engine** Proprietary native / JS ad engine

| Layer | |
|---|---|
| Runtime | libignite = WAMR AoT WASM runtime; player/timeline in QuickJS. v16 (6.24.x) moved the whole ad pipeline native (Java ad surface gone); the Megablast Rust ad client is a DOWNLOADED .so.br blob, updatable server-side. |
| Delivery | Client-stitched: the app fetches an ad schedule (regolith / SGAI `getVideoAds` response, intraTitlePlaylist) and stitches ad pods into playback itself. |
| Code seam | The regolith ad-playlist response inside libignite — intercepted by a native GOT/PLT hook (memcpy) and emptied; intraTitlePlaylist PATH1 skipped. |
| Patch action | Empty the ad playlist before the player sees it → the break is never scheduled. |
| Patch evidence | `[rego] emptied playlist (~N ad(s), interior X bytes blanked)` + `PVKILL path=tv ads=N` at the moment each pod is fetched (2026-09-24 live: 1 pre-roll, 4 + 3 mid-roll ads emptied). |
| Device signature | When an ad DOES play (6.24.7 capture): decoder reconfigure (`Discard frames from previous generation`, `VideoResolutionChange`) + crop switch (title-specific, e.g. letterbox 960 → ad 1080) + ~27 `I/f Successfully uploaded metrics` in ~2 s from the app process. With the patch working: none of these at the marker, 0 metrics uploads. |
| Drift history | v16 loader never fired because onCreate became `public final` vs an exact accessFlags match (fix: drop accessFlags). 6.24.7 = minor bump, seam re-pointed (declared target still in progress). High-ad-region mid-roll truncation (#120) fixed in v1.37.4. |

**Gaps:** Timeline-action native symbols unresolved (Native_StartTimelineAction not in NativeSymbol table).

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `SetAdPlaybackStatesMedia3Fingerprint` | `ads/ServerSideAdInsertionMediaSource`.`setAdPlaybackStates` | `V` |  | addInstructions |
| Skip ads | `SetAdPlaybackStatesExo2Fingerprint` | `ads/ServerSideAdInsertionMediaSource`.`setAdPlaybackStates` | `V` |  | addInstructions |
| Skip ads | `MetricsTransporterTransmitFingerprint` | `transport/MetricsTransporter`.`transmit` | `Lcom/amazon/minerva/client/thirdparty/transport/UploadResult;` |  | addInstructions `SUCCESS` |
| Skip ads | `BasicNetworkPerformRequestFingerprint` | `toolbox/BasicNetwork`.`performRequest` | `Lcom/android/volley/NetworkResponse;` |  | addInstructions |
| Clone Prime Video _(opt-in)_ | — | — | — |  |  |
| Prime Video extension | — | — | — |  |  |
| Override certificate pinning | — | — | — |  |  |
| Disable auto-updates | — | — | — |  |  |
| Bundle native ad-strip hook | — | — | — |  |  |
| Load native ad-strip hook | `ApplicationOnCreateFingerprint` | `primevideo/PrimeVideoApplication`.`onCreate` | `V` |  | addInstructions |

Runtime evidence (tags `SkipAdsPatch`, `PVNativeHook`; first 12 of 37):

- I `skipAllMedia3AdGroups: entries=` — SkipAdsPatch.java:99
- E `skipAllMedia3AdGroups failed` — SkipAdsPatch.java:103
- I `skipAllExo2AdGroups: entries=` — SkipAdsPatch.java:131
- E `skipAllExo2AdGroups failed` — SkipAdsPatch.java:135
- I `enforceAdBlock: blocking getVideoAds (ad decisioning)` — SkipAdsPatch.java:170
- I `enforceAdBlock: blocking ` — SkipAdsPatch.java:218
- I `NativeHookLoader: loaded lib` — NativeHookLoader.java:43
- E `NativeHookLoader: could not load lib` — NativeHookLoader.java:49
- E `NativeHookLoader: unexpected failure loading lib` — NativeHookLoader.java:51
- N `got: … has no PT_DYNAMIC` — got_hook.cpp:60
- N `got: … missing dynamic entries (symtab=… strtab=… jmprel=… sz=…)` — got_hook.cpp:82
- N `got: … PLT reloc type is not DT_REL (…)` — got_hook.cpp:88

</details>

## rte

**Package** `com.twentyfouri.tvbridge.rte` · **Targets** `3.160.3` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | 24i player on media3 + IMA (VOD). |
| Delivery | Client-side IMA VOD ads. |
| Code seam | Cue-points `long[]` (Hook3) and `AdsManager.start`. |
| Patch action | Empty cue points; neuter AdsManager.start. ⚠️ Neutering onAdsManagerLoaded HANGS. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | _unknown_ |

**Gaps:** No runtime log line. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `ClientAdsManagerStartFingerprint` | `(custom matcher)`.— | `V` |  | addInstructions `return-void` |
| Skip ads | `ClientAdEventHandlerFingerprint` | `(custom matcher)`.— | `V` |  | addInstructions `return-void` |
| Skip ads | `CuePointsToTimesFingerprint` | `(custom matcher)`.— | `[J` |  | addInstructions `                 const/4 v0, 0x0                 new-array v0, v0, [J                 return-object v0             ` |

</details>

## tubi

**Package** `com.tubitv` · **Targets** `10.28.5000` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | Fox IMA-DAI SDK. |
| Delivery | Client-side ad decisions through the Fox SDK. |
| Code seam | SDK ad hooks 1/2/3/5/6 (per-version R8 class renames). |
| Patch action | Skip ads at the SDK boundary. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | R8 renames classes each version → per-version re-pin map (10.28.5000 shipped; newer re-pins in progress). |

**Gaps:** No runtime log line. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `FoxImaAdEventListenerFingerprint` | `listeners/FoxImaAdListeners`.`adEventListener_delegate$lambda$10$lambda$9` | — | `adEvent` | addInstructions `return-void` |
| Skip ads | `FoxImaAdsLoadedListenerFingerprint` | `listeners/FoxImaAdListeners`.`adsLoadedListener_delegate$lambda$4$lambda$3` | — | `onAdsManagerLoaded` | addInstructions `return-void` |
| Skip ads | `FoxPlayerClearVodAdsFingerprint` | `player/FoxPlayer`.`clearVodAds` | — |  | addInstructions `return-void` |
| Skip ads | `TubiPauseAdsFingerprint` | `pauseads/ImagePauseAds`.`l` | `V` |  | addInstructions `return-void` |
| Skip ads | `FoxImaVodStreamRequestFingerprint` | `loaders/FoxImaStreamIdLoader`.`requestVODDAIUrl` | `V` | `requestVODDAIUrl() BEGIN...` | addInstructions `                 const-string v0, ` |
| Skip ads | `FoxImaLiveStreamRequestFingerprint` | `loaders/FoxImaStreamIdLoader`.`requestImaStreamId` | `V` | `requestImaStreamId BEGIN...` | addInstructions `                 const-string v0, ` |
| Skip ads | `TubiWebClientInterceptFingerprint` | `Po/C$c`.`shouldInterceptRequest` | `Landroid/webkit/WebResourceResponse;` |  | addInstructions |
| Skip ads | `TubiWebClientPageFinishedFingerprint` | `Po/C$c`.`onPageFinished` | `V` |  | addInstructions `                 const-string v0, ` |
| Skip ads | `QfcSuspendGetAdBreaksFingerprint` | `sf/c`.`suspendGetAdBreaks` | `Ljava/lang/Object;` |  | addInstructions `                 new-instance v0, Ljava/io/IOException;                 const-string v1, ` |

</details>

## twitchatv

**Package** `tv.twitch.android.app` · **Targets** `13.0.0.2` · **Engine** Proprietary native / JS ad engine

| Layer | |
|---|---|
| Runtime | Starshot WebView player (Android TV build only). |
| Delivery | HLS with server-inserted ad segments + X-TV-TWITCH-AD-* signal tags; native IVS pre-roll. |
| Code seam | WebView `shouldInterceptRequest` on playlists/segments. |
| Patch action | Blank-substitute ad segments with PTS smoothing (the client ceiling; zero-ads blocked by native pre-roll). |
| Patch evidence | Extension log lines (see generated section). |
| Device signature | _unknown_ |
| Drift history | v9.0.0 is a native ExoPlayer build that predates Starshot → incompatible (#164). |

**Gaps:** Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Block TV ads | `SetWebViewClientFingerprint` | `app/StarshotActivity`.— | — |  | apply `->setWebViewClient(` |

Runtime evidence (tags `MORPHE-TW-ATV-WV`; first 12 of 5):

- D `wrapClient — Twitch ATV HLS scrubber active` — TwitchAtvWebViewHelper.java:109
- E `playback-fix injection failed` — TwitchAtvWebViewHelper.java:128
- E `shouldInterceptRequest error` — TwitchAtvWebViewHelper.java:173
- I `blanked stitched-ad segments in weaver playlist` — TwitchAtvWebViewHelper.java:223
- E `httpGet failed: ` — TwitchAtvWebViewHelper.java:346

</details>

## vix

**Package** `com.univision.prendetv` · **Targets** `4.46.0_tv`, `4.47.2_tv` · **Engine** Google IMA / DAI client SDK

| Layer | |
|---|---|
| Runtime | IMA DAI pod serving (baked SSAI). |
| Delivery | Server-stitched pods via IMA DAI. |
| Code seam | Cue-empty (+ clone build). |
| Patch action | Empty cues; clone to avoid keystore mismatch. |
| Patch evidence | _unknown_ |
| Device signature | _unknown_ |
| Drift history | Device's ViX key ≠ CLI key → uninstall or clone. |

**Gaps:** Parked; cue-empty built but unverified. · Device signature not captured.

<details><summary>Patches and fingerprints (generated)</summary>

| Patch | Fingerprint | Class / method | Returns | Strings | Action |
|---|---|---|---|---|---|
| Skip ads | `LuraAdBreakSchedulerFingerprint` | `(custom matcher)`.— | — |  | addInstructions `                 return-void             ` |
| Skip ads | `InnovidStartAdFingerprint` | `innovid/InnovidHelper`.— | `V` | `innovidAd` | addInstructions `                 return-void             ` |
| Override certificate pinning | — | — | — |  |  |

</details>
