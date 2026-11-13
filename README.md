<p align="center">
  <img src="docs/readme/banner.svg" alt="Morphe Patches for Android TV: ad-free streaming, patched on your own TV" width="100%">
</p>

<p align="center">
  <a href="https://github.com/ajstrick81/morphe-androidtv-patches/releases/latest"><img src="https://img.shields.io/github/v/release/ajstrick81/morphe-androidtv-patches?style=flat-square&label=release&color=3b82f6" alt="Latest release"></a>
  <a href="https://github.com/ajstrick81/morphe-androidtv-patches/releases"><img src="https://img.shields.io/github/downloads/ajstrick81/morphe-androidtv-patches/total?style=flat-square&label=downloads&color=3b82f6" alt="Downloads"></a>
  <a href="https://github.com/ajstrick81/morphe-androidtv-patches/stargazers"><img src="https://img.shields.io/github/stars/ajstrick81/morphe-androidtv-patches?style=flat-square&label=stars&color=f59e0b" alt="Stars"></a>
  <a href="https://github.com/ajstrick81/morphe-androidtv-patches/issues"><img src="https://img.shields.io/github/issues/ajstrick81/morphe-androidtv-patches?style=flat-square&label=issues&color=22c55e" alt="Open issues"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-8b5cf6?style=flat-square" alt="License: GPL-3.0"></a>
</p>

<p align="center"><a href="#status">Patch status</a> · <a href="#install">Install</a> · <a href="#apps">App notes</a> · <a href="#why">Why</a> · <a href="#credits">Credits</a></p>

<p align="center"><sub>Built on the shoulders of giants: huge shout-out to the <a href="https://github.com/MorpheApp/morphe-patches">Morphe Team</a> for creating this beautiful community.</sub></p>

<a id="status"></a>

## 📊 Patch status

