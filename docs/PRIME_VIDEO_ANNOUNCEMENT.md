# ▶️ Prime Video (Android TV) Ad Patch — Current State

*Last updated: 2026-09-24 · Ships in release `v1.37.4`+ · Target APK
`6.23.23+v15.5.0.70-armv7a` (engine `v15.5`)*

> This is the up-to-date write-up for the Prime Video Android TV patch. An
> earlier Reddit announcement described the first approach and is preserved,
> unedited, at
> [`docs/archive/2026-07_prime-video-reddit-post.md`](archive/2026-07_prime-video-reddit-post.md).
> **If those two documents ever disagree, this one is correct.**

---

## TL;DR

- **What it does:** removes **movie and TV-show** ads — prerolls and mid-rolls —
  before they play. No "Ad 1 of 2" countdowns, no ad segments fetched.
- **How it works now:** a **native, in-process hook** (`libpvhook.so`) that
  neutralizes ads inside Amazon's own runtime, backed by a secondary layer of
  Java-bytecode hooks. (Details below.)
- **What you need:** just the patched APK. **No DNS list, no VPN, no proxy, no
  root.**
- ⚠️ **Stay on `6.23.23` (engine `v15.5`). Do NOT update to `6.24.x` / engine
  v16** — Amazon moved the ad pipeline into the native engine there and ads come
  back. Keep **Disable auto-updates** on.
