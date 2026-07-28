package ajstrick81.morphe.patches.purpletv.ads

import ajstrick81.morphe.patches.purpletv.shared.Constants
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

// ─────────────────────────────────────────────────────────────────────────────
// Layer 1 — Spoof playerType on the playback access token request
//
// Background (see project research notes): Twitch's Android app plays back
// through the Amazon IVS Player SDK, and confirmed via direct dex analysis that
// none of the app's own Player.Listener implementations override the ad-break
// callbacks IVS exposes (onAdBreakStarted/onAdBreakEnded/onAdCreativeStarted/
// onAdTimeUpdate) — ad decisioning is not something the client reacts to during
// playback, it happens server-side when the stream's manifest is built, keyed
// off the playerType value sent on the StreamAccessTokenQuery / Usher request.
//
// The live-viewing default is PlayerType.NORMAL, which serializes to the
// literal string "mobile_player". This patch overwrites that value at
// construction time, matching the same class of technique documented for
// Twitch's web client (TwitchAdSolutions/vaft, Xtra's PlayerRepository):
// requesting playback under a different declared player context can return a
// manifest variant the ad server doesn't consider ad-eligible.
//
// Other known playerType wire values from the same enum (Lr440 in v30.2.2),
// in case NORMAL's replacement needs to change after testing:
//   PIP                          -> android_pip
//   PBYP                         -> picture-by-picture
//   BACKGROUND_AUDIO             -> background_audio
//   CLIPS_DEEP_LINK              -> clips_deeplink
//   CLIP                         -> clips
//   AUTOPLAY                     -> autoplay
//   PREVIEW_THEATRE_MODE         -> preview_theatre_mode
//   PRIME_VIDEO_PLAYER           -> prime_video_on_twitch_mobile
//   PRIME_VIDEO_PLAYER_MINIMIZED -> prime_video_on_twitch_mobile_minimized
//   CREATOR_MODE_PREVIEW         -> creator_mode_preview
//   MOBILE_DISCOVERY_FEED        -> mobile_feed
//   CREATOR_STORIES              -> creator_stories
//
// NOT YET VALIDATED ON DEVICE. Static analysis confirmed the hook point and
// that this app's own clientintegrity.a class only fetches/caches an
// attestation token reactively (on GQL_CHALLENGE / APP_BOOT / EXPIRATION /
// AUTH_CHANGE) rather than gating every request up front, so a spoofed
// playerType is not expected to be rejected outright — but whether Twitch's ad
// server actually serves an ad-free manifest for "background_audio" (or any
// other candidate above) on a live stream is untested. Try this patch first;
// if playback still shows ads, swap the literal below for another candidate
// from the table and re-test before concluding the approach doesn't work.
//
// hasAdblock (a sibling field on the same class) is NOT touched by this patch:
// confirmed via disassembly that neither of the two call sites that build this
// class (live playback, VOD/clips) pass an explicit value for it — it already
// defaults to absent on the wire, so there's nothing to spoof there.
// ─────────────────────────────────────────────────────────────────────────────
//
// ─────────────────────────────────────────────────────────────────────────────
// Layer 2 — Block the GrandDads ad-eligibility request
//
// This is a real, shipped technique, not a guess: a decompiled 2024 Purple TV
// build shows its own AdBlocker class short-circuiting Twitch's
// GrandDadsApiImpl.shouldDeclineAds at the very top of the method — before any
// GraphQL query is built or sent — returning a "declined" response
// immediately when their ad-block setting is on. That exact call chain
// (VideoAdManager -> AdEligibilityFetcher -> GrandDadsFetcher ->
// GrandDadsApiImpl -> GrandDadsQuery) was independently re-confirmed present
// and unpatched in this session's v30.2.2 official APK via the query's own
// document() string and the shared GraphQL repository class it flows through.
//
// What differs here from Purple TV's own approach: Purple TV's patch returns
// a specific Twitch-internal sealed response object
// (GrandDadsResponse.AdContextUnavailable). That class's obfuscated identity
// in v30.2.2 could not be pinned down from static analysis alone — its
// GraphQL Data-class fields are fully R8-renamed with no readable anchor
// (checked directly; see Fingerprints.kt Layer 2/3 comments) — and
// constructing the wrong sealed-class shape risks a ClassCastException where
// Purple TV's approach risks nothing, since Purple TV had the real class name
// to work with via their own toolchain.
//
// Instead, this patch makes the request method raise a Single.error(...)
// immediately, skipping the GraphQL call entirely. This is inferred safe
// rather than confirmed safe: ad-fetching code operating at Twitch's scale
// has to tolerate real network failures for this exact call (timeouts, no
// connectivity) constantly in production, so an error path is almost
// certainly already handled gracefully upstream (VideoAdManager /
// AdEligibilityFetcher) rather than crashing the player. This has NOT been
// confirmed on a real device.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Spoof player type + block GrandDads ad-eligibility request",
    description = "Two independent ad-suppression layers. Layer 1 overwrites the playerType " +
        "value sent on Twitch's playback access token request (normally \"mobile_player\" for " +
        "live viewing) with an alternate declared player context, aiming to receive a manifest " +
        "variant the ad server doesn't insert ads into. Layer 2 blocks the \"GrandDads\" " +
        "ad-eligibility GraphQL request that a decompiled Purple TV build confirms is the real " +
        "per-session ad-decisioning call, by making the request fail immediately instead of " +
        "reaching the network. Both layers are UNTESTED on a real device — see the comments in " +
        "SkipAdsPatch.kt for what's confirmed vs. inferred for each.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // p0 = this, p1 = playerType (String), p2 = hasAdblock/maid optional, p3 = defaults mask.
        // Overwriting p1 before the constructor body runs means the field gets
        // assigned our literal instead of whatever the caller passed in.
        PlaybackAccessTokenParamsConstructorFingerprint.method.addInstructions(
            0,
            """
                const-string p1, "background_audio"
            """.trimIndent(),
        )

        // Raise an error immediately instead of building/dispatching the
        // GrandDads query. The method's declared return type is the generic
        // Ljava/lang/Object; (Kotlin Function1 erasure), and Single<T> is a
        // valid Object, so this is bytecode-verifier-safe regardless of what
        // the caller does with the result.
        DeclineAdsRequestFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Ljava/lang/RuntimeException;
                invoke-direct {v0}, Ljava/lang/RuntimeException;-><init>()V
                invoke-static {v0}, Lio/reactivex/Single;->error(Ljava/lang/Throwable;)Lio/reactivex/Single;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )
    }
}
