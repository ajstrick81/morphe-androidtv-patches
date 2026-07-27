package ajstrick81.morphe.patches.purpletv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Purple TV (https://github.com/nyanarchive/purpletv) is itself a patch layer
// applied on top of the stock, mobile-only Twitch APK (package
// tv.twitch.android.app). There is no official Android TV build of Twitch to
// target, unlike every other app in this repo — so this patch is expected to
// be applied to an already Purple-TV-patched APK, and no specific version has
// been verified on an Android TV device yet. Update/expand the target list
// below once a version has actually been tested.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Twitch (Purple TV)",
        packageName = "tv.twitch.android.app",
        appIconColor = 0x9146FF,
        targets = listOf(AppTarget("UNTESTED")),
    )
}
