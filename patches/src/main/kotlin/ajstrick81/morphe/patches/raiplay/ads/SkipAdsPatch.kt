package ajstrick81.morphe.patches.raiplay.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.raiplay.shared.Constants

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Suppresses Smartclip VOD ads in the RaiPlay Android TV app by forcing the " +
        "native ad-context parser to return null, so every title takes RaiPlay's own ad-free " +
        "playback path — no Smartclip session, no VMAP ad slot, no pre/mid/post-roll breaks. " +
        "Live-channel server-side ads (if any) are not affected.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Force NativePlaybackRequestParser.parseAdContext(JSONObject) to return
        // null. null is the app's OWN no-ads signal (the method already returns it
        // when ads are "enabled":false / provider != "smartclip" / no adTagUrl), so
        // this routes every playback into RaiPlay's supported ad-free path — the
        // Smartclip ad session is never created. The early return also sits ahead of
        // the method's own throw branches (missing_ad_tag_url / unsupported_ad_provider),
        // so those cannot fire. methodOrNull so a future re-anchor miss degrades
        // gracefully instead of aborting the whole patch run.
        ParseAdContextFingerprint.methodOrNull?.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
}
