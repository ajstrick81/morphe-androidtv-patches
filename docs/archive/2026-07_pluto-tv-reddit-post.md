> # 🗄️ ARCHIVED — Historical Reddit announcement (July 2026)
>
> **Status: superseded. Kept for transparency, not for installation.**
>
> This is the original Reddit post (with its 2026-07-31 update) that introduced the
> Pluto TV Android TV patch. It is preserved here so the project's history is
> visible even after the live post is updated.
>
> **Why it's archived:** the patch has moved on since this went up. Where this post
> and today's patch diverge:
>
> - **Ad detection changed.** This post says ads are told apart by DRM ("content is
>   DRM-protected; the ad chunks are not"). That rule broke real shows that stream
>   content un-DRM'd (issue #144) — it kept a DRM'd ad bumper and dropped the whole
>   episode. The shipping patch now detects **ads positively** by their creative URL
>   marker and keeps everything else.
> - **Live TV is now maskable.** This post says live ads are simply "NOT removable."
>   Still true they can't be *removed*, but there's now an optional **Mask live ad
>   breaks (black screen + mute)** patch (#152).
> - **Resume was hardened.** A "Continue Watching" bookmark is now re-mapped into the
>   shortened, ad-stripped timeline so resume doesn't overshoot (#147).
>
> **➡️ For the up-to-date write-up and install steps, read
> [`docs/PLUTO_TV_ANNOUNCEMENT.md`](../PLUTO_TV_ANNOUNCEMENT.md), the full teardown in
> [`docs/PLUTO_TV_ATV_SYSTEM_DESIGN.md`](../PLUTO_TV_ATV_SYSTEM_DESIGN.md), and the
> [repo README](../../README.md).**
>
> Everything below this line is preserved as it was originally posted.

---

# Pluto TV Android TV - Patched for Ad-Free Enjoyment!

## Update - 7-31-2026

Quick update on Pluto TV — and a real upgrade. The earlier patch quieted the ad
*experience* (markers, beacons, pause-screen ads), but I have to be honest: the ad
video itself was still sneaking through on some on-demand titles. That's now fixed.
Pluto VOD ads are **fully removed** — the ad video is gone, not skipped. A movie
that ran 2:49:58 with ads now runs 2:18:41, straight content. **No more need to
manually skip ads :)**

### The simple version of how it works

Pluto stitches ads into on-demand titles server-side (SSAI), which is "supposed" to
be unbeatable — the same class of problem that walls off Prime Video. But Pluto does
it in a way that leaves a door open.

A Pluto VOD title is really a **playlist of separate chunks** — content chunks and
adchunks sitting side by side. The player (ExoPlayer/media3) has to read that
playlist to play anything, and on Pluto that playlist is **readable in plain form**.
The content chunks are DRM-protected; the ad chunks are not. So the patch reads the
playlist, keeps the DRM (content) chunks, throws out the ad chunks, and stitches the
content back together seamlessly. No ad frames ever get played.

That's the whole trick: don't try to block the download (that just breaks playback)
— let the player fetch the playlist, then quietly hand it back the content-only
version.

If you want the full teardown — every dead end, the exact detection rule, and why
this works on Pluto but not Prime Video — Claude wrote it all up here:

https://gist.github.com/ajstrick81/fc8928413c3aa46cf4e8e71672a5a282

---

### Testing / Results

Verified on my **Onn 4K Plus** (Android 14), Pluto **v5.66.0-leanback**: multiple
on-demand movies and shows, runtime drops to the real length, mid-rolls gone, and
playback stays smooth across every spot where an ad break used to be. Loads fast, no
delay.

### Scope — On-Demand vs Live TV

- **On-demand (VOD) — fully removed.** Ad chunks dropped, timeline stitched back to
  the true runtime.
- **Live TV (linear channels) — NOT removable.** Live ads are real broadcast time in
  the linear feed — the ad literally IS the live stream at that moment. Same limit as
  any live broadcast.

Optional DNS companion (NOT required): the patch handles all VOD ads in-app, so you
don't need DNS. But if you already run a blocker and want Pluto's ad/beacon hosts
gone at the network layer too:

```
||sp.pluto.tv^
||v.fwmrm.net^
||spotxchange.com^
||tremorhub.com^
```

(Generic Google ad hosts are already covered by any standard list like HaGeZi.)

## Hey everyone!

Back with another one — and this might be my favorite result yet, because Pluto TV
was "supposed" to be unbeatable. It's SSAI (server-side ad insertion), the same
class of stitched-ad problem that walls off Prime Video's mid-rolls. Every instinct
said "the ad video is baked into the stream, bytecode can't touch it." Turns out
that's only half true — and the other half is a clean one-hook kill. Let me explain,
because the lesson here is what separates the apps we CAN beat from the ones we
can't.

Same disclaimer as always: **I am not a coder.** I work in healthcare and lean on
Claude heavily to read bytecode, trace smali, and write the patch code. This one was
a proper collaborative autopsy — dex disassembly across 64,000+ methods, on-device
logcat forensics on my Onn 4K TV, and an AdGuard Premium filtering-log capture that
cracked the whole thing wide open. If AI-assisted patches aren't your thing, totally
fair — but it works, and it's ad-free.

---

### The Big Discovery — WHERE the Ad Timeline Lives Decides Everything

Pluto delivers ads via **server-side ad stitching (SSAI)**, with tracking through
FreeWheel. The ad video is spliced into the same DASH stream as your content and
served from the same CDN. Conventional wisdom — and my own Prime Video scars — says
that's unremovable: you can't strip video segments that are physically baked into the
stream.

**But not all SSAI apps are the same, and the difference is WHERE the ad-break
schedule gets parsed.**

Pluto's stitcher hands the client a `session.json` containing an `adBreaks` array —
the ad-break timeline. And critically, **Pluto parses and consumes that timeline in
the app's own Java/Kotlin layer** (`StitcherSession.getAdBreaks()`). That single list
drives the *entire* ad experience: the ad-break timeline, the ID3-tag beacon
tracking, the pause/clickable-ad overlays, and the seek-blocking during breaks.

**How I confirmed it:** a filtering-log export from AdGuard Premium (running on PC)
showed exactly how it suppresses Pluto's ads — one surgical rule:

`||pluto.tv/*/session.json$jsonprune=$.adBreaks.*`

AdGuard simply strips the `adBreaks` array out of the session response on the wire,
and the ads vanish — no crash, clean playback. That was the proof: the ad video is
**NOT irreversibly baked in.** It's client-assembled from `adBreaks`. Remove the
schedule, remove the ads.

Which means we can reproduce that exact prune **inside the app, in bytecode** — no
proxy, no cert install, no DNS required for on-demand.

**The contrast that matters for the roadmap:** Prime Video does its SSAI ad
decisioning down in a sealed *native* engine (libignite / MediaPipelineBackend),
below the Java line — unreachable in bytecode, DNS-only. Pluto does it in *reachable
Java*. Same disease, but Pluto leaves the door open. **If an SSAI app parses its
ad-break timeline in Java, it's beatable in bytecode. If it's sealed in native code,
it isn't.** That's the single most useful rule I've picked up on this whole project.

---

### What the Patch Does

Four hooks — but one of them does the heavy lifting:

**Hook 1 — BeaconTracker.fire(String, List)** (`return-void`)

The choke point for SSAI tracking beacons (impressions, quartiles, completes).
Silences ad analytics with zero risk to the playback pipeline.

**Hook 2 — PauseAdsImageBinder.showPauseAdImageAfterInactivity(Bitmap, List)**
(`return-void`)

Pluto's pause-screen ad overlay — the full-screen ad shown when you pause. No-op'd
at index 0 so the pause screen renders clean and the overlay is never fetched.

**Hook 3 — ClickableAdsBinder.bind(...)** (returns an already-disposed Disposable)

Kills the interactive/clickable ad overlays by never wiring the subscription graph —
while still honoring the method's contract (the caller gets a valid, harmless
Disposable), so nothing hangs.

