# 🍿 Netflix (Android TV) Ad Patch — Current State

*Last updated: 2026-09-24 · Ships in release `v1.37.4`+ · Target APK
`13.0.1 build 25028` (armeabi-v7a)*

> This is the up-to-date write-up for the Netflix Android TV patch. It replaces
> the details in the original Reddit post where they differ. That earlier Reddit
> announcement is preserved, unedited, at
> [`docs/archive/2026-07_netflix-reddit-post.md`](archive/2026-07_netflix-reddit-post.md).
> **If those two documents ever disagree, this one is correct.**

---

## Intro

I'm a huge fan of this community and all the work the developers have put into
Morphe. I'm **not a coder** — I work in healthcare — so I lean on Claude heavily
to read bytecode, trace smali, and write the patch code. If AI-assisted patches
aren't your thing, that's completely fair. But it works, and it's ad-free.

To be clear up front: this is **not** about getting Netflix for free. You need a
valid, paid account and you log in normally. Bypassing subscriptions, paywalls, or
DRM is out of scope. This only removes ads and trims device telemetry inside an
app you're already paying for.

---

## TL;DR

- A Morphe patch that installs an **ad-free clone** of the real living-room Netflix
  app on Android TV. Kills **pre-rolls, mid-rolls, and the pause-screen ad**, plus
  an optional privacy toggle. No root, no proxy, no DNS/VPN.
- **You keep stock Netflix installed** — the patched clone needs it there
  (explained below).
