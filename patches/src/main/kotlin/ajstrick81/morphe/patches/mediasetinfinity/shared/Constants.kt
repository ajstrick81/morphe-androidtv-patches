package ajstrick81.morphe.patches.mediasetinfinity.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // Mediaset Infinity Android TV (it.mediaset.infinitytv). The Kotlin shell
    // (it.mediaset.mediasetplay.ctv.*) is NOT obfuscated as of 8.1.13.
    val COMPATIBILITY = Compatibility(
        name = "Mediaset Infinity Android TV",
        packageName = "it.mediaset.infinitytv",
        appIconColor = 0x7D26DC,
        // Autopsied against 8.1.13 (versionCode 24121109). Not yet device-verified.
        targets = listOf(AppTarget("8.1.13")),
    )
}