**Hook 4 — StitcherSession.getAdBreaks() → empty list** (the keystone)

This is AdGuard's `$jsonprune=$.adBreaks.*` reproduced in bytecode. It forces the
parsed ad-break timeline to be empty at the single choke point every consumer reads
it through. The result: **no ad breaks are ever scheduled — no ad video, no markers,
no overlays, and no beacons.** On-device it even silenced the ID3-tag beacon tracker
that Hooks 1–3 don't fully cover, because that tracker is fed by the very same
`adBreaks` list. One hook, entire ad subsystem starved.

The patch also ships with an **Override certificate pinning** hook — it's what made
Pluto's HTTPS traffic inspectable during analysis, and it's harmless to leave
enabled.

### Testing / Results

Verified on my **Onn 4K Plus** (Android 14), Pluto **v5.66.0-leanback**: played
multiple on-demand movies and shows — **playback loads fast, no delay whatsoever, ad
markers gone, and zero tracking beacons fired.** Straight-through content on every
title I tried.

### Important — Scope: On-Demand vs Live TV

Being upfront about the boundary, because SSAI has one part even this can't fix:

- **On-demand (VOD) — fully suppressed.** The ad-break timeline is client-side, so
  emptying it removes the ads completely. This is the win.
- **Live TV (linear channels) — NOT removable.** Live ads occupy real broadcast
  wall-clock time in the linear feed — there's no "skip," because the ad literally IS
  the live stream at that moment. Same fundamental limit as any live broadcast.

