package ajstrick81.morphe.patches.purpletv.ads

import ajstrick81.morphe.patches.purpletv.shared.Constants
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

// ─────────────────────────────────────────────────────────────────────────────
// Spoof playerType on the playback access token request
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
@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Spoof player type (ad suppression)",
    description = "Overwrites the playerType value sent on Twitch's playback access token " +
        "request (normally \"mobile_player\" for live viewing) with an alternate declared " +
        "player context, aiming to receive a manifest variant the ad server doesn't insert " +
        "ads into. Ad decisioning happens server-side for this app — there is no client-side " +
        "ad event to intercept after the fact — so this is the only lever available at the " +
        "bytecode level. UNTESTED: the specific replacement value may need adjustment after " +
        "a real playback test; see the candidate list in SkipAdsPatch.kt.",
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
    }
}
