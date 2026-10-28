package ajstrick81.morphe.patches.raiplay.shared

// RaiPlay 5.0.1 support (target + re-verified seam) contributed by Michael Bompani (github.com/michaelbompani).

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "RaiPlay Android TV",
        packageName = "it.rainet.androidtv",
        appIconColor = 0x7A2FF7,
        targets = listOf(AppTarget("5.0.1"), AppTarget("5.0.0"))
    )
}
