package ajstrick81.morphe.patches.youtubetv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "YouTube TV",
        // YouTube TV / YouTube Unplugged (Android TV)
        packageName = "com.google.android.apps.youtube.unplugged",
        // Tested against 10.33.2 (versionCode 210330210), 2-arch APKM
        targets = listOf(AppTarget("10.33.2"))
    )
}
