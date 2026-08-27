# 🗄️ ARCHIVED — Historical Reddit announcement (August 2026)

**Status: superseded. Kept for transparency, not for installation.**

This is the original Reddit post that introduced the HBO Max Android TV patch. It
documents the reverse-engineering journey and the **first** working approach: six
Java-bytecode hooks that suppress HBO's ad *delivery/timeline* systems (Bolt,
SsaiInfoTimelineBuilder, GenerateLiveTimelineEntries, Nowtilus, …).

**Why it's archived:** the code and methods have since evolved, and one central
claim in this post turned out to be a wall that wasn't a wall. In the spirit of
the project's growth-mindset philosophy — *walls are usually not walls; expect
adversity and keep going* — the part below that says the free/ad-supported tier's
baked-in SSAI ads are **"structurally impossible to remove client-side… there is
no workaround"** is **no longer true**. Where this post and today's patch diverge:

- **The "impossible" baked ads are now removed.** HBO's ad-supported stream is
  server-stitched SSAI (ads encoded into the DASH segments), which the six
  bytecode hooks below could not touch — they strip ad *markers/timeline*, not the
  ad *video*. The current patch removes the ad video entirely by discovering that
  HBO itself ships a **second, ad-free manifest** for every ad-tier title (its own
  resiliency "fallback" stream) and switching the player to it. See the current
  write-up: [`docs/HBO_MAX_ANNOUNCEMENT.md`](../HBO_MAX_ANNOUNCEMENT.md).
- **No more "skip past the remaining ads with your remote."** Because no ad
  segments are fetched at all, there is nothing to skip — and no ad countdown, no
  seekbar lock, and no error when resuming into what used to be an ad break.
- **The APK target moved on.** This post targets `7.2.0.41`; the current patch is
  maintained against later Android TV builds (e.g. `7.9.0.61`).

**If this post and the current write-up ever disagree, the current write-up is
correct.**

---

## Original post (verbatim, August 2026 — u/ajstrick81, r/MorpheApp)

> **Introducing an Android TV Morphe patch for HBO Max**
>
> Hey Everyone!
>
> I'm back with another patch for the Android TV community, and this one was quite
> the journey! After successfully patching Paramount+ and helping move the Disney+
> patch to an Android TV-focused repo, I decided to take on HBO Max — and with a
> lot of help from AI, we got there!
>
> First, the same disclaimer as before: I am not a coder. I work in healthcare and
> have used Claude extensively to problem solve, analyze bytecode, and write the
> patch code. This was a deep collaborative process involving dex file autopsies,
> smali analysis, fingerprint matching, and a lot of trial and error. If
> AI-assisted patches aren't your thing, totally fair — but it works, and that's
> what matters!
>
> **What the patch does:**
>
> The HBO Max patch suppresses six distinct ad delivery systems built into the APK:
>
> * Bolt nonlinear overlay ads
> * BoltDynamicAdFetcher coroutine ad result
> * SsaiInfoTimelineBuilder SSAI linear ad timeline registration (VOD/movies)
> * The synthetic accessor that closes the lambda call path
> * GenerateLiveTimelineEntries for live and episodic TV prerolls
> * Nowtilus SSAI plugin initialization (MediaMelon CDN-level ad stitching)
>
> **Important — free tier limitation:**
>
> If you are on Max's free ad-supported tier, you will still see some ads. These
> are baked directly into the HLS/DASH video stream at WBD's origin CDN before the
> content ever reaches your device. They are structurally impossible to remove from
> the client side — no DNS block, no APK patch, and no client-side intercept can
> touch them because they are literally encoded into the video segments themselves.
> There is no workaround for this. The good news is that these remaining ads are
> skippable using your remote, so you can fast forward right past them.
>
> **This is NOT an app unlocker.**
>
> I want to be crystal clear about this: the patch does not grant access by
> unlocking premium features like 4k content, bypassing authentication, or giving
> you a subscription for free. You must have a valid Max account and active
> subscription to use the app. All this patch does is suppress the ad delivery
> systems for subscribers who prefer an ad-free experience. Please do not post
> asking how to get free access to content — that is not what this is.
>
> Add: https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches
>
> **Quick step by step:**
>
> 1. Download the specified Android TV APK bundle version here: HBO Max (Android TV) `7.2.0.41` to your phone/tablet
> 2. Load the apkm file into Morphe for patching (select "No, I already have an APK")
> 3. Allow the code to do its thing and then save the new patched APK file (don't click install — save in the bottom right corner)
> 4. Delete the old APK from your Android TV device before installing the new one
> 5. Option 1: Connect your phone to your Android TV device via ADB and perform a streamed install
> 6. Option 2: Use the "Send Files to TV" app to transfer the patched APK to the Android TV device
> 7. Enjoy mostly ad-free Max on Android TV!
>
> Huge thanks to RookieEnough and Hoodles for being the inspiration and for
> building such a great community around Morphe. And of course thank you to
> everyone who has tested and given feedback. See you in the next one!
