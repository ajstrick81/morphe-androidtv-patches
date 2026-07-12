package ajstrick81.morphe.patches.vix.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "ViX Android TV",
        packageName = "com.univision.prendetv",
        appIconColor = 0x00A0E9,
        targets = listOf(AppTarget("4.46.0_tv"), AppTarget("4.47.2_tv"))
    )
}
