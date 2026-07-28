package ajstrick81.morphe.patches.purpletv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Targets the stock, mobile-only official Twitch APK (package
// tv.twitch.android.app) directly — there is no official Android TV build of
// Twitch to target, unlike every other app in this repo. v30.2.2
// (versionCode 3002026) has been decompiled and analyzed (dex-level fingerprint
// targets confirmed present), but no patch in this package has been verified
// working on an actual Android TV device yet. These patches are independent of
// and can be combined with Purple TV (https://github.com/nyanarchive/purpletv),
// which does its own separate bytecode patching of the same base APK.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Twitch",
        packageName = "tv.twitch.android.app",
        appIconColor = 0x9146FF,
        targets = listOf(AppTarget("30.2.2")),
    )
}
