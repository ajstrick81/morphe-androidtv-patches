# 📡 Pluto TV (Android TV) Ad Patch — Current State

*Last updated: 2026-09-24 · Ships in release `v1.37.4`+ · Target APK
`5.66.0-leanback`*

> This is the up-to-date write-up for the Pluto TV Android TV patch. It replaces
> the details in the original Reddit post where they differ. That earlier Reddit
> announcement (with its 2026-07-31 update) is preserved, unedited, at
> [`docs/archive/2026-07_pluto-tv-reddit-post.md`](archive/2026-07_pluto-tv-reddit-post.md).
> **If those two documents ever disagree, this one is correct.**

---

## TL;DR

- **On-demand (VOD): ads fully removed.** The ad *video* is gone, not skipped —
  ad periods are dropped and the timeline is re-based to the true runtime. A movie
  that ran 2:49:58 with ads plays 2:18:41, straight content. Markers, beacons and
  the pause-screen ad are gone too.
- **Live TV: now maskable.** Live/linear ads are real broadcast time and can't be
  *removed*, but the optional **Mask live ad breaks (black screen + mute)** patch
  **hides** them — black screen + mute for the break, restored the instant the show
  returns.
- **What you need:** just the patched APK. **No DNS, no VPN, no proxy, no root.**
- 🔬 **Deep dive:** full teardown + an **interactive diagram** of how it works —
  [system-design doc](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/docs/PLUTO_TV_ATV_SYSTEM_DESIGN.md)
  · [interactive diagram](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/plutotv-ad-path.html).

---

## The simple version of how it works

Pluto stitches ads into on-demand titles server-side (SSAI), the same class of
problem that walls off Prime Video. But Pluto does it in a way that leaves a door
open.

A Pluto VOD title is really a **playlist of separate chunks** — content chunks and
ad chunks side by side. The player (ExoPlayer/media3) has to read that playlist to
play anything, and on Pluto **that playlist is readable in plain form** (media3's
DASH classes aren't obfuscated). So the patch reads the playlist, drops the ad
chunks, stitches the content back together seamlessly, and re-bases the timeline so
there are no gaps. No ad frames are ever fetched or played.

That's the whole trick: don't block the download (that just black-screens
playback) — let the player fetch the playlist, then hand it back the content-only
version at the parser.

> **Correction from the original post.** The first version told ads apart by DRM
> ("content is DRM-protected, ads aren't"). That was wrong and it broke real shows:
> some titles stream their **content un-DRM'd**, and one ad *bumper* is itself
> DRM'd — so the DRM rule kept the ad and threw away the episode (issue #144, e.g.
> *Supermarket Sweep* collapsing to a 10-second stub). The shipped patch now
> detects **ads positively** by their creative URL marker (`_ad/creative`, usually
> percent-encoded as `_ad%2Fcreative`, plus `_ad_bumper`) and **keeps everything
> else** — content, DRM'd or not. An unknown chunk is treated as content and never
> dropped.

---

## How the current patch works

