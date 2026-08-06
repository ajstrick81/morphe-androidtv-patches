> # 🗄️ ARCHIVED — Historical Reddit announcement (July 2026)
>
> **Status: superseded. Kept for transparency, not for installation.**
>
> This is the original Reddit post that introduced the Prime Video Android TV
> patch. It documents the reverse-engineering journey and the **first** working
> approach: a pair of Java-bytecode hooks on ExoPlayer/media3's
> `ServerSideAdInsertionMediaSource.setAdPlaybackStates()`, pitched as a
> self-contained, "no DNS required" fix.
>
> **Why it's archived:** the code and methods have since evolved, and parts of
> this post no longer describe what ships. In the spirit of the project's
> growth-mindset philosophy — *walls are usually not walls, expect adversity and
> keep going* — the `setAdPlaybackStates` bytecode hook alone turned out **not**
> to be a complete solution against Prime Video's WASM/Ignite ("Megablast")
> runtime. Where this post and today's patch diverge:
>
> - **Primary mechanism changed.** The current ad strip is a **native,
>   in-process hook** (`libpvhook.so`) that blanks *Remote* (ad) items from
>   libignite's PRS `intraTitlePlaylist` via a `memcpy`/`memmove` GOT/PLT import
>   hook — *before* the WASM runtime ever hands a schedule to the player. The
>   `setAdPlaybackStates` media3/ExoPlayer2 hooks described below still ship, but
>   as a *secondary* control-plane layer alongside impression-report and Volley
>   ad-host suppression — not as the whole story.
> - **TV shows are now covered.** This post is almost entirely about movies. The
>   current patch strips **both movie and TV-show** prerolls and mid-rolls.
> - **The DNS guidance in this post is self-contradictory** (note the mid-post
>   "DISREGARD — DNS is needed" reversal). The shipping patch needs **no DNS
>   rules, no VPN, no proxy** — see the current announcement for the definitive
>   guidance.
>
> **➡️ For the up-to-date write-up and install steps, read
> [`docs/PRIME_VIDEO_ANNOUNCEMENT.md`](../PRIME_VIDEO_ANNOUNCEMENT.md) and the
> [repo README](../../README.md).**
>
> Everything below this line is preserved verbatim as it was originally posted.

---

Presenting - The Prime Video Android TV Patch for Onn 4K TV
Guide/Useful
Hey everyone!
Long time coming on this one. Prime Video ATV has been the most challenging and fascinating reverse engineering project I've tackled so far, and I want to document the full journey here because the architecture is unlike anything else in the streaming ecosystem. A lot of wrong turns, a lot of learning, and ultimately a clean result that I'm proud of.

Same disclaimer as always: I am not a coder. I work in healthcare and have used Claude extensively as a collaborative research and coding partner throughout this project. We've spent months analyzing bytecode, running PCAPdroid captures, DNS examination, dissecting DEX files, running logcat analysis, and building — then sometimes reverting — various approaches until we found what actually works cleanly. If AI-assisted patches aren't your thing, totally fair. But the methodology is sound and the result speaks for itself.

The Most Important Thing You Need to Know First
Prime Video ATV is architecturally unique among streaming apps, and understanding why is critical to understanding both the patch and its limitations.

Most streaming apps keep their ad logic in Java bytecode — IMA SDK calls, FreeWheel integrations, SSAI client code. You find the right class, inject a hook, done. Prime Video ATV does something completely different. The app is a shell. The appBootstrap.js bundled inside the APK is literally 0 bytes — a placeholder. The actual application logic, including ALL ad scheduling, is a WebAssembly (WASM) bundle downloaded fresh from Amazon's CDN every single session. Amazon calls this the Ignite/Megablast runtime.

This means you cannot patch the ad logic directly because it doesn't exist in the APK. What you CAN patch is the boundary between the WASM runtime and the standard Android media player (ExoPlayer/media3) that ultimately plays the video. That boundary is where our hook lives.

The Discovery That Made This Work
After extensive DEX analysis, logcat forensics, and PCAPdroid captures, we mapped the exact chain of events during an ad break:

Amazon WASM Runtime (downloaded at session start)
    ↓ pushes SSAI ad schedule as AdPlaybackState map
setAdPlaybackStates(ImmutableMap, Timeline)   ← THIS IS OUR HOOK
    ↓ ExoPlayer media3 receives ad groups
    ↓ ExoPlayer fetches ad segments from Akamai CDN
    ↓ Ads play
