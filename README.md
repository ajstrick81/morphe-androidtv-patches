# 📺 Morphe Patches — Android TV

> **Built on the Shoulders of Giants**
> 
> Huge shout-out to the [Morphe Team](https://github.com/MorpheApp/morphe-patches) for creating this beautiful community

---

## ❓ About

I'm just like you — I enjoy watching TV and movies without being bored and annoyed to death by ads. I'll try to keep this repo updated when I can. I only do it for fun and I'm trying my best to make your viewing experience as enjoyable as possible :)

---

## 📊 Patch Status

| App | Package | Status | Tested Version | Date |
|-----|---------|--------|---------------|------|
| 🟢 Disney+ | `com.disney.disneyplus` | Working | `26.9.2+rc1-2026.06.12` | 6/17/26 |
| 🟡 Prime Video | `com.amazon.amazonvideo.livingroom` | Partial/Testing — [DNS filters](dns/README.md) required; native prerolls may still appear | `6.23.23+v15.5.0.70-armv7a` | 6/26/26 |
| 🟢 HBO Max | `com.wbd.hbomax` | Working | `v7.7.0.78` | 7/18/26 |
| 🟢 Peacock | `com.peacocktv.peacockandroid` | Working — no DNS required | `v7.6.100` | 7/16/26 |
| 🟢 Tubi | `com.tubitv` | Working | `v10.20.5000` | 5/20/26 |
| 🟢 ViX | `com.univision.prendetv` | Working | `v4.47.2_tv` | 7/11/26 |
| 🟢 Pluto TV | `tv.pluto.android` | Working — VOD ad breaks removed (video, markers, beacons); LIVE TV ads are broadcast time and remain | `5.66.0-leanback` | 7/3/26 |
| 🔴 Paramount+ | `com.cbs.ott` | In Development | `v16.8 → v16.12` | — |
| 🔴 Fox One | **Under Development** | — |
| 🔴 MLB TV | **Under Development** | — |

> 🟢 Working &nbsp;&nbsp; 🟡 Partial/Testing &nbsp;&nbsp; 🔴 Under Development / Broken

---

## 📥 How to Install

All patches follow the same general workflow using **Morphe Manager**:

1. Download the correct **Android TV** `.apkm` from APKMirror — use the direct links below
2. Open Morphe Manager and select the `.apkm` file
3. Apply the patch

> ⚠️ **Get the Android TV build, not the phone or Fire TV build.** Each direct
> link below points at the app's **Android TV** listing on APKMirror. Several of
> these apps ship a separate phone build and/or a Fire TV build under the *same*
> package name — those will patch incorrectly or not at all. On the listing,
> match the **exact version** named below and download the **`.apkm`** (App
> Bundle), not a single-arch `.apk`.

---

### 🎬 Disney+

1. Open the **[Disney+ (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/disney/disney-android-tv/)** and select version **`26.9.2+rc1-2026.06.12`**
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

---

### 🎭 HBO Max

1. Open the **[HBO Max (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/warnermedia-direct-llc/max-stream-hbo-tv-movies-android-tv/)** and select version **`7.7.0.78`** (or the fallback `7.5.0.73`)
   > ⚠️ **Get the right package.** These patches target **`com.wbd.hbomax`** (the "Max: Stream HBO, TV & Movies — Android TV" listing linked above). A Google search may surface a different **`com.wbd.stream`** build — that is a *separate* app variant and the patches will **not** apply to it. Confirm the download shows package `com.wbd.hbomax` before patching.
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

---

### ▶️ Prime Video

1. Open the **[Prime Video (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/)** and select version **`6.23.23+v15.5.0.70-armv7a`**
2. Download the `.apkm` file
3. Select it in Morphe Manager
4. Apply the patch

> 🟡 **Partial — DNS filter required, and some ads may still appear.** Prime
> Video picks its ad-delivery path server-side: the bytecode patch covers the
> "Java road" while a DNS blocklist covers the "native road" the patch can't
> see. Native prerolls are baked into the session manifest below the patchable
> layer, so a few ads can still slip through even with both layers active. Run
> the DNS filter alongside the patch — see [**dns/README.md**](dns/README.md)
> for the list and the dual-layer rationale.

---

### 📡 Paramount+

> 🔴 **Currently under development.** The transition from `v16.8` to `v16.12` introduced issues that are still being debugged. Use `v16.8` in the meantime.

1. Open the **[Paramount+ (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/cbs-interactive-inc/paramount-2/)** and select version **`16.8.0`**
2. ⚠️ This listing is published by **CBS Interactive, Inc.** — the correct publisher. Do **not** use the separate Viacom-published build.
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 📺 Tubi

1. Open the **[Tubi (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/tubi-tv/tubi-free-movies-live-tv-android-tv/)** and select version **`10.20.5000`**
2. ⚠️ Use this **Android TV** listing — not the "Tubi (Fire TV)" or the phone listing
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 🌐 ViX

1. Open the **[ViX (Fire TV / Android TV) listing on APKMirror](https://www.apkmirror.com/apk/univision-communications-inc/vix-tv-deportes-y-noticias-fire-tv-android-tv/)** and select version **`4.47.2_tv`** (or the fallback `4.46.0_tv`)
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
> beacons. **LIVE TV** ads are real broadcast time in the linear feed and are not
> removable. DNS filters do **not** help here.

1. Open the **[Pluto TV (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/pluto-inc/pluto-tv-android-tv/)** and select version **`5.66.0-leanback`**
2. ⚠️ Use this **Android TV** listing and pick a **`-leanback`** build — not the phone or Fire TV build
3. Download the `.apkm` file
4. Select it in Morphe Manager
5. Apply the patch

---

### 🦚 Peacock

1. Open the **[Peacock TV (Android TV) listing on APKMirror](https://www.apkmirror.com/apk/peacock-tv-llc/peacock-tv-android-tv/)** and select version **`7.6.100`**
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

### 🦊 Fox One

> 🔴 **Currently under development.** Install instructions will be added once a stable patch is available.

---

## 🙏 Credits

This patch template is based on the prior work of [ReVanced](https://github.com/ReVanced/revanced-patches-template). All modifications made by Morphe, along with their dates, can be found in the Git history.

---

## 📜 License

`ajstrick81` Morphe Patches are licensed under the [GNU General Public License v3.0](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/LICENSE).
