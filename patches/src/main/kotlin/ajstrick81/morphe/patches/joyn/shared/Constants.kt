package ajstrick81.morphe.patches.joyn.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Joyn Android TV",
        packageName = "de.prosiebensat1digital.seventv",
        appIconColor = 0x000000,
        // AppTarget matches versionName verbatim, including the -ATV build suffix.
        targets = listOf(AppTarget("6.5.2-ATV-605282491")),
    )
}
