# 🟣 HBO Max (Android TV) Ad Patch — Current State

*Last updated: 2026-08-26 · Target APK `com.wbd.hbomax` `7.9.0.61` (Android TV)*

> This is the up-to-date write-up for the HBO Max Android TV patch. The earlier
> Reddit announcement described the first approach and is preserved, unedited, at
> [`docs/archive/2026-08_hbo-max-reddit-post.md`](archive/2026-08_hbo-max-reddit-post.md).
> **If those two documents ever disagree, this one is correct.**

---

## TL;DR

- **What it does:** removes HBO Max ads — pre-rolls **and** mid-rolls, on movies
  and shows — *including the ad-supported tier's server-stitched (SSAI) ads* that
  the original post called impossible to remove. No "Ad 1 of 2" countdown, no
  seekbar lock, no ad segments fetched.
- **How it works now:** instead of fighting the ad-stitched stream, the patch
  makes the player load **HBO's own ad-free manifest**. Every ad-tier title ships
  a second, non-ad-stitched "fallback" stream that HBO uses for its own resiliency
  recovery — the patch simply selects that stream from the start.
- **Bonus:** it loads **faster** than the ad-supported stream (no ad stitching, no
  failed ad requests, no resiliency retries), and resuming a title no longer
  throws a "Couldn't Play Content" error.
- **Still not an unlocker** — you need a valid Max subscription. See below.

---

## The wall that wasn't a wall

The original announcement was honest about a hard limit: on the ad-supported tier,
ads are **server-side stitched (SSAI)** into the video segments before they reach
your device. The six bytecode hooks in that first patch suppress HBO's ad
*markers and timeline* — the overlays, the "Ad 1 of 2" countdown, the beacons —
but they can't remove video that's already baked into the stream. The post
concluded those ads were *"structurally impossible to remove from the client
side… there is no workaround."*

That turned out to be an assumption, not a hard constraint. Digging one level
deeper revealed that HBO's client keeps a **resiliency / CDN-failover** layer, and
for every ad-tier title the server actually offers **two** manifests:

```
PRIMARY  (ad-stitched):  https://<token>.cf.prd.media.h264.io/gcs/<uuid>/dash.mpd
FALLBACK (ad-free):      https://akm.prd.media.h264.io/gcs/<uuid>/<hash>_fallback.mpd
```

Same content, but the **fallback** manifest has **no ad periods at all**. HBO only
switches to it after a playback failure. The current patch just tells the player
to use it from the first request — so the "impossible" baked-in ads are never
fetched, because HBO hands us a stream that was never stitched.

---

## How it works now

**Patch: "HBO Max - Prefer Ad-Free Stream"** (on by default).

An HBO `Playable` carries two streams (`StreamInfo`): a `PRIMARY` (ad-stitched)
and a `FALLBACK` (ad-free) one. Normal playback resolves the `PRIMARY` stream via
`PlayableKt.getStreamInfo(Playable, StreamInfo$Type)`. The patch injects a tiny
helper (`HboStreamSelector`) at the entry of that method to **remap a PRIMARY
request to FALLBACK — but only when a FALLBACK stream actually exists** (checked by
reflection, fail-open, so titles without one are left exactly as they were).

Result: the player loads HBO's own ad-free manifest on **fresh start and resume
alike**. Because there are no ad periods:

- no ad video plays (pre-roll or mid-roll),
- no ad countdown / overlay / seekbar lock,
- no timeline gaps or rewinds,
- and no "Couldn't Play Content (39999)" error when resuming into a former ad break.

This is HBO's *own* sanctioned ad-free stream, so playback machinery (DRM, seeking,
resume) stays completely consistent.

---

## The journey (for the curious)

Getting to the clean fix meant walking into — and back out of — several walls:

1. **Six bytecode ad-timeline hooks** (the original post). Kill the markers and
   overlays, but the ad-supported tier's baked SSAI video still plays. These still
   ship as the default "Disable Ads" patch and cover the overlay/timeline layer.
2. **"Block SSAI Ad Origins"** (opt-in). Force the ad-segment origin to fail so
   HBO's resiliency swaps to the clean fallback manifest. Works on a **fresh
   start** — but a *resumed* title never triggers the swap at startup, so hitting a
   mid-roll threw a fatal **39999** error. Briefly shipped default-on, then reverted
   to opt-in when the resume regression was found.
3. **Manifest surgery / segment-CDN forcing.** Removing ad periods from the
   stitched manifest left timeline gaps (a ~20-30s rewind at each break); forcing
   the alternate CDN at the segment layer was the wrong layer (the ad-free-ness is
   a different *stream*, not just a different host).
4. **"Prefer Ad-Free Stream"** (current). Select HBO's own fallback stream at the
   source. Clean on fresh start, resume, and seek — validated on-device with a
   20-minute stress run (repeated resume→seek cycles) with zero ad leaks.

Each wall taught us where the real seam was. Classic growth-mindset debugging.

---

## What ships today

- **HBO Max - Prefer Ad-Free Stream** *(default on)* — loads HBO's ad-free manifest.
  This is the primary ad remover.
- **HBO Max - Disable Ads** *(default on)* — the original bytecode hooks (Bolt
  overlay ads, SSAI/AdSparx timeline, live/episodic prerolls, Nowtilus). Kept as a
  belt-and-suspenders layer for overlay/timeline ad surfaces.
- **HBO Max - Block SSAI Ad Origins** *(opt-in, default off)* — the fresh-start
  origin block. Superseded by Prefer Ad-Free Stream; retained as an option.

---

## This is NOT an app unlocker

To be crystal clear: the patch does **not** unlock premium features (4K, etc.),
bypass authentication, or give you a subscription for free. You must have a valid
Max account and active subscription. All it does is suppress ads for subscribers
who prefer an ad-free experience. Please don't ask how to get free access to
content — that is not what this is.

---

## Install

Add the source:
`https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches`

1. Download the Android TV APK bundle for `com.wbd.hbomax` (target build
   `7.9.0.61`) to your phone/tablet (APKMirror, "Android TV" variant).
2. Load the `.apkm` into Morphe for patching ("No, I already have an APK").
3. Let it patch, then **save** the patched APK (don't click install).
4. **Uninstall or disable the existing HBO Max app** on your Android TV device
   first (so the patched build isn't shadowed by the store version).
5. Install the patched APK — via `adb install` (streamed install) or the
   "Send Files to TV" app.
6. Enjoy ad-free Max on Android TV.

> Note: a valid ad-tier or ad-free Max subscription both work; on the ad-tier the
> patch now removes the previously-unavoidable baked-in ads by using HBO's own
> ad-free stream.

---

*Huge thanks to RookieEnough and Hoodles for the inspiration and the community, and
to everyone who tested and gave feedback — the resume-vs-fresh-start reports in
particular were the clue that cracked this one.*