ServerSideAdInsertionMediaSource.setAdPlaybackStates() is the transfer point where the WASM runtime hands the ad schedule to ExoPlayer. It's in the unobfuscated androidx.media3 library — which means it's a stable target that survives APK updates as long as Amazon continues using media3. Our extension intercepts this map and calls withRemovedAdGroupCount(adGroupCount) to strip every ad group before ExoPlayer ever sees it.

What the Patch Does
Two hooks. No DNS rules required. No network configuration needed.

Hook 1 — media3 ServerSideAdInsertionMediaSource.setAdPlaybackStates()

The primary intercept. Called every time the WASM runtime pushes an ad schedule into ExoPlayer. Our Java extension iterates the AdPlaybackState map and removes all AdGroups via withRemovedAdGroupCount() before ExoPlayer processes them. ExoPlayer receives a clean map with no ad groups — no ads are scheduled, no segments are requested.

Hook 2 — ExoPlayer2 ServerSideAdInsertionMediaSource.setAdPlaybackStates()

The Google Mobile Ads SDK bundles its own copy of ExoPlayer2 alongside media3. This hook covers the same suppression for the ExoPlayer2 SSAI pipeline, ensuring both delivery paths are covered.

Logcat Confirmation — What's Actually Happening
After testing across 4 different movies in a single session, the logcat tells a clear story:

60 instances of:

[playback::machine] [0] not creating envelope refresher as no correlation id present on envelope
This is the WASM runtime trying to synchronize ad state with ExoPlayer and failing because our hook stripped the correlation IDs. This is the patch working.

16 instances of:

[playback] start_with_options ignored: requested src matches the currently loaded src
After failing to find correlation IDs, the WASM tries to restart playback to re-acquire the ad schedule. ExoPlayer ignores the restart because the source is already loaded. This is the patch working.

3 instances of:

Header visibility update rejected — conflicting lock found
active locks: [(sudo_header_controller, ...)]
The WASM's ad overlay UI (the "Ad 1 of 2" header) attempts to render and gets blocked by the player's own lock mechanism. The interactive cart/purchase overlay (InteractiveVideoAd) appears zero times across the entire session.

Zero contact with any ad CDN — Akamai, TERS SGAI stitching server, s.amazon-adsystem.com impression beacon, Surface-X ad orchestration scheduler. All absent from the entire session. The patch intercepts before any network request is made.

Update!!!! DISREGARD THE PART BELOW - DNS IS NEEDED:
Use this filter list for your DNS list written in AdGuard syntax: https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/dns/prime-video.txt

Important — Do NOT Run DNS Filtering Alongside This Patch

This is the lesson that cost the most sessions to learn. AGH (AdGuard Home) and AGP (AdGuard Pro) both cause problems with Prime Video ATV when active alongside the bytecode patch.

AGH adds DNS resolution latency that disrupts Prime Video's Ignite WASM session initialization. The session init fires dozens of concurrent DNS requests at launch — even small delays cause cascading failures that crash the app. We went through five versions of AGH rules trying to make this work before concluding that the app simply doesn't tolerate added DNS latency.

AGP is worse — its local VPN disrupts the ExoPlayer pipeline that our patch depends on. Running AGP alongside the patch produced MORE ads than the patch alone, because the VPN layer interfered with the very delivery chain we were intercepting.

The bytecode patch is self-contained. No network configuration needed. No DNS rules. No VPN. Just the patched APK.

The Belgium VPN Curiosity (Interesting Finding, Not a Recommendation)
One fascinating discovery during research: connecting via a Belgian VPN completely eliminates ads with no patch at all. Belgium's GDPR enforcement means Amazon's server sends a WASM bundle with no ad code whatsoever — the Ignite runtime simply never initializes an ad engine. Zero WASM activity, zero ad CDN contact, zero ads.

This is entirely server-side — Amazon makes this decision based on IP geolocation. Prime Video will detect your VPN and it might not work. I used NordVPN, cleared the cache, force stopped the app, and reopened it successfully.

The problem with connecting to Belgium is many of the US shows/movies you would enjoy are not available. It's a cool trick, but the content isn't as good.

Device Compatibility — Please Read Before Installing
This patch was built and validated specifically on Onn 4K Android TV (US account, ad-supported tier, v6.23.23+v15.5.0.70-armv7a). Here's honest guidance on other devices:

✅ Confirmed Working
Onn 4K Android TV — full test suite, multiple sessions, logcat verified

