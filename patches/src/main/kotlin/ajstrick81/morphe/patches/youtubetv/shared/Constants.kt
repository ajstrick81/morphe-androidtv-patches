package ajstrick81.morphe.patches.youtubetv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "YouTube Android TV",
        packageName = "com.google.android.youtube.tv",
        appIconColor = 0xFF0000,
        targets = listOf(
            // Tested on a MiTV-AFMU0 (Android 14, armeabi-v7a).
            AppTarget("7.31.300"),
            AppTarget("7.05.301"),
        )
    )
}