- **Where it's tested:** Onn 4K Android TV, US account, ad-supported tier.
  Other devices — see [Device compatibility](#device-compatibility).
- 🔬 **Want the deep dive?** A full reverse-engineering teardown with an
  **interactive diagram** of exactly how the strip works:
  [system-design doc](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md)
  · [interactive diagram](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/primevideo-ad-path.html).

---

## What changed since the original post

The first version of this patch (the archived Reddit write-up) bet everything on
one idea: intercept `ServerSideAdInsertionMediaSource.setAdPlaybackStates()` —
the boundary where the WASM/Ignite ("Megablast") runtime hands an ad schedule to
ExoPlayer/media3 — and strip the ad groups there.

That was a good, clean idea, and it partly worked. But Prime Video's WASM runtime
did not reliably route the whole ad schedule through that one media3 seam, so the
bytecode hook alone left gaps. Rather than route around the wall, the patch went
**one level deeper — into the native runtime itself.** The honest changes:

1. **The primary mechanism is now native, not bytecode.** `libpvhook.so` installs
   a `memcpy`/`memmove` **GOT/PLT import hook** inside `libignite` and empties the
   ad data out of the playback response **as the runtime decompresses it** —
   upstream of the point where any player, WASM overlay, or ad CDN request gets
   involved. It does this by producing the **same empty-break shape Amazon's own
   servers return most of the time**, so the app plays it cleanly. The old
   `setAdPlaybackStates` bytecode hooks still ship, but as a **secondary**
   control-plane layer, not the whole patch.
2. **TV shows are covered.** The archived post was almost entirely about movies;
   the native hook suppresses **TV-show** prerolls and mid-rolls too.
3. **Hardened for high-ad regions (v1.37.4).** In aggressive-ad regions (India,
   EU) a mid-roll ad response can be large enough to split across memory chunks;
   the strip now salvages those safely instead of letting the ad through or
   stalling playback. See the freeze note under [Installation](#installation-current-recommended).

One more correction: the archived post's DNS advice contradicts itself (it says
"no DNS needed," then a "DISREGARD — DNS is needed" note, then "do not run DNS
alongside the patch"). **The definitive answer today: no DNS rules, no VPN, no
proxy.** The patch is entirely in-app and self-contained.

---

## How the current patch works

[![Prime Video ATV ad strip: PRS and getVideoAds responses pass through libcurl/zlib, where libpvhook.so empties the ad data in the copy destination before the QuickJS player parses it](https://raw.githubusercontent.com/ajstrick81/morphe-androidtv-patches/main/docs/diagrams/primevideo-ad-path.png)](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/primevideo-ad-path.html)

> 🔬 **[Explore the interactive diagram ↗](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/primevideo-ad-path.html)** — step through four guided views: *Movies: strip Remote*, *TV: empty ad list*, *#14: why slots stay*, and *Truncated responses*. The full teardown is in
> [`docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md`](PRIME_VIDEO_ATV_SYSTEM_DESIGN.md).

The Prime Video patch is a small stack of cooperating patches. When you select
**Skip ads** in Morphe Manager, its dependencies pull in the rest.

| Layer | Patch | What it does |
|-------|-------|--------------|
| 🥇 **Primary — native** | **Bundle native ad-strip hook** + **Load native ad-strip hook** | Packages `libpvhook.so` into the APK and loads it at startup. Installs a `memcpy`/`memmove` GOT/PLT import hook in `libignite` and empties the ad breaks in-process — blanking *Remote* ad items in the movie playlist and emptying the resolved `getVideoAds` response for TV shows, so the app runs its own no-ad path. |
| 🥈 **Secondary — bytecode** | **Skip ads** (+ **Prime Video extension**) | Multi-layer control-plane suppression: media3 & ExoPlayer2 `setAdPlaybackStates()` ad-group strip, a metrics/impression-report short-circuit (fake `SUCCESS` upload so Amazon can't measure impression deficits), and a Volley `BasicNetwork.performRequest` ad-host block. |
| ⚙️ **Optional adjuncts** | **Clone Prime Video**, **Disable auto-updates**, **Override certificate pinning** | Side-by-side install for non-removable system builds; stops the Play Store silently replacing the patched build; trusts user CAs (**off by default**; only needed if you specifically want AdGuard Premium HTTPS inspection — not required for ad removal, and it makes the app trust any user-installed CA). |

**Why native is the durable answer here:** emptying the ad breaks inside
`libignite`, into Amazon's own "no ads" shape, means the WASM overlay has nothing
to render and no ad CDN (Akamai / SGAI stitching / `s.amazon-adsystem.com`
beacons) is ever contacted. From Amazon's side the session still looks normal —
requests happen, the session establishes — so there's no failed-request
fingerprint of the kind that DNS blocking leaves behind. That's the same "leaves
no suspicious network fingerprint" advantage the original post valued, now
enforced one layer lower and more completely.

---

## Installation (current, recommended)

1. **Add the repo to Morphe:**
   <https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches>
2. **Get the right APK.** On APKMirror, open the
   **[Prime Video (Android TV) listing](https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/)**
   and download version **`6.23.23+v15.5.0.70-armv7a`** as the **`.apkm`** bundle.
   - ⚠️ **Do NOT get `6.24.x` (engine v16).** On v16 Amazon moved the whole ad
     pipeline into the native engine, so the patch installs cleanly but ads come
     back. Match `6.23.23` exactly and keep **Disable auto-updates** on.
   - ⚠️ Get the **Android TV** build, not the phone build and **not** the Fire TV
     build. Fire TV is a different app (`com.amazon.firebat`) — this patch targets
     `com.amazon.amazonvideo.livingroom`.
3. **Patch it.** Open Morphe Manager, select the `.apkm`, and apply **Skip ads**
   (its dependencies bring in the native hook + extension automatically). Leave the
   default patch selection as-is unless a note below applies to your device.
4. **Save, don't install.** Use Morphe's save option (bottom-right) to export the
   patched APK rather than installing directly.
5. **Transfer & install** on the TV — e.g. the *Send Files to TV* app.
6. **Sign in and test.** Play a movie and a TV show to confirm both are ad-free.

**That's it — no DNS list, no VPN, no proxy, no root.**

> ℹ️ **High-ad regions (India / EU):** a rare, very large mid-roll break can still
> briefly freeze (spinner or "Something went wrong"). If it happens, press
> **Back**, then **Resume** — playback continues ad-free. v1.37.4 clears the large
> majority of these; the residual case is a known limit of the current hook layer.

### Device-specific options
- **Non-removable / system-app Prime Video** (some Fire TV & preinstalled boxes):
  installing over the stock app can fail with a signature/`UPDATE_INCOMPATIBLE`
  error. Enable **Clone Prime Video** to install side-by-side under its own
  package (a second Prime Video icon with its own login). Leave it off if you were
  able to fully uninstall the stock app first — an in-place install is cleaner.
- **Keep the patch from being overwritten:** **Disable auto-updates** is available
  so the Play Store won't silently replace the patched build (and can't push you to
  v16).

> ℹ️ **Historical note:** the archived post told users to *disable* a "Morphe
> patches" package-rename option to avoid `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
> That concern is now handled explicitly by the opt-in **Clone Prime Video**
> patch — use that when you need a side-by-side install, and otherwise install
> in place.

---

## Device compatibility

| Device | Status | Notes |
|--------|--------|-------|
| **Onn 4K Android TV** (US, ad-supported) | ✅ **Confirmed** | Built and validated here across multiple sessions. |
| **Nvidia Shield / other arm64 Android TV** | ⚠️ Likely, unverified | The `.apkm` carries the arm64 slice; the native hook targets the same `libignite` import surface. Low risk to try, untested. |
| **Google TV built-in TVs** (Sony/TCL/Hisense) | ⚠️ Varies | Prime Video is often a protected system app; use **Clone Prime Video** or a dongle (Chromecast w/ Google TV, TiVo Stream 4K) where it isn't a system app. |
| **Non-US accounts** | ⚠️ Varies | Regional ad infrastructure and WASM bundles differ; high-ad regions (India/EU) may hit the rare mid-roll freeze above. |
| **Fire TV Stick / Fire TV** | ⚠️ Varies | Prime Video is often a protected system app; use **Clone Prime Video** or a dongle that may produce a desired result. |

If you try an untested combination and it works (or doesn't), please open an issue
— that data helps everyone.

---

## Known edge cases

- **Rare mid-roll freeze in high-ad regions** (India/EU): a very large mid-roll
  break can briefly freeze; **Back → Resume** continues ad-free (see above).
- **Fast-forward + resume nudge:** very aggressive fast-forward + resume can
  occasionally nudge the playback position. It self-heals on a full playthrough and
  normal viewing is unaffected.

Anything else, please file an issue.

---

## Recommendations at a glance

- ✅ **Do** install only the patched APK. It's self-contained.
- ✅ **Do** enable **Disable auto-updates** so the store doesn't replace it — or
  push you to v16.
- ✅ **Do** use **Clone Prime Video** only if you can't uninstall a system build.
- 🚫 **Don't** update to `6.24.x` / engine v16 — ads return there.
- 🚫 **Don't** add DNS filter lists, a VPN, or AdGuard alongside it — the native
  hook doesn't need them, and added DNS latency / a local VPN can interfere with
  Ignite session init.
- 🚫 **Don't** expect the archived Reddit steps to match — follow this document.

---

## 🙏 Credits & influences

- **Paresh Maheshwari** ([`Paresh-Maheshwari/morphe-ai`](https://github.com/Paresh-Maheshwari/morphe-ai))
  — for his APK analysis methodology (the RECON → DECOMPILE → HUNT → WRITE →
  BUILD+DEPLOY workflow), which shaped how targets on this app were located and
  verified in smali before a single fingerprint was written. See
  [`docs/PARESH_APK_ANALYSIS_METHODOLOGY.md`](PARESH_APK_ANALYSIS_METHODOLOGY.md).
- Hoodles' Prime Video Mobile patch in particular — the FSM state intercept
  approach — was a major conceptual influence even though the ATV architecture
  ended up being completely different.

Huge thanks to the Mod Team, and to RookieEnough, Paresh, and u/hoo-dles for
building such a solid community around Morphe and for the reference
implementations that helped shape the methodology here.

---

*Built with a growth mindset: the first wall — "the ad schedule only lives in a
downloaded WASM bundle, you can't patch it" — turned out to be an assumption, not
a hard constraint. Going one layer deeper, into the native runtime, is what made
a complete, DNS-free strip possible.*