- ⚠️ **Netflix's ad logic is downloaded JavaScript, not baked into the app.** That
  means Netflix can change ad delivery server-side at any time, so an ad can
  occasionally reappear until the patch is re-pointed. The patch already ships
  rename-tolerant fallbacks for this; if you see an ad, update to the latest patch
  first (details under [If ads come back](#if-ads-come-back)).
- On-device verified on an Onn 4K.
- 🔬 **Deep dive:** full teardown + an **interactive diagram** of how it works —
  [system-design doc](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/docs/NETFLIX_ATV_SYSTEM_DESIGN.md)
  · [interactive diagram](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/netflix-ad-path.html).

---

## What it does

- ✅ **Pre-roll ads** (before playback) — gone
- ✅ **Mid-roll ads** (during a show/movie) — gone
- ✅ **Pause-screen ad overlay** (the full-screen ad when you pause) — gone
- ✅ Playback, resume-to-position, and seeking all work normally
- 🔒 **Optional privacy toggle** ("Minimize Network Fingerprint") — blanks the
  local IP / MAC / Wi-Fi SSID the app reports, and stops it reporting your
  advertising ID (GAID) in telemetry. Off by default.

This is the actual TV app (`com.netflix.ninja`), not a phone app running on your TV.

---

## How it works (the short version)

[![Netflix ATV ad strip: the encrypted manifest is decrypted in-process, ad code and data become plaintext in the JS heap, and the in-process hook empties getAdMetadata into Netflix's own no-ad shape](https://raw.githubusercontent.com/ajstrick81/morphe-androidtv-patches/main/docs/diagrams/netflix-ad-path.png)](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/netflix-ad-path.html)

> 🔬 **[Explore the interactive diagram ↗](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/netflix-ad-path.html)** — four guided views: *Why in-heap*, *The kill*, *Clone + tamper*, and *Drift + fallback*.

Netflix looked unbeatable: the traffic is encrypted (MSL), the ad code is a
downloaded bundle that's cryptographically **signed**, and the ads are served from
the same servers as the content. But every one of those is a gate the app has to
pass through to play anything — and once it does, the ad schedule is plaintext in
the app's own memory, in code that carries a built-in "no ads" path Netflix's own
servers use most of the time.

So the patch runs **in-process**, inside a cloned Netflix: it waits until the ad
schedule is decrypted, then makes the ad-break builder return an **empty list** —
the exact shape Netflix returns when it has no ad to show. The app plays it
cleanly. Nothing is blocked on the network, so there's no failed-request
fingerprint the way DNS blocking leaves.

---

## Why there are TWO apps (please read — this trips people up)

You end up with **stock Netflix** and an **ad-free Netflix clone** installed side
by side. Both must stay installed. Here's why:

- Netflix on a TV is a **system app** cryptographically signed by Netflix. You
  **cannot** patch it in place, and you **cannot** uninstall it without root. So the
  patch installs as a **separate app** with its own name.
- The clone is protected by a **native anti-tamper check** that reads Netflix's
  original signature. The clone passes it by *pointing at the stock app and
  borrowing its genuine signature*. **Remove or disable stock Netflix and the clone
  will refuse to start.**
- The clone reads stock's **signature, not its version** — so it's fine to let
  stock Netflix auto-update in the background. It won't break the clone.

**Bottom line:** keep stock Netflix installed and enabled. You just never open it —
you open the clone instead. Log into the clone once and you're set.

---

## Install

1. **Add the repo to Morphe:**
   <https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches>
2. **Get the right APK.** On APKMirror, open the **Netflix (Android TV)** listing
   (publisher *Netflix, Inc.*, package `com.netflix.ninja`) and get version
   **`13.0.1 build 25028`**. This listing has **no App Bundle** — download the
   single **`armeabi-v7a`** APK.
3. **Patch it.** In Morphe, select **Netflix** and apply the patch set:
   - **Remove Netflix ads** — the ad kill (on by default)
   - **Clone Netflix** — required; makes it installable alongside stock (on by
     default)
   - **Disable Netflix CertCheck** — required; on by default
   - **Minimize Network Fingerprint** — optional privacy toggle (off by default)
4. Make sure **"Optimize for Device Architecture"** is toggled **off**.
5. **Save, don't install.** Export the patched APK.
6. **Transfer & install** on the TV (e.g. *Send Files to TV*). **Do not uninstall
   stock Netflix.**
7. Open the new **Netflix Clone** app, sign in, and use it like normal.

**No root, no proxy, no DNS/VPN.**

---

## If ads come back

Netflix's ad logic is **downloaded JavaScript** that Netflix re-minifies
server-side from time to time, with no app update. When that happens the patch's
byte anchors can drift and an ad slips through. The patch ships rename-tolerant
fallbacks that re-anchor automatically, and this has already been fixed once this
way (issue #166). If you see an ad:

1. Make sure you're on the **latest patch version** and re-patch.
2. If it persists, please open an issue — the more detail, the faster the
   re-anchor.

---

## FAQ

**Do I need root?** No. No root and no proxy. Your ATV device plays it seamlessly
without ads.

**Do I need a VPN or a DNS blocker (AdGuard/Pi-hole)?** No. The ads are removed
inside the app, not by blocking network requests.

**Will this stop the "you're traveling / not in your household" prompt?** No.
That's based on your public IP address, which Netflix's servers see no matter what
the app does. Only a VPN/proxy changes that. (There's an opt-in **Suppress
Household Prompt** patch that hides the *prompt* client-side, but it doesn't change
the server-side detection.)

**Does the privacy toggle make me anonymous?** No — be realistic. Netflix still
knows who you are (you're logged in) and what you watch. The toggle trims granular
device analytics (local network details, advertising ID). It's a reduction, not a
cloak.

**Will Netflix updating break it?** Let stock Netflix update freely. If a future
Netflix version changes the internals enough that ads come back, the patch
fingerprints get refreshed and you rebuild (see [If ads come back](#if-ads-come-back)).

**Two Netflix icons is annoying — can I hide stock?** You can move it out of the
way, but don't disable or uninstall it — the clone needs it present to start.

**Is my login safe / is this a fake app?** It's the real Netflix APK, re-signed and
cloned locally by you with Morphe. It talks to Netflix's real servers with your
real account. Nothing is proxied through a third party.

**Does this get me Netflix for free / bypass the subscription?** No — and that's
not what this is. You need a valid, paid Netflix account and you log in normally.
This only changes how ads and device telemetry are handled inside an app you're
already entitled to use. Bypassing paywalls, subscription checks, DRM, or any
access control is out of scope — don't ask for it and don't expect it here.

---

## Verified

Tested on an Onn 4K (Android 14, Netflix `13.0.1`). Across repeated runs the
Netflix servers **did** deliver real pre-rolls, mid-rolls and pause ads — and none
of them played, with no playback errors and resume position intact. A full
technical write-up of how it works is in the
[system-design doc](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/docs/NETFLIX_ATV_SYSTEM_DESIGN.md).

---

## 🙏 Credits

- **sshh12** for the original Netflix *web* system-design teardown, which named the
  internal systems (MSL, Pinot, the manifest model) that this Android TV analysis
  builds on.
- The reporter of issue #166, whose precise on-device logs pinned Netflix's
  server-side re-minify and made the drift fix a clean re-anchor.
- The Nikflix project (household-enforcement seam), credited in the repo's `NOTICE`.
- Huge thanks to the Mod Team, and to **RookieEnough, Hoodles, and Paresh** for
  building such a solid community and the reference implementations that shaped the
  methodology here.

---

*Built with a growth mindset: "the ad schedule only lives in a signed, encrypted
bundle, you can't touch it" turned out to be an assumption, not a hard constraint.
Signed isn't sealed — the app has to decrypt and run the bundle to play anything,
and by then the ad schedule (and its own no-ad path) is right there in memory.*