| App | What's removed | Version to patch | Updated |
|-----|----------------|------------------|---------|
| 🟢 [Disney+](#disney) | Ads (pre/mid-roll) · pause ads on `≤26.15.2` | [`26.20.0+rc3-2026.11.02`](https://www.apkmirror.com/apk/disney/disney-android-tv/disney-android-tv-26-20-0rc3-2026-11-02-release/) ⚠️ experimental · `26.16.0+rc2-2026.09.08` | 11/7/26 |
| 🟢 [Prime Video](#prime-video) | Movie + TV-show ads (native strip) · `6.23.23` recommended | [`6.23.23+v15.5.0.70-armv7a`](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-23-23v15-5-0-70-armv7a-release/) · ⚠️ experimental: [`6.24.7+v16.0.0.332-allAbis`](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-24-7v16-0-0-332-allabis-release/prime-video-android-tv-6-24-7v16-0-0-332-allabis-android-apk-download/), [`6.24.13+v16.0.1.93-allAbis`](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-24-13v16-0-1-93-allabis-release/prime-video-android-tv-6-24-13v16-0-1-93-allabis-android-apk-download/) | 11/8/26 |
| 🟢 [Netflix](#netflix) | Pre/mid-roll + pause ads · installs as a clone | [`13.0.1 build 25028`](https://www.apkmirror.com/apk/netflix-inc/netflix-android-tv/netflix-android-tv-13-0-1-build-25028-release/) · ⚠️ experimental: [`13.2.1 build 27207`](https://www.apkmirror.com/apk/netflix-inc/netflix-android-tv/netflix-android-tv-13-2-1-build-27207-release/netflix-android-tv-13-2-1-build-27207-android-apk-download/), [`13.2.2 build 27250`](https://www.apkmirror.com/apk/netflix-inc/netflix-android-tv/netflix-android-tv-13-2-2-release/netflix-android-tv-13-2-2-android-apk-download/) | 11/11/26 |
| 🟢 [HBO Max](#hbo-max) | All ads, incl. baked-in SSAI (ad-free stream) | [`7.9.0.61`](https://www.apkmirror.com/apk/warnermedia-direct-llc/hbo-max-stream-movies-tv-android-tv/hbo-max-stream-movies-tv-android-tv-7-9-0-61-release/hbo-max-stream-movies-tv-android-tv-7-9-0-61-android-apk-download/) · ⚠️ experimental: [`7.14.0.69`](https://www.apkmirror.com/apk/warnermedia-direct-llc/hbo-max-stream-movies-tv-android-tv/hbo-max-stream-movies-tv-android-tv-7-14-0-69-release/hbo-max-stream-movies-tv-android-tv-7-14-0-69-android-apk-download/) | 11/7/26 |
| 🟢 [Peacock](#peacock) | VOD ads | `7.10.102` · [`7.8.100`](https://www.apkmirror.com/apk/peacock-tv-llc/peacock-tv-android-tv/peacock-tv-stream-tv-movies-android-tv-7-8-100-apk-release/) | 10/6/26 |
| 🟢 [Tubi](#tubi) | Ads (+ opt-in analytics block) | [`10.40.5000`](https://www.apkmirror.com/apk/tubi-tv/tubi-free-movies-live-tv-android-tv/) | 10/22/26 |
| 🟢 [ViX](#vix) | Ads, incl. server-stitched pre-rolls | [`4.52.0_tv`](https://www.apkmirror.com/apk/univision-communications-inc/vix-cine-y-tv-en-espanol-android-tv/vix-tv-deportes-y-noticias-android-tv-4-52-0_tv-release/) | 11/10/26 |
| 🟢 [Pluto TV](#pluto-tv) | VOD ads · live breaks masked on `5.66` | [`5.66.0-leanback`](https://www.apkmirror.com/apk/pluto-inc/pluto-tv-android-tv/plutotv-stream-free-movies-tv-android-tv-5-66-0-release/plutotv-stream-free-movies-tv-android-tv-5-66-0-leanback-android-apk-download/) · [`17.2.0`](https://www.apkmirror.com/apk/pluto-inc/pluto-tv-android-tv/pluto-tv-watch-free-movies-tv-android-tv-17-2-0-release/pluto-tv-watch-free-movies-tv-android-tv-17-2-0-3-android-apk-download/) | 11/10/26 |
| 🟢 [Paramount+](#paramount) | VOD + pause ads · live untouched | [`16.17.0`](https://www.apkmirror.com/apk/cbs-interactive-inc/paramount-2/paramount-android-tv-16-17-0-release/paramount-android-tv-16-17-0-2-android-apk-download/) · `16.19.0` · ⚠️ experimental: [`17.3.0`](https://www.apkmirror.com/apk/cbs-interactive-inc/paramount-2/paramount-android-tv-17-3-0-release/paramount-android-tv-17-3-0-3-android-apk-download/) | 11/12/26 |
| 🟢 [Twitch](#twitch) | Ad overlay + blanked live ads · TV build only | [`13.0.0.2`](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch-android-tv/twitch-live-streaming-android-tv-13-0-0-2-release/) | 10/28/26 |
| 🟢 [ESPN](#espn) | Live breaks masked (slate + mute) · VOD ads | [`6.11.1`](https://www.apkmirror.com/apk/disney/espn-android-tv/espn-android-tv-6-11-1-release/) | 9/28/26 |
| 🟢 [RTÉ Player](#rte-player) | VOD ads · 🇮🇪 geo-locked | [`3.160.3`](https://www.apkmirror.com/apk/rte/rte-player-android-tv/rte-player-android-tv-3-160-3-release/) | 9/14/26 |
| 🟢 [RaiPlay](#raiplay) | VOD ads · live-channel 404 fix | `5.0.1` · `5.0.0` | 9/27/26 |
| 🟡 [Xumo Play](#xumo-play) | Client-inserted ads (VOD + live) · not yet device-verified | `4.9.147` | 10/29/26 |
| 🟢 [Fawesome](#fawesome) | Pre/mid-roll, banner + pause ads · update prompt | `19.7` | 10/26/26 |
| 🟢 [F1 TV](#f1-tv) | No ads involved: unlocks 4K (UHD) · needs F1 TV Premium | `3.0.49.4-SP166.4.1-release-R54.2-tv` | 10/16/26 |

<sub>🟢 Working &nbsp;·&nbsp; 🟡 Partial / testing &nbsp;·&nbsp; 🔴 Under development / broken &nbsp;·&nbsp; Version links go to the exact Android TV release on APKMirror.</sub>

<a id="install"></a>

## 📥 Install

Every patch uses the same workflow in **Morphe Manager**:

1. Open the app's **version link** in the table above and download the **`.apkm`** bundle. If a release lists more than one, pick the one whose architecture includes **`armeabi-v7a`**.
2. In Morphe Manager, check that **Optimize for device architecture** is **off**.
3. Select the `.apkm` file and apply the patch.

Then read the app's entry under [App notes](#apps). Most apps have one thing that trips people up.

> [!WARNING]
> **Get the Android TV build, at the exact version listed.** Several of these apps ship a separate phone or Fire TV build under the *same* package name, and those patch incorrectly or not at all. Download the `.apkm` (App Bundle), not a single-arch `.apk`.

> [!IMPORTANT]
> **Keep "Optimize for device architecture" off.** It's an Expert-mode setting in Morphe Manager, off by default. When it's on, Manager keeps only the CPU architecture of the device doing the patching. Patch on a phone (arm64), install on a TV box, and the result can be missing the `armeabi-v7a` native libraries that many TV apps and several of these patches (Prime Video, Netflix) depend on. Symptoms: the app won't install, closes on launch, or runs with the ad patch silently doing nothing. Turn the option off and patch again.

<a id="apps"></a>

## 🗂️ App notes

Click an app to expand it.

<a id="disney"></a>
<details>
<summary><b>🎬 Disney+</b> · match the exact <code>rc</code>, and always patch the newest listed version</summary>

- **Match the release candidate exactly.** APKMirror lists several per version, and they are different builds. The current target is `rc3` of [`26.20.0+rc3-2026.11.02`](https://www.apkmirror.com/apk/disney/disney-android-tv/disney-android-tv-26-20-0rc3-2026-11-02-release/).
- **`26.20.0+rc3` is experimental.** It is patched and tested on-device (no pre-roll, no mid-roll markers, no stutter), but has less mileage than `26.16.0+rc2-2026.09.08`, which stays supported if you prefer the older one.
- **Always patch the newest listed version.** Disney enforces a minimum client version server-side. An older build installs and launches normally, then playback stops after about 20 seconds with *"There was a problem — go to our Help Center"*. That is Disney refusing the client, not a broken patch, and no patch can work around it. Don't patch whatever build happens to be on the TV already.

</details>

<a id="prime-video"></a>
<details>
<summary><b>▶️ Prime Video</b> · native ad strip, no DNS · stay on <code>6.23.23</code></summary>

- **Recommended build:** [`6.23.23+v15.5.0.70-armv7a`](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-23-23v15-5-0-70-armv7a-release/) (engine `v15.5.x`). It is `armeabi-v7a` only, so **Optimize for device architecture must be off**.
- **How it works:** a native hook (`libpvhook.so`) strips movie and TV-show pre-rolls and mid-rolls on the device before they play. No DNS blocklist, proxy or root.
- **Keep "Disable auto-updates" enabled** (on by default) so the patched build isn't replaced.
- **v16 (`6.24.x`) is experimental and unconfirmed.** `6.24.7+v16.0.0.332-allAbis` and `6.24.13+v16.0.1.93-allAbis` are supported targets, but no device pass has confirmed them yet. The strip never loaded on v16 because our loader fingerprint required `onCreate` to be exactly `public` and v16 made it `public final`; that is fixed. The hook now builds for both `armeabi-v7a` and `arm64-v8a`, so 64-bit v16 installs get their own hook.
- **Known edge cases:** a rare, very large mid-roll break can briefly freeze (spinner or "Something went wrong"); press **Back**, then **Resume**, to continue ad-free. Very aggressive fast-forward plus resume can occasionally nudge the playback position; it self-heals on a full playthrough. v1.37.4 hardens the strip for high-ad regions (India/EU). Please report anything else in an issue.

Read more: [current write-up and install recommendations](docs/PRIME_VIDEO_ANNOUNCEMENT.md) · [system design teardown](docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md) · [archived Reddit announcement](docs/archive/2026-07_prime-video-reddit-post.md)

</details>

<a id="netflix"></a>
<details>
<summary><b>🍿 Netflix</b> · installs as a clone · keep stock Netflix installed</summary>

Pre-rolls, mid-rolls and the full-screen pause ad are removed in-app by a script the app runs itself at launch. No DNS, PC, root or frida server.

1. Open the [`13.0.1 build 25028`](https://www.apkmirror.com/apk/netflix-inc/netflix-android-tv/netflix-android-tv-13-0-1-build-25028-release/) release (publisher **Netflix, Inc.**, package `com.netflix.ninja`). This is the recommended build. `13.2.1 build 27207` and `13.2.2 build 27250` are also supported as **experimental** targets; other versions, including `13.1.x`, are not.
2. Download the `.apkm` bundle. It is the only variant and is `armeabi-v7a` only, so **Optimize for device architecture must be off**. Morphe Manager shows it as **Recommended**.
3. Apply the patch with **Clone Netflix** and **Remove Netflix ads** enabled (both on by default). Optionally enable **Minimize Network Fingerprint**, which blanks local IP/MAC/SSID and the advertising ID.
4. Install the result **without uninstalling stock Netflix**, then open the new **Netflix clone** and sign in.

- **Two apps, on purpose.** Netflix on a TV is a preinstalled, Netflix-signed system app that can't be replaced without root, so the patch installs as a separate clone (`com.netflix.ninja.clone`). The clone passes Netflix's tamper check by reading the stock app's genuine signature, so stock Netflix must stay installed and enabled. You never open it, and letting it auto-update is fine.
- **Clone closes right after launch?** On some Android 12 TVs (reported on a TiVo Stream 4K and a Philips TV) the default engine crashes shortly after opening. Re-patch with **Remove Netflix ads (legacy engine)** enabled in addition to the defaults. It is opt-in and experimental ([#317](https://github.com/ajstrick81/morphe-androidtv-patches/issues/317)); leave it off if the default works.
- **An ad can occasionally reappear.** Netflix's ad logic is downloaded JavaScript, so Netflix can change ad delivery server-side with no app update. The strip covers several delivery paths and is kept updated. If you see an ad, update to the latest patch first; if it persists, open an issue.
- **Not a subscription bypass.** You need a valid, paid Netflix account and you log in normally.

Read more: [system design teardown](docs/NETFLIX_ATV_SYSTEM_DESIGN.md)

</details>

<a id="hbo-max"></a>
<details>
<summary><b>🎭 HBO Max</b> · fully ad-free by default · check the package name</summary>

- **Keep the defaults.** Since v1.30.0, **Prefer Ad-Free Stream** loads HBO's own ad-free manifest, so pre-rolls, mid-rolls, the ad tier's stitched SSAI ads and the ad markers/countdown are gone on fresh start and resume. No DNS required, and it loads faster.
- **Leave "Block SSAI Ad Origins" off.** It is superseded, and turning it on can make mid-roll breaks fail with error `39999`.
- **Get the right package.** These patches target **`com.wbd.hbomax`**. WarnerMedia also publishes a separate `com.wbd.stream` build under a near-identical name, and APKMirror's listing URLs have shifted between the two before. Confirm the download page shows `com.wbd.hbomax`.
- **Experimental `7.14.0.69`:** tested on a Shield TV by a contributor. The clean combination there is **Disable Ads** plus the opt-in **Remove Ad Periods (keep 5.1)**, which keeps 5.1 audio; **Prefer Ad-Free Stream** also works but plays in stereo. **Block SSAI Ad Origins** hangs playback on this build and is not offered for it.
- **Version:** [`7.9.0.61`](https://www.apkmirror.com/apk/warnermedia-direct-llc/hbo-max-stream-movies-tv-android-tv/hbo-max-stream-movies-tv-android-tv-7-9-0-61-release/hbo-max-stream-movies-tv-android-tv-7-9-0-61-android-apk-download/), or search APKMirror for the fallback `7.7.0.78` if it is unavailable.

</details>

<a id="peacock"></a>
<details>
<summary><b>🦚 Peacock</b> · no DNS · use Clone Peacock on Fire TV and system-app devices</summary>

- **Version:** use the direct [`7.8.100`](https://www.apkmirror.com/apk/peacock-tv-llc/peacock-tv-android-tv/peacock-tv-stream-tv-movies-android-tv-7-8-100-apk-release/) link rather than searching, which can land on a similarly named build. `7.10.102`, `7.5.102` and `7.6.100` are also supported.
- **Bundle:** this release has three. Pick **`arm64-v8a + armeabi-v7a`** (Android 6.0+). The `armeabi-v7a`-only bundle needs Android 12L+, and the `arm64-v8a`-only one won't run on 32-bit TV boxes.
- **Fire TV and preinstalled Peacock:** on many Fire TV sticks and some Android TV boxes Peacock is a system app that can't be uninstalled, and installing over it fails with a signature or `CONFLICTING_PROVIDER` error. Enable the opt-in **Clone Peacock** patch to install side by side under its own package, with its own icon and login. Leave it off if you could fully uninstall stock Peacock first.
- **Disable auto-updates** is on by default and stops the Play Store from replacing the patched build.

</details>

<a id="tubi"></a>
<details>
<summary><b>📺 Tubi</b> · pick <code>10.40.5000</code> from the Android TV listing</summary>

Open the [Tubi (Android TV) listing](https://www.apkmirror.com/apk/tubi-tv/tubi-free-movies-live-tv-android-tv/) and select version **`10.40.5000`**. Use this listing, not "Tubi (Fire TV)" or the phone listing.

</details>

<a id="vix"></a>
<details>
<summary><b>🌐 ViX</b> · use <code>4.52.0_tv</code>; older builds are forced to update</summary>

- **Version:** [`4.52.0_tv`](https://www.apkmirror.com/apk/univision-communications-inc/vix-cine-y-tv-en-espanol-android-tv/vix-tv-deportes-y-noticias-android-tv-4-52-0_tv-release/) (package `com.univision.prendetv`).
- **Two listings.** APKMirror files ViX under two Android TV listings. `4.52.0_tv` is on the one linked above. The older `4.46.0_tv` is on [the other listing](https://www.apkmirror.com/apk/univision-communications-inc/vix-tv-deportes-y-noticias-fire-tv-android-tv/vix-movies-tv-and-sports-in-spanish-fire-tv-android-tv-4-46-0_tv-release/) as a single universal `.apk` (no bundle).

</details>

<a id="pluto-tv"></a>
<details>
<summary><b>📡 Pluto TV</b> · VOD ads removed · live breaks can be masked</summary>

- **Version:** [`5.66.0-leanback`](https://www.apkmirror.com/apk/pluto-inc/pluto-tv-android-tv/plutotv-stream-free-movies-tv-android-tv-5-66-0-release/plutotv-stream-free-movies-tv-android-tv-5-66-0-leanback-android-apk-download/). Pick a **`-leanback`** build, not the phone or Fire TV build.
- **Pluto `17.2.0`:** v17 is a ground-up rebuild of the app, so it has its own patch, **Skip ads (v17)**. Morphe applies whichever generation matches the APK you picked. The live-break mask below does nothing on v17.
- **VOD:** Pluto stitches ads into the content stream server-side (SSAI), so there is no ad domain to block and DNS filters don't help. The patch empties the client-side ad-break timeline, which removes on-demand breaks entirely: ad video, timeline markers, overlays and tracking beacons.
- **Live TV:** live ads are real broadcast time and can't be removed. The optional **Mask live ad breaks** patch covers the player with a black screen and mutes the audio during a break, then restores both the instant the show returns. It reads Pluto's own ad-state signal and works alongside the main **Skip ads** patch.
- **On-device tweaks:** in `Android/data/tv.pluto.android/files/`, create a file named `slate_off` to disable masking without re-patching, or `pluto_slate_mode` containing `black` (cover only) or `mute` (mute only).

Read more: [system design teardown](docs/PLUTO_TV_ATV_SYSTEM_DESIGN.md)

</details>

<a id="paramount"></a>
<details>
<summary><b>⭐ Paramount+</b> · VOD and pause ads removed · live TV preserved</summary>

- **Recommended:** [`16.17.0`](https://www.apkmirror.com/apk/cbs-interactive-inc/paramount-2/paramount-android-tv-16-17-0-release/paramount-android-tv-16-17-0-2-android-apk-download/). VOD ads (movies and TV shows, pre-roll and mid-roll) and pause ads are removed, and live TV is preserved.
- **Also supported:** `16.19.0`, `16.12.0` and `16.8.0`.
- **Experimental `17.3.0`:** device-tested on an onn 4K Plus. VOD movies play with no pre-roll. Pause ads, mid-rolls on TV episodes and live TV are not yet verified on this build.
- **Publisher:** the listing is published by **CBS Interactive, Inc.**, which is the correct one. Do not use the separate Viacom-published build.

</details>

<a id="twitch"></a>
<details>
<summary><b>💜 Twitch</b> · Android TV "Starshot" build <code>13.0.0.2</code> only</summary>

- **Version:** exactly [`13.0.0.2`](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch-android-tv/twitch-live-streaming-android-tv-13-0-0-2-release/), the Android TV "Starshot" build. The phone and Fire TV builds are not supported. The release has two bundles and either works; **`arm64-v8a + armeabi-v7a`** is the safe pick.
- **What it does:** Twitch live ads are server-side stitched (SSAI) and the TV app runs Twitch's web player in a WebView. The patch intercepts the live HLS playlist, strips the ad-pod tags behind the on-screen "Ad · 1 of 3" countdown, and blanks the stitched ad video. DNS filters don't help here.
- **A brief black gap can remain** during a break, because the ad occupies real live stream time that still has to pass.
- **Playback speed controls** are available as an opt-in patch.
- **Want zero ads and no gap?** Point a VPN at **Albania**. Twitch serves that region ad-free playlists. Verified first-hand with NordVPN.

</details>

<a id="espn"></a>
<details>
<summary><b>🏈 ESPN</b> · live breaks masked with a slate · VOD ads suppressed</summary>

- **Version:** exactly [`6.11.1`](https://www.apkmirror.com/apk/disney/espn-android-tv/espn-android-tv-6-11-1-release/) (package `com.espn.score_center`), Android TV only. Use the link rather than searching, to avoid the phone build.
- **Live:** ESPN live breaks are passthrough SSAI, stitched into the same stream as the game, so they can only be masked. The patch detects each break from the stream's own ad markers, covers the player with a full-screen slate and mutes it, then lifts cleanly when live content resumes. DNS filters don't help here.

| Patch | Default | What it does |
| --- | --- | --- |
| **ESPN Android TV** | ✅ On (core) | Suppresses VOD / scheduled ads by forcing the DMP SGAI `isAdDisabled` flag. Always keep this on. |
| **ESPN live commercial-break slate** | ✅ On | Masks live passthrough-SSAI breaks with a full-screen slate + mute. Deselect for VOD ad suppression only. |
| **Disable auto-updates** | ✅ On | Stops the Play Store from reinstalling the official ESPN and wiping the patch. Update by re-patching a newer APK. |
| **Clone ESPN** | ⬜ Off (opt-in) | Installs alongside a stock ESPN that can't be uninstalled (common on Amazon Fire TV and some TV boxes), with its own package and icon. |

**Slate styles:** while a break is up, press the D-pad to open a picker and switch between **Video** (your own clips), **Video + Score** (clips plus a live-score strip), **Be Right Back** (an animated broadcast graphic with a live countdown) and **Blank**. Slate media is user-supplied local files in the app's external files dir; nothing is bundled. Drop a `slate_off` marker file there to disable the live slate without re-patching. Overlay assets and setup: [`docs/slates/espn_overlay/`](docs/slates/espn_overlay/).

</details>

<a id="rte-player"></a>
<details>
<summary><b>🇮🇪 RTÉ Player</b> · VOD only · geo-locked to Ireland</summary>

- **Version:** [`3.160.3`](https://www.apkmirror.com/apk/rte/rte-player-android-tv/rte-player-android-tv-3-160-3-release/).
- **VOD:** client-side (Google IMA) pre-roll and mid-roll ads are removed in-app. Content plays straight through where a break would be. No DNS filter needed. A cosmetic ad-break marker may still appear on the scrubber, but it no longer pauses playback.
- **Live channels still show ads.** Live is server-stitched (DAI) into the video itself and can't be removed client-side without breaking the stream.
- **Geo-locked.** RTÉ Player only streams from an Irish IP, so you need a VPN set to Ireland and a valid RTÉ account. This removes ads; it is not an access bypass.

</details>

<a id="raiplay"></a>
<details>
<summary><b>🎞️ RaiPlay</b> · VOD ads removed · live-channel fix included</summary>

- **Versions:** `5.0.1` or `5.0.0`. Support for `5.0.1` was contributed by [Michael Bompani](https://github.com/michaelbompani).
- **Skip ads:** every title takes RaiPlay's own ad-free playback path, so there are no pre-roll, mid-roll or post-roll breaks on VOD. Live-channel server-side ads, if any, are not affected.
- **Fix live HLS variant 404** (on by default): fixes live channels such as Rai 2 that fail with an error toast.

</details>

<a id="xumo-play"></a>
<details>
<summary><b>📼 Xumo Play</b> · client-inserted ads · not yet verified on a device</summary>

- **Version:** `4.9.147`.
- **Skip ads:** removes Xumo's client-inserted ads in both the VOD and Live TV players, so content continues where an ad would play.
- **Limits:** ads that are server-stitched into the stream (FreeWheel SSAI) can't be removed client-side and may remain. Xumo is moving toward SSAI, so coverage can vary by title and region.
- **Status:** not yet device-verified on `4.9.147`. Please report results in an issue.

</details>

<a id="fawesome"></a>
<details>
<summary><b>🎥 Fawesome</b> · all client-side ads removed · stay on <code>19.7</code></summary>

- **Version:** `19.7`. The ad patch does not work on `20.1`.
- **Skip ads:** removes the pre-roll and mid-roll video ads, the in-player banner and the pause-screen ad. Ads are requested client-side, so this is a full removal.
- **Disable update prompt** (on by default): stops the "New version Available" dialog on every launch. This one also works on `20.1`.

</details>

<a id="f1-tv"></a>
<details>
<summary><b>🏎️ F1 TV</b> · unlocks 4K (UHD) · needs F1 TV Premium</summary>

- **Version:** `3.0.49.4-SP166.4.1-release-R54.2-tv`.
- **Unlock UHD (4K):** removes the client-side device-capability gates so the 2160p tier is offered on all Android TV hardware.
- **Not an entitlement bypass.** It requires a paid F1 TV Premium subscription and only changes which video tier the app's own checks allow.

</details>

<a id="why"></a>

## 🧭 Why this exists

I'm just like you — I enjoy watching TV and movies without being bored and annoyed to death by ads. I'll try to keep this repo updated when I can. I only do it for fun and I'm trying my best to make your viewing experience as enjoyable as possible :)

> **You bought the TV. You control the TV. You own the glass.**
>
> — Rory Gallagher, [lg-webos-dashboard](https://github.com/rorygallagher2024/lg-webos-dashboard) (MIT). This project shares inspiration and is aligned with Rory's work.

If you own the TV and pay for the service, what plays on your screen should be your call, not an ad network's.

| | |
|---|---|
| 📺 **Your screen, your choice** | Ads, pause-screen promos and ad-tracking beacons are removed or masked, so the show you picked is what plays. |
| 🔍 **Local and transparent** | Everything runs on your TV, inside the patched app. The source is open, and the [seam map](docs/SEAM_MAP.md) shows what each patch changes. |
| 🚫 **No data collection** | The patches don't phone home, and nothing is sent to this project. Several of them cut the apps' own ad-tracking calls. |
| 🏷️ **No dark patterns** | Optional features stay optional and are clearly labeled. |
| 🔒 **No weakened security by default** | Anything that lowers the app's own protections (such as trusting user-installed CAs for HTTPS inspection) is off by default unless an ad-removal method needs it (today only ViX live, with AdGuard Premium), and its description says so. |

<details>
<summary><b>What the patches can't reach</b></summary>

They work inside the patched app only. The TV's own operating system can still watch and report on its own: TV-level Automatic Content Recognition (ACR), voice data, ad IDs and home-network scanning ([AdGuard's write-up on LG TVs](https://adguard-dns.io/en/blog/lg-smart-tv-spying-privacy.html) shows how far that goes). For that layer, turn off ACR / "viewing information" and personalized ads in the TV's settings, filter the TV's DNS (AdGuard DNS or AdGuard Home), and keep the TV on its own network or VLAN, away from your computers and phones.

</details>

<a id="credits"></a>

## 🙏 Credits

This patch template is based on the prior work of [ReVanced](https://github.com/ReVanced/revanced-patches-template). All modifications made by Morphe, along with their dates, can be found in the Git history.

**Community contributions** — code written by people who forked this repo, ported here with their authorship preserved. Thank you for your time and hard work:
- [Michael Bompani](https://github.com/michaelbompani) — RaiPlay 5.0.1 support and the live-channel (Rai 2) 404 fix
- [Joker4Life12](https://github.com/Joker4Life12) — HBO Max "Remove Ad Periods (keep 5.1)": keeps the PRIMARY 5.1 stream and strips its stitched ad periods instead of the stereo-only fallback

<details>
<summary><b>Prior art and references</b> (Twitch, Netflix, ESPN)</summary>

**Twitch ad-suppression techniques** are informed by:
- [Purple TV](https://github.com/AdrianLxM/PurpleTV) (nyanarchive/purpletv) — GrandDads ad-eligibility short-circuit approach
- [TwitchAdSolutions](https://github.com/pixeltris/TwitchAdSolutions) (pixeltris) — playerType spoofing concept
- [Xtra for Twitch](https://github.com/crackededed/Xtra) (crackededed) — Android-side Twitch ad mitigation prior art

All Twitch techniques were independently re-derived via dex disassembly and are not copied from these projects' source code. See [NOTICE](NOTICE) for full attribution details.

**Netflix household-prompt suppression** is informed by:
- [Nikflix](https://github.com/YidirK/Nikflix) (YidirK, GPL-3.0) — identified Netflix's household / "you're traveling" enforcement via the `CLCSInterstitialPlaybackAndPostPlayback` interstitial

Our "Suppress Household Prompt" patch shares no source code with Nikflix — it is an independent implementation for the Android TV app (`com.netflix.ninja`), located by analyzing our own on-device appboot heap dumps. Nikflix is credited for identifying the enforcement seam. See [NOTICE](NOTICE).

**ESPN live-score slate** (optional scoreboard overlay) uses ESPN's public "hidden" stats API, documented by the community:
- [pseudo-r/Public-ESPN-API](https://github.com/pseudo-r/Public-ESPN-API)
- [akeaswaran's ESPN hidden-API gist](https://gist.github.com/akeaswaran/b48b02f1c94f873c6655e7129910fc3b)
- [cwendt94/espn-api](https://github.com/cwendt94/espn-api)
- [quantum0813/ESPNSportsAPI](https://github.com/quantum0813/ESPNSportsAPI)
- [ITIRadio/ESPN-API](https://github.com/ITIRadio/ESPN-API)

We use only the publicly documented, unauthenticated scoreboard endpoints for read-only live scores; no source code from these projects is included. Thanks to their maintainers.

</details>

## 📜 License

`ajstrick81` Morphe Patches are licensed under the [GNU General Public License v3.0](LICENSE).
