package ajstrick81.morphe.patches.twitch.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Twitch",
        packageName = "tv.twitch.android.app",
        appIconColor = 0x9146FF,
        targets = listOf(AppTarget("30.2.2"))
    )
}
