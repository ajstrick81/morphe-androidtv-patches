package ajstrick81.morphe.patches.xumo.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // Xumo Play Android TV (com.xumo.xumo.tv). Unobfuscated as of 4.9.147.
    // accent color from the app's brand palette (Xumo green/blue ~ 0x0073E6 placeholder).
    val COMPATIBILITY = Compatibility(
        name = "Xumo Play Android TV",
        packageName = "com.xumo.xumo.tv",
        appIconColor = 0x0073E6,
        // Tested against 4.9.147 (versionCode 147)
        targets = listOf(AppTarget("4.9.147")),
    )
}
