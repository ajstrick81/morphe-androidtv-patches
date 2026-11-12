package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.misc.contexthook.clientContextHookPatch
import ajstrick81.morphe.patches.youtubetv.misc.scripts.tvScriptsPatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Removes the ad schedule (adPlacements, adSlots, playerAds, " +
        "adBreakHeartbeatParams) from every InnerTube response before the YouTube TV leanback " +
        "web app (youtube.com/tv) reads it, and removes sponsored tiles (adSlotRenderer and the " +
        "promoted* renderers) from feeds. Also keeps the existing Android Automotive OS-name " +
        "spoof that suppresses video ads client-side.",
) {
    // The automotive spoof stays: it is orthogonal to the web-app script and is what suppresses
    // the player's own ad requests when the page-side strip is too late for the first response.
    dependsOn(clientContextHookPatch)
    // Shared, unnamed patch: PatchLoader skips unnamed patches, so this does not appear as its own
    // entry in the Manager. It injects the script and extends the APK with the extension bundle.
    dependsOn(tvScriptsPatch)

    compatibleWith(Constants.COMPATIBILITY)

    // merge the extension the injected smali calls into (TvScripts).
    extendWith("extensions/extension.mpe")

    // Nothing left to do here: the smali hooks are injected by tvScriptsPatch via dependsOn.
    execute { }
}