[![Pluto TV ad strip: ad chrome emptied by hooks 1-4, ad video periods removed at DashManifestParser by hook 5, resume remapped by hook 6, and live breaks masked via ID3 ad ticks](https://raw.githubusercontent.com/ajstrick81/morphe-androidtv-patches/main/docs/diagrams/plutotv-ad-path.png)](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/plutotv-ad-path.html)

> 🔬 **[Explore the interactive diagram ↗](https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/plutotv-ad-path.html)** — four guided views: *VOD strip*, *Ad chrome*, *Resume fix*, and *Live mask*.

The **Skip ads** patch is a stack of cooperating hooks — but one does the heavy
lifting:

| Hook | What it does |
|------|--------------|
| **1 — `BeaconTracker.fire`** | silences all SSAI tracking beacons |
| **2 — `PauseAdsImageBinder.showPauseAdImageAfterInactivity`** | kills the pause-screen ad |
| **3 — `ClickableAdsBinder.bind`** | kills interactive/clickable ad overlays |
| **4 — `StitcherSession.getAdBreaks`** | empties the ad-break timeline (markers, seek-block, ID3 beacons) — AdGuard's `$jsonprune=$.adBreaks.*` in bytecode |
| **5 — `DashManifestParser.parse`** ★ | **removes the ad VIDEO** — drops ad `<Period>`s and re-bases the timeline. The real removal. |
| **6 — `AviaPlayer.startExoplayer`** | **new (#147):** re-maps a "Continue Watching" bookmark into the shortened, ad-stripped timeline so resume doesn't overshoot and auto-advance |

Hooks 1–4 remove the ad *experience*; hooks 5–6 remove the ad *frames* and keep the
shorter timeline consistent. Two safety rails on the strip: **live/dynamic
manifests pass through untouched** (live is handled separately, below), and if a
strip would keep less than ~40% of the runtime it passes through unchanged rather
than risk mangling a title.

---

## Live TV — mask, don't remove (optional patch)

Live/linear ads occupy real broadcast wall-clock time — the ad *is* the stream at
that moment, so there's nothing to delete. The optional **Mask live ad breaks
(black screen + mute)** patch hides them instead: during a live commercial it
covers the player with a black screen and mutes the audio, then restores both the
instant the show returns.

- It reads Pluto's own ID3 ad-state signal, so it needs no polling and works
  alongside **Skip ads**.
- It's **opt-out** — leave it off to watch live ads normally.
- Runtime controls are marker files in the app's external files dir
  (`Android/data/tv.pluto.android/files/`): create **`slate_off`** to disable it
  on-device without re-patching, or **`pluto_slate_mode`** containing `black`
  (cover only) or `mute` (mute only) instead of the default both.

---

## Installation

1. **Add the repo to Morphe:**
   <https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches>
2. **Download** the Pluto TV Android TV APK **`5.66.0-leanback`** from APKMirror.
   Use the **Android TV `-leanback`** build — not the phone or Fire TV build.
3. **Patch it.** Load the APK into Morphe Manager and select **Skip ads** (you can
   leave the certificate-pinning override on). Optionally add **Mask live ad breaks
   (black screen + mute)** if you also watch live channels.
4. **Save, don't install.** Use Morphe's save option (bottom-right) to export the
   patched APK.
5. **Uninstall the existing Pluto TV** app from your Android TV first (avoids a
   signature conflict).
6. **Install** via ADB (`adb install <patched_apk>`) or *Send Files to TV*.
7. **Enjoy ad-free on-demand Pluto TV.**

**No DNS, no VPN, no proxy, no root.**

### Optional DNS companion (belt-and-suspenders, NOT required)

The patch handles all VOD ads in-app, so you don't need DNS. But if you already run
a blocker and want Pluto's ad/beacon hosts gone at the network layer too:

```
||sp.pluto.tv^
||v.fwmrm.net^
||spotxchange.com^
||tremorhub.com^
```

(Generic Google ad hosts are already covered by any standard list like HaGeZi.)

---

## Scope — On-Demand vs Live TV

- **On-demand (VOD): fully removed.** Ad periods dropped, timeline re-based to the
  true runtime, resume bookmarks re-mapped so they land in the right place.
- **Live TV (linear channels): masked, not removed.** Black screen + mute for each
  break via the optional patch. Same fundamental limit as any live broadcast.

---

## Testing / Results

Verified on an **Onn 4K Plus** (Android 14), Pluto **`5.66.0-leanback`**: multiple
on-demand movies and shows, runtime drops to the real length, mid-rolls gone, and
playback stays smooth across every spot where an ad break used to be. Loads fast, no
delay. Detection fix (#144) confirmed on both un-DRM'd (*Supermarket Sweep*) and
DRM'd (*Workaholics*) titles.

---

## 🙏 Credits

Huge thanks to the Mod Team, to RookieEnough and hoo-dles for building such a great
community around Morphe, and to everyone who has tested and given feedback. The
debugging on this one was quick, but only because of everything the community has
documented before it. Every lesson from this build is in the repo. See you in the
next one!

---

*Built with a growth mindset: Pluto "should" have been unbeatable — SSAI, the same
stitched-ad problem that walls off Prime Video. It wasn't, for one reason: the wall
was a door the app has to walk through too. To play a title, media3 must be handed a
manifest it can read, and that manifest spells out, in plain periods, exactly which
chunks are content and which are ads.*
