package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.misc.contexthook.clientContextHookPatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

// The user-facing "Skip ads" entry. YouTube for Android TV bundles no ad code
// of its own — the leanback UI/player/ads are served from youtube.com/tv and run
// in native Cobalt (see analysis/youtube-tv/711300320/). So there is no local
// ad-delivery method to stub; suppression is achieved entirely by the client
// context hook, which makes YouTube itself return an ad-free player response.
@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Suppresses video ads in YouTube Android TV by reporting the " +
        "InnerTube OS name as Android Automotive.",
) {
    dependsOn(clientContextHookPatch)
    compatibleWith(Constants.COMPATIBILITY)
    execute { }
}
