package ajstrick81.morphe.patches.tubi.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Tubi Android TV",
        packageName = "com.tubitv",
        appIconColor = 0xE9181C,
        targets = listOf(AppTarget("10.28.5000"))
    )
}
