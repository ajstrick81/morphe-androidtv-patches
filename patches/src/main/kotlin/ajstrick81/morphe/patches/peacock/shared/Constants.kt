package ajstrick81.morphe.patches.peacock.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Peacock TV Android TV",
        packageName = "com.peacocktv.peacockandroid",
        appIconColor = 0x000000,
        targets = listOf(
            AppTarget("7.5.102"),
            AppTarget("7.6.100"),
        )
    )
}
