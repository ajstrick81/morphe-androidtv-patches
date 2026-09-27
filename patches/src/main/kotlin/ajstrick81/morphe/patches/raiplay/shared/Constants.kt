package ajstrick81.morphe.patches.raiplay.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "RaiPlay Android TV",
        packageName = "it.rainet.androidtv",
        appIconColor = 0x7A2FF7,
        targets = listOf(AppTarget("5.0.0"))
    )
}