**Optional DNS companion (belt-and-suspenders, NOT required):** unlike some apps,
Pluto's pre-rolls do *not* need DNS — Hook 4 handles all on-demand ads in-app. But if
you already run a DNS blocker and want Pluto's beacon/ad hosts gone at the network
layer too, from the same AdGuard capture:

```
||sp.pluto.tv^
||v.fwmrm.net^
||spotxchange.com^
||tremorhub.com^
```

(Generic Google ad hosts like doubleclick / googlesyndication / adnxs are already
covered by any standard list such as HaGeZi.)

---

### Installation

**Add the repo to Morphe:**

https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches

**Step by step:**

1. Download the Pluto TV Android TV APK **v5.66.0-leanback** from APKMirror to your
   phone/tablet.
2. Load the APK into Morphe Manager and select the **Pluto TV — Skip ads** patch (you
   can leave the certificate-pinning override on).
3. Allow patching to complete, then save the patched APK (save it — don't
   auto-install; use the save option in the bottom-right corner).
4. Uninstall the existing Pluto TV app from your Android TV device first (avoids a
   signature conflict).
5. Install via one of these methods:
   - **Option A:** ADB streamed install — connect to the TV via ADB and run
     `adb install <patched_apk>`
   - **Option B:** Use "Send Files to TV" to transfer the patched APK, then install
     from a file manager
6. (Optional) add the DNS rules above if you want the trackers gone too.
7. Enjoy ad-free on-demand Pluto TV!

---

## What This Means Going Forward

The real prize here isn't just Pluto — it's a **reusable recon technique**. An
AdGuard Premium filtering-log capture tells you *exactly* which request/rule kills
the ads ($jsonprune, $replace, or a plain host block). If that mechanism maps onto
something the app parses in its own Java layer, you can reproduce it in bytecode and
cut the network out of the loop entirely. That's precisely how Hook 4 was found, and
it should carry over to other client-side-SSAI apps....hopefully ;

It also sharpens the map for future targets: apps that parse their ad-break timeline
in Java (Pluto) are beatable in-app; apps that bury it in a sealed native pipeline
(Prime Video) are DNS-only. Knowing which is which up front saves a mountain of
dead-end effort. And honestly — a free service with tens of thousands of on-demand
titles, now ad-free? Hard to beat that.

Huge thanks to the Mod Team, to RookieEnough and hoo-dles for building such a great
community around Morphe, and to everyone who has tested and given feedback. The
debugging on this one was quick, but only because of everything the community has
documented before it. Every lesson from this build is in the repo. See you in the
next one!
