package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants

@Suppress("unused")
val liveSlatePatch = bytecodePatch(
    name = "Mask live ad breaks (black screen + mute)",
    description = "Masks Pluto TV LIVE/linear commercial breaks, which are real broadcast time " +
        "stitched into the live feed and cannot be removed (unlike VOD ads, which \"Skip ads\" " +
        "deletes outright). During a live break it covers the player with a black screen and mutes " +
        "the audio, then restores both the instant the show returns — so gambling, drinking, or any " +
        "other live ad is blanked and silenced. Detection is driven by Pluto's own ID3 ad-state flow " +
        "(ID3AdsBeaconTracker), which fires only while an ad plays, so it needs no polling and keeps " +
        "working alongside \"Skip ads\". Opt-out: leave it OFF to watch live ads normally; runtime " +
        "controls are marker files in the app's external files dir — `slate_off` disables it, and " +
        "`pluto_slate_mode` set to `black` or `mute` picks cover-only or mute-only instead of both. " +
        "Validated on-device, 5.66.0-leanback.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // Bundle the extension the slate hooks call into (shared with "Skip ads").
    extendWith("extensions/extension.mpe")

    execute {
        // 1) Detector — ID3AdsBeaconTracker.consumeID3(ID3Tag). Every call = an ad is on
        //    screen right now; the helper shows the mask + mutes and re-arms a short hide
        //    timer, lifting when the ticks stop (content resumed). p1 = the ID3Tag.
        Id3ConsumeFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;" +
                "->onAdTick(Ljava/lang/Object;)V",
        )

        // 2) Overlay host — capture the stable single-Activity leanback host (p0) so the
        //    helper can reach the Activity content root at break time. Registering at
        //    onCreate (before the content view exists) is fine — the cover is added lazily.
        //    onDestroy clears it. Mask lifetime is driven ONLY by ID3 ticks, never by the
        //    controls view lifecycle.
        LeanbackHostOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;" +
                "->registerActivity(Landroid/app/Activity;)V",
        )
        LeanbackHostOnDestroyFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;" +
                "->unregisterActivity(Landroid/app/Activity;)V",
        )

        // 3) Mute handle — capture the AviaPlayer (constructor arg p1). p1 is a parameter
        //    register, valid at offset 0 even before the super() call. The helper reflects
        //    its media3 ExoPlayer `player` field to get/set volume (mute = 0f, restored).
        AviaControllerInitFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, Lajstrick81/morphe/extension/pluto/ads/PlutoLiveSlateHelper;" +
                "->setAviaPlayer(Ljava/lang/Object;)V",
        )
    }
}
