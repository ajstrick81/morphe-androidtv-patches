package ajstrick81.morphe.patches.xumo.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.xumo.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Xumo Play Android TV — Skip ads  (issue #247)
//
// FIRST ATTEMPT — client-fetch (CSAI) ad path only, device-iteration expected.
//
// Xumo's player managers fetch and stitch VAST/AMS/IMA ads client-side in
// response to manifest cue markers. playAmsOrImaAd(PlayAms) is the final step
// that actually plays the fetched ad (switches the player to the ad media item).
// No-opping it at entry (return-void) means the ad is never played and content
// continues — the "empty the ad, don't break the flow the player waits on"
// approach: the surrounding state machine / beacons still run, only the ad
// playback is skipped. Applied to both the VOD (ExoPlayerManager) and Live
// (XumoTvInputPlayer) definitions.
//
// NOT addressed here: FreeWheel SSAI avails (server-stitched ads baked into the
// stream — see handleCueOutDetected / SSAIState "SSAI-FW"). Those are not
// removable client-side; if a given stream is SSAI, its ads will remain. The
// FeatureFlags.enableServerSideAdInsertionMigration flag means Xumo is migrating
// toward SSAI, so coverage may vary by content/region/version.
//
// NOT YET device-verified — needs on-device confirmation that VOD content
// resumes seamlessly (no spinner/hang) where this neuters the ad. If it hangs,
// move upstream: empty the parsed ad list (getVastAdXmlPullParserResult) so the
// break resolves to zero ads instead of suppressing the play step.
// ─────────────────────────────────────────────────────────────────────────────

private const val NOP_RETURN = "return-void"

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Removes Xumo Play's client-inserted (CSAI) VOD ads by no-opping the " +
        "player's ad-play step (playAmsOrImaAd) so fetched VAST/AMS/IMA ads are never " +
        "played and content continues. Applies to both the VOD and Live TV players. " +
        "FreeWheel SSAI ads that are server-stitched into the stream are not removable " +
        "client-side and may remain; Xumo is migrating toward SSAI, so coverage can vary " +
        "by title and region. Not yet device-verified on 4.9.147.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // VOD player — required. A miss here means the app changed its player,
        // so fail loudly rather than silently shipping a no-op patch.
        val vod = ExoPlayerPlayAmsOrImaAdFingerprint.methodOrNull
            ?: throw PatchException(
                "Could not find ExoPlayerManager.playAmsOrImaAd(PlayAms). This patch " +
                    "targets Xumo Play Android TV (com.xumo.xumo.tv).",
            )
        vod.addInstructions(0, NOP_RETURN)

        // Live TV (TIF) player — optional; some builds/branches may not carry it.
        TifPlayAmsOrImaAdFingerprint.methodOrNull?.addInstructions(0, NOP_RETURN)
    }
}