⚠️ Results May Vary
Fire TV devices (any model) The Fire TV version of Prime Video is a completely different app: com.amazon.firebat vs our target com.amazon.amazonvideo.livingroom. The package names are entirely different. While our DEX analysis confirmed the same setAdPlaybackStates method exists in the Fire TV build, several factors make success uncertain:

Fire TV uses the PyroCore framework for device integration which doesn't exist in the Android TV build

Amazon's server almost certainly sends a Fire TV-specific WASM bundle, not the Android TV bundle our patch was calibrated against

The WASM ad delivery chain on Fire TV may use different timing or callback patterns that our hook doesn't intercept at the right moment

Some users have reported sideloading the patched Android TV APK onto Fire TV with the patch APK taking priority over the system version — results vary by device and account

If you try it on Fire TV and it works, please report back — that data helps the whole community.

Nvidia Shield Android TV but uses arm64-v8a architecture vs our tested armeabi-v7a. The fingerprints target class names and method signatures that should be identical across architecture slices, but this combination is untested. Low risk to try, but unverified.

Google TV built-in smart TVs (Sony, TCL, Hisense, etc.) Prime Video on built-in Google TV smart TVs typically ships as a system app in a protected partition. Sideloading a patched version may conflict with the system version. Additionally Google TV's process management layer can interfere with how patched apps behave. Dedicated streaming dongles (Chromecast with Google TV, TiVo Stream 4K) have better odds since Prime Video isn't a system app on those.

Non-US accounts Amazon operates separate regional infrastructure. The SSAI stitching endpoints, ad CDN routing, and WASM bundle content all vary by region. The patch operates at the ExoPlayer layer which is globally consistent, but the specific WASM behavior may differ. Users in regions where Amazon's ad-supported tier hasn't launched will see no ads regardless of the patch.

❌ Not Compatible
Fire TV Stick specifically — different package name, different app, different architecture. The patch as written will have no effect. I've only come across one user that was able to implement it effectively but most people report issues either installing it or ads playing normally. Sorry, but I cannot create and maintain a patch for FireTV.

A Note on Account Flagging
One hard-learned lesson: Amazon's ad system responds to suppression patterns. If you aggressively block ad delivery over many sessions — especially using DNS blocking that causes failed network requests — Amazon's server-side logic can increase ad load in subsequent sessions to compensate for impression deficits.

The bytecode patch avoids this because it leaves no suspicious network fingerprint. From Amazon's servers, your session looks completely normal — requests are made, sessions are established — the interception happens entirely inside the local app process. This is the fundamental advantage of bytecode patching over DNS/network blocking for this specific app.

If you've been experimenting with aggressive DNS blocking and notice heavier than expected ads after installing this patch, try:

Reset your advertising ID: Settings → Privacy → Ads → Reset advertising ID

Deregister and re-register the device on amazon.com

Clear Prime Video data: Settings → Apps → Prime Video → Storage → Clear Data

Give it a few fresh sessions before concluding the patch isn't working.

Installation
Add the repo to Morphe: https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches

Step by step:

Download Prime Video Android TV APK v6.23.23 (armv7a) from APKMirror to your phone or tablet. You want the .apkm bundle file.

Load the APK into Morphe Manager and select the Skip ads patch

Important: Disable the "Morphe patches" option before patching — leaving it enabled activates a package rename that will cause INSTALL_FAILED_UPDATE_INCOMPATIBLE

Allow patching to complete, then save the patched APK using the bottom right corner save option (do not click install directly)

Use Send Files to TV app to transfer the apk from your phone/tablet to the device

Sign into your account and test

Enjoy the ad-free TV shows/movies!

No DNS rules. No additional configuration. Just the patched APK.

What's Next
The same setAdPlaybackStates approach used here was already confirmed working in our Peacock patch. The methodology of finding the ExoPlayer SSAI boundary and intercepting the AdPlaybackState map before the player sees it is transferable to any app using media3 for server-side ad insertion. The full DEX analysis, logcat methodology, and PCAPdroid findings are all documented in the repo for anyone who wants to dig into the architecture.

Huge thanks to the Mod Team, and to RookieEnough and u/hoo-dles for building such a solid community around Morphe and for the reference implementations that helped shape the methodology here. Hoodles' Prime Video Mobile patch in particular — the FSM state intercept approach — was a major conceptual influence even though the ATV architecture ended up being completely different.

See you in the next one!
