# 📺 Morphe Patches — Android TV

> **Built on the Shoulders of Giants**
> 
> Huge shout-out to the [Morphe Team](https://github.com/MorpheApp/morphe-patches) for creating this beautiful community

---

## ❓ About

I'm just like you — I enjoy watching TV and movies without being bored and annoyed to death by ads. I'll try to keep this repo updated when I can. I only do it for fun and I'm trying my best to make your viewing experience as enjoyable as possible :)

---

## 🧭 Philosophy

> **You bought the TV. You control the TV. You own the glass.**
>
> — Rory Gallagher, [lg-webos-dashboard](https://github.com/rorygallagher2024/lg-webos-dashboard) (MIT). This project shares inspiration and is aligned with Rory's work.

That line is the reason this project exists. If you own the TV and pay for the service, what plays on your screen should be your call, not an ad network's. These patches hand that choice back to you:

- **Your screen, your choice.** Ads, pause-screen promos and ad-tracking beacons are removed or masked, so the show you picked is what plays.
- **Local and transparent.** Everything runs on your TV, inside the patched app. The source is open, and the [seam map](docs/SEAM_MAP.md) shows what each patch changes.
- **No data collection.** The patches don't phone home, and nothing is sent to this project. Several of them cut the apps' own ad-tracking calls.
- **No dark patterns.** Optional features stay optional and are clearly labeled.

---

## 📊 Patch Status

| App | Package | Status | Tested Version | Date |
|-----|---------|--------|---------------|------|
| 🟢 Disney+ | `com.disney.disneyplus` | Working | `26.16.0+rc2-2026.09.08` | 9/14/26 |
| 🟢 Prime Video | `com.amazon.amazonvideo.livingroom` | Working — native in-app ad strip (movies + TV shows), no DNS required. ⚠️ **Use `6.23.23` / engine `v15.5.x`; do NOT update to `6.24.x` (engine `v16`)** — v16 moved the ad pipeline into the native engine, so ads return there ([#120](https://github.com/ajstrick81/morphe-androidtv-patches/issues/120)). Keep **auto-updates disabled**. **v1.37.4** hardens the strip for high-ad regions (India/EU), where large mid-roll ad responses split across memory chunks. A rare, very large mid-roll break can still briefly freeze (spinner / "Something went wrong") — press **Back**, then **Resume**, to continue ad-free | `6.23.23+v15.5.0.70-armv7a` | 9/20/26 |
| 🟢 Netflix | `com.netflix.ninja` | Working — native in-app ad strip (pre-roll, mid-roll, pause-screen ad), no DNS required. Installs as a **side-by-side clone**; keep stock Netflix installed | `13.0.1 build 25028` | 9/14/26 |
| 🟢 HBO Max | `com.wbd.hbomax` | Working — **fully ad-free by default** (v1.30.0). The **Prefer Ad-Free Stream** patch loads HBO's own ad-free manifest (its resiliency-fallback stream), so pre-rolls, mid-rolls, and even the ad-tier's stitched **SSAI baked-in ads** are gone — along with the ad markers/countdown — on both fresh start and resume, and it loads faster. No DNS required. (Legacy opt-in **Block SSAI Ad Origins** is now off by default and superseded.) | `v7.9.0.61` | 8/26/26 |
| 🟢 Peacock | `com.peacocktv.peacockandroid` | Working — no DNS required | `v7.8.100` | 9/6/26 |
| 🟢 Tubi | `com.tubitv` | Working | `v10.28.5000` | 7/20/26 |
| 🟢 ViX | `com.univision.prendetv` | Working | `v4.47.2_tv` | 7/11/26 |
| 🟢 Pluto TV | `tv.pluto.android` | Working — VOD ad breaks removed (video, markers, beacons); LIVE TV breaks maskable (black screen + mute) via optional patch | `5.66.0-leanback` | 9/7/26 |
| 🟢 Paramount+ | `com.cbs.ott` | Working — VOD ads removed (movies + TV shows, pre-roll + mid-roll); pause ads removed; live TV preserved | `v16.17.0` | 8/4/26 |
| 🟢 Twitch | `tv.twitch.android.app` | Working — **Android TV "Starshot" build only; install exactly `13.0.0.2`** (the phone app is not supported — do not use the phone APK). Removes the on-screen ad-pod overlay/countdown ("Ad · 1 of 3") and blanks stitched (SSAI) ad video on live streams. A brief black gap can remain during a break; a VPN set to Albania is fully ad-free — see notes | `13.0.0.2` | 8/22/26 |
| 🟢 ESPN | `com.espn.score_center` | Working — **Android TV** only. Live commercial breaks masked with a full-screen slate + audio mute (passthrough SSAI can't be removed, only covered); VOD/scheduled ads suppressed. No DNS required | `6.11.1` | 9/5/26 |
| 🟢 RTÉ Player | `com.twentyfouri.tvbridge.rte` | Working — **Android TV, VOD only**. Client-side (Google IMA) pre-roll + mid-roll ads removed seamlessly (no ad, no break pause). Live channels keep ads (server-stitched). **Geo-locked to Ireland — use a VPN**; needs a valid RTÉ account | `3.160.3` | 9/14/26 |
| 🔴 Fox One | **Under Development** | — |
| 🔴 MLB TV | **Under Development** | — |

> 🟢 Working &nbsp;&nbsp; 🟡 Partial/Testing &nbsp;&nbsp; 🔴 Under Development / Broken

---

## 📥 How to Install

All patches follow the same general workflow using **Morphe Manager**:

1. Download the correct **Android TV** `.apkm` from APKMirror — use the direct links below
2. In Morphe Manager, make sure **Optimize for device architecture is OFF** (see below)
3. Open Morphe Manager and select the `.apkm` file
4. Apply the patch

> ⚠️ **Get the Android TV build, not the phone or Fire TV build.** Each direct
> link below goes to the **exact Android TV release** on APKMirror. Several of
> these apps ship a separate phone build and/or a Fire TV build under the *same*
> package name — those will patch incorrectly or not at all. Match the **exact
> version** named below and download the **`.apkm`** (App Bundle), not a
> single-arch `.apk`. When a release lists more than one bundle, pick the one
> whose architecture column includes **`armeabi-v7a`**.

> 🛑 **Turn OFF "Optimize for device architecture" before patching.**
> It's an Expert-mode setting in Morphe Manager (off by default). When it's on,
> Manager keeps only the CPU architecture of **the device doing the patching** and
> throws the rest away. If you patch on a phone (arm64) and install on a TV box,
> the result can be missing the **`armeabi-v7a`** native libraries that many TV
> apps — and several of these patches (Prime Video, Netflix) — depend on. Typical
> symptoms: the app won't install, closes immediately on launch, or runs with the
> ad patch silently doing nothing. If that happens, turn the option off and patch
> again. (Patching on the TV itself is unaffected, but leaving it off is always safe.)

---

### 🎬 Disney+

1. Open the **[Disney+ (Android TV) 26.16.0+rc2-2026.09.08 release on APKMirror](https://www.apkmirror.com/apk/disney/disney-android-tv/disney-android-tv-26-16-0rc2-2026-09-08-release/)** (version **`26.16.0+rc2-2026.09.08`**)
   > ⚠️ APKMirror also lists **`26.16.0+rc1`** — that is a different build. Use **rc2**.
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

---

### 🎭 HBO Max

1. Open the **[HBO Max 7.9.0.61 (Android TV) release on APKMirror](https://www.apkmirror.com/apk/warnermedia-direct-llc/hbo-max-stream-movies-tv-android-tv/hbo-max-stream-movies-tv-android-tv-7-9-0-61-release/hbo-max-stream-movies-tv-android-tv-7-9-0-61-android-apk-download/)** directly (or search APKMirror for the fallback `7.7.0.78` if unavailable)
   > ⚠️ **Get the right package.** These patches target **`com.wbd.hbomax`**. WarnerMedia also publishes a separate **`com.wbd.stream`** build under a near-identical name, and APKMirror's listing URLs for this app have shifted between the two before — don't trust the listing title alone. Confirm the download page shows package **`com.wbd.hbomax`** before downloading.
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch
   > 💡 **Keep the defaults.** Since v1.30.0 the default **Prefer Ad-Free Stream** patch loads HBO's own ad-free stream, which removes the stitched (SSAI) ad video too. Leave the legacy opt-in **Block SSAI Ad Origins** patch **off** — it is superseded, and turning it on can make mid-roll breaks fail with error `39999`.

---

### ▶️ Prime Video

1. Open the **[Prime Video (Android TV) 6.23.23+v15.5.0.70-armv7a release on APKMirror](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-23-23v15-5-0-70-armv7a-release/)** (version **`6.23.23+v15.5.0.70-armv7a`** — `armeabi-v7a` only, so **Optimize for device architecture must be OFF**)
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

> 🟢 **Working — no DNS filter needed.** Ads are stripped **in-app** by a native
> hook (`libpvhook.so`), so movies and TV shows play ad-free without any external
> DNS blocklist, proxy, or root. Movie prerolls/mid-rolls and TV-show
> prerolls/mid-rolls are all removed on the device before they play.
>
> ⚠️ **Stay on `6.23.23` (engine `v15.5.x`) — do NOT update to `6.24.x` (engine
> `v16`).** In v16 Amazon moved the ad pipeline into the app's native engine, out
> of reach of the current strip, so **ads return on 6.24.x** ([#120](https://github.com/ajstrick81/morphe-androidtv-patches/issues/120)).
> Enable **Disable auto-updates** when patching (on by default) so Amazon can't
> push you to v16. If you're already on 6.24.x, uninstall and reinstall `6.23.23`.
>
> TV-show ad removal is newly shipped (v1.16.0) and in wider testing. One known
> edge case: very aggressive fast-forward + resume can occasionally nudge the
> playback position; it self-heals on a full playthrough and normal viewing is
> unaffected. Please report anything else via an issue.
>
> 📄 **Full write-up:** see [`docs/PRIME_VIDEO_ANNOUNCEMENT.md`](docs/PRIME_VIDEO_ANNOUNCEMENT.md)
> for how the current native (`libpvhook.so`) approach works and complete install
> recommendations. The original Reddit announcement is archived for transparency at
> [`docs/archive/2026-07_prime-video-reddit-post.md`](docs/archive/2026-07_prime-video-reddit-post.md).
>
> 🔬 **How it works under the hood:** [`docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md`](docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md)
> is a reverse-engineering teardown of Prime Video's ad and playback system, and of why the patch works.

---

### 🍿 Netflix

> 🟢 **Working — no DNS filter needed.** Pre-rolls, mid-rolls, **and** the
> full-screen pause-screen ad are removed **in-app** by an in-process script that
> the app runs itself at launch (no PC, no root, no frida server). Verified
> on-device: Netflix's servers still deliver real ad breaks and none of them play.
>
> ℹ️ **Netflix's ad logic is downloaded JavaScript, not baked into the APK.** That
> means Netflix can change how ads are delivered server-side at any time, with no
> app update — so an ad can occasionally reappear until the in-app strip is
> re-pointed at the new delivery path. The strip already covers several such paths
> (dynamic server-side insertion **and** the legacy manifest model) and is kept
> updated to match. If you ever see an ad, first make sure you're on the latest
> patch; if it persists, please open an issue.
>
> 🔐 **Two apps, on purpose — keep BOTH installed.** Netflix on a TV is a
> preinstalled, Netflix-signed **system app** that can't be replaced or uninstalled
> without root, so the patch installs as a **separate clone**
> (`com.netflix.ninja.clone`) next to it. The clone passes Netflix's built-in
> tamper check by reading the **stock** app's genuine signature — so **stock
> Netflix must stay installed and enabled** (you just never open it; letting it
> auto-update is fine). Log into the **clone** and use that. Don't disable or
> uninstall stock Netflix, or the clone won't start.

1. Open the **[Netflix (Android TV) 13.0.1 build 25028 release on APKMirror](https://www.apkmirror.com/apk/netflix-inc/netflix-android-tv/netflix-android-tv-13-0-1-build-25028-release/)** (publisher **Netflix, Inc.**, package `com.netflix.ninja`, version **`13.0.1 build 25028`**)
2. Download the `.apkm` bundle — it is the only variant, **`armeabi-v7a`** only (base + one arch split), so **Optimize for device architecture must be OFF**. Morphe Manager will show it as **Recommended**. Don't pick a newer `13.1.x` release — the patch targets `13.0.1 build 25028`.
3. Select the `.apk` in Morphe Manager
4. Apply the patch — leave **Clone Netflix** and the **Remove Netflix ads** patches enabled (both on by default). Optionally enable **Minimize Network Fingerprint** for the privacy pass (blanks local IP/MAC/SSID + advertising ID).
5. Install the result **without uninstalling stock Netflix**, then open the new **Netflix clone** app and sign in.

> ⚠️ **Not a subscription bypass.** You need a valid, paid Netflix account and you
> log in normally. This only removes ads and trims device telemetry inside an app
> you're already entitled to use.
>
> 🔬 **How it works under the hood:** [`docs/NETFLIX_ATV_SYSTEM_DESIGN.md`](docs/NETFLIX_ATV_SYSTEM_DESIGN.md)
> is a reverse-engineering teardown of Netflix's ATV ad and playback system, and of why the patch works.

---

### 📡 Paramount+

> 🟢 **Recommended version: `v16.17.0`.** VOD ads (movies **and** TV shows,
> pre-roll and mid-roll) and pause ads are removed, while **live TV is
> preserved**. This is the version to patch and install right now.
>
> 🟡 **Stable fallback: `v16.8.0`.** The previous recommended build. Use it only
> if you can't get `v16.17.0`.

1. Open the **[Paramount+ 16.17.0 (Android TV) release on APKMirror](https://www.apkmirror.com/apk/cbs-interactive-inc/paramount-2/paramount-android-tv-16-17-0-release/paramount-android-tv-16-17-0-2-android-apk-download/)** directly (or search APKMirror for the fallback `16.8.0` if unavailable)
2. ⚠️ This listing is published by **CBS Interactive, Inc.** — the correct publisher. Do **not** use the separate Viacom-published build.
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 📺 Tubi

1. Open the **[Tubi (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/tubi-tv/tubi-free-movies-live-tv-android-tv/)** and select version **`10.28.5000`**
2. ⚠️ Use this **Android TV** listing — not the "Tubi (Fire TV)" or the phone listing
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 🌐 ViX

1. Open the **[ViX: TV, Deportes y Noticias (Android TV) 4.47.2_tv release on APKMirror](https://www.apkmirror.com/apk/univision-communications-inc/vix-cine-y-tv-en-espanol-android-tv/vix-tv-deportes-y-noticias-android-tv-4-47-2_tv-release/)** (version **`4.47.2_tv`**, package `com.univision.prendetv`)
   > ⚠️ APKMirror files ViX under **two** Android TV listings. `4.47.2_tv` is only on the one linked above — the "ViX (Fire TV) (Android TV)" listing skips it. The fallback **`4.46.0_tv`** is on [that other listing](https://www.apkmirror.com/apk/univision-communications-inc/vix-tv-deportes-y-noticias-fire-tv-android-tv/vix-movies-tv-and-sports-in-spanish-fire-tv-android-tv-4-46-0_tv-release/) as a single universal `.apk` (no bundle).
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

---

### 📡 Pluto TV

> 🟢 **Working.** Pluto is a free, 100% ad-supported (FAST) service that uses
> **server-side ad stitching (SSAI)** — ads are baked into the same stream as
> the content, so there is no ad domain to block and no ad-free tier to unlock.
> This patch empties the client-side ad-break timeline, which removes **on-demand
> (VOD)** ad breaks entirely: ad video, timeline markers, overlays, and tracking
> beacons. **LIVE TV** ads are real broadcast time in the linear feed and cannot be
> *removed* — but the separate **Mask live ad breaks** patch *hides* them (see below).
> DNS filters do **not** help here.

1. Open the **[Pluto TV (Android TV) 5.66.0-leanback download on APKMirror](https://www.apkmirror.com/apk/pluto-inc/pluto-tv-android-tv/plutotv-stream-free-movies-tv-android-tv-5-66-0-release/plutotv-stream-free-movies-tv-android-tv-5-66-0-leanback-android-apk-download/)** (version **`5.66.0-leanback`**) — the listing now also shows newer `17.x` releases; the patch targets `5.66.0-leanback`
2. ⚠️ Use this **Android TV** listing and pick a **`-leanback`** build — not the phone or Fire TV build
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

> 📺 **Mask live ad breaks (black screen + mute).** Live/linear Pluto ads occupy real
> broadcast time and can't be deleted like VOD ads, so this optional patch **masks** the
> break instead: during a live commercial it covers the player with a **black screen** and
> **mutes** the audio, then restores both the instant the show returns — so gambling,
> drinking, or any other live ad is blanked and silenced. It reads Pluto's own ad-state
> signal, so it needs no polling and works alongside the main **Skip ads** patch. It's
> **opt-out** (leave it off to watch live ads normally). Runtime tweaks via marker files in
> the app's external files dir (`Android/data/tv.pluto.android/files/`): create **`slate_off`**
> to disable it on-device without re-patching, or **`pluto_slate_mode`** containing `black`
> (cover only) or `mute` (mute only) instead of the default both.
>
> 🔬 **How it works under the hood:** [`docs/PLUTO_TV_ATV_SYSTEM_DESIGN.md`](docs/PLUTO_TV_ATV_SYSTEM_DESIGN.md)
> is a reverse-engineering teardown of Pluto TV's ad and playback system, and of why the patch works.

---

### 🦚 Peacock

1. Open the **[Peacock TV (Android TV) 7.8.100 release on APKMirror](https://www.apkmirror.com/apk/peacock-tv-llc/peacock-tv-android-tv/peacock-tv-stream-tv-movies-android-tv-7-8-100-apk-release/)** directly (this is version **`7.8.100`** — use this link rather than searching, which can land on a similarly-named build). Versions **`7.5.102`** and **`7.6.100`** are also supported.
   > This release has three bundles — pick **`arm64-v8a + armeabi-v7a`** (Android 6.0+). The `armeabi-v7a`-only bundle needs Android 12L+, and the `arm64-v8a`-only one won't run on 32-bit TV boxes.
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

> ⚠️ **Fire TV & preinstalled/system-app devices: enable the `Clone Peacock`
> patch before applying.** On many Fire TV sticks and some Android TV boxes,
> Peacock ships as a **system app that can't be uninstalled**. Installing the
> patched build over it fails with a signature or `CONFLICTING_PROVIDER` error.
> The **Clone Peacock** patch (opt-in, off by default) installs the patched app
> side-by-side under its own package, so it appears as a second Peacock icon and
> keeps its own login. Leave it **off** only if you were able to fully uninstall
> the stock Peacock first (a normal in-place install is cleaner). The
> **Disable auto-updates** patch is on by default and stops the Play Store from
> silently replacing the patched build.

---

### 💜 Twitch

> 🟢 **Working — Android TV "Starshot" build only (`13.0.0.2`).** Twitch live ads
> are **server-side stitched (SSAI)** into the same stream as the content, and the
> TV app runs Twitch's web player inside a WebView. This patch intercepts the live
> HLS playlist and both **(a)** strips the client-side ad-pod tags that drive the
> on-screen **"Ad · 1 of 3"** countdown/overlay and **(b)** blanks the stitched ad
> video. Real ad breaks no longer play as an ad. Because the ad occupies real
> **live** stream time, a brief **black gap** can remain during a break — the ad
> video is gone, but that slice of live time still has to pass. DNS filters do
> **not** help here.
>
> 💡 **Want zero ads and no black gap?** Point a VPN at **Albania** — Twitch serves
> that region genuinely ad-free playlists (no ad is stitched at all). Verified
> first-hand with **NordVPN → Albania**; pair it with the patch and you're fully
> covered.

1. Open the **[Twitch: Live Streaming (Android TV) 13.0.0.2 release on APKMirror](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch-android-tv/twitch-live-streaming-android-tv-13-0-0-2-release/)** (version **`13.0.0.2`**)
2. ⚠️ Use this **Android TV** listing and the exact **`13.0.0.2`** "Starshot" build — not the phone or Fire TV build (the patch targets this TV build specifically). The release has two bundles; either works — **`arm64-v8a + armeabi-v7a`** is the safe pick.
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 🏈 ESPN

> 🟢 **Working — Android TV only (`6.11.1`).** ESPN **live** commercial breaks are
> native **passthrough SSAI** — the ad is stitched into the same stream as the game,
> so it cannot be removed, only **masked**. This patch detects each break from the
> stream's own ad markers and covers the player with a full-screen **slate** while
> **muting** the player, then lifts cleanly when live content resumes. **VOD /
> scheduled** ads are separately suppressed. DNS filters do **not** help here.

1. Open the **[ESPN (Android TV) 6.11.1 release on APKMirror](https://www.apkmirror.com/apk/disney/espn-android-tv/espn-android-tv-6-11-1-release/)** directly (version **`6.11.1`**) — use this link rather than searching, to avoid landing on the phone build or a different version
2. ⚠️ Use this **Android TV** listing and the exact **`6.11.1`** build (package `com.espn.score_center`)
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Choose your patch options (below) and apply

**Patch options**

| Patch | Default | What it does |
| --- | --- | --- |
| **ESPN Android TV** | ✅ On (core) | Suppresses VOD / scheduled ads by forcing the DMP SGAI `isAdDisabled` flag. Always keep this on. |
| **ESPN live commercial-break slate** | ✅ On | Masks **live** passthrough-SSAI breaks with a full-screen slate + mute. Deselect if you only want VOD ad suppression and no on-screen slate during live breaks. |
| **Disable auto-updates** | ✅ On | Stops the Play Store from silently reinstalling the official ESPN and wiping the patch. Update deliberately by re-patching a newer APK. |
| **Clone ESPN** | ⬜ Off (opt-in) | Installs the patched app **alongside** a stock ESPN that can't be uninstalled (common on **Amazon Fire TV** and some TV boxes). Gives it its own package/icon. Leave off if you could uninstall the original first. |

**Live-slate styles (on-device):** while a break is up, press the D-pad to open an on-screen picker and switch between **Video** (your own clips), **Video + Score** (clips + a live-score strip), **Be Right Back (ESPN Ad-Break Overlay)** (an animated broadcast graphic with a live countdown), and **Blank**. Slate media is user-supplied local files placed in the app's external files dir — nothing is bundled. Drop a `slate_off` marker file to disable the live slate without re-patching. See [`slates/espn_overlay/`](slates/espn_overlay/) for the animated overlay assets and setup.

---

### 📺 RTÉ Player

1. Open the **[RTÉ Player (Android TV) 3.160.3 release on APKMirror](https://www.apkmirror.com/apk/rte/rte-player-android-tv/rte-player-android-tv-3-160-3-release/)** (version **`3.160.3`**)
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

> 🟢 **Working — Android TV, VOD only.** Client-side (Google IMA) **pre-roll and mid-roll** ads are removed **in-app** — content plays straight through where a break would be, with no ad and no pause. No DNS filter needed.
>
> ⚠️ **Geo-locked to Ireland.** RTÉ Player only streams from an Irish IP, so you need a **VPN set to Ireland** (and a valid RTÉ account — this only removes ads, it's not an access bypass).
>
> ℹ️ **Live channels still show ads.** Live is server-stitched (DAI) into the video itself, which can't be removed client-side without breaking the stream — only on-demand (VOD) is covered. A cosmetic ad-break marker may still appear on the scrubber, but it no longer pauses playback.

---

### 🦊 Fox One

> 🔴 **Currently under development.** Install instructions will be added once a stable patch is available.

---

## 🙏 Credits

This patch template is based on the prior work of [ReVanced](https://github.com/ReVanced/revanced-patches-template). All modifications made by Morphe, along with their dates, can be found in the Git history.

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

---

## 📜 License

`ajstrick81` Morphe Patches are licensed under the [GNU General Public License v3.0](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/LICENSE).
