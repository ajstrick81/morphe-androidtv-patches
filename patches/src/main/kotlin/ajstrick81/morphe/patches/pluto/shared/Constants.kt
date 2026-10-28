package ajstrick81.morphe.patches.pluto.shared

// Pluto TV 17.x compatibility (COMPATIBILITY_17 / COMPATIBILITY_ALL) contributed by Mathias Thur (github.com/AllGray).

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Pluto TV Android TV",
        packageName = "tv.pluto.android",
        // accent_color from APKMirror bundle metadata (info.json)
        appIconColor = 0xB8B004,
        // Tested against 5.66.0-leanback (versionCode 510400248)
        targets = listOf(AppTarget("5.66.0-leanback"))
    )

    // Pluto TV 17.x: a rebuild on Paramount+'s platform (see patches/pluto17). One versionName for both
    // the Android TV build (versionCode 520000962) and the phone/tablet build (420000962).
    val COMPATIBILITY_17 = Compatibility(
        name = "Pluto TV",
        packageName = "tv.pluto.android",
        appIconColor = 0xB8B004,
        targets = listOf(AppTarget("17.2.0"))
    )

    // For patches that only touch the manifest (Clone), so they work on 5.66 and 17.x alike.
    val COMPATIBILITY_ALL = Compatibility(
        name = "Pluto TV",
        packageName = "tv.pluto.android",
        appIconColor = 0xB8B004,
        targets = listOf(AppTarget("5.66.0-leanback"), AppTarget("17.2.0"))
    )
}
