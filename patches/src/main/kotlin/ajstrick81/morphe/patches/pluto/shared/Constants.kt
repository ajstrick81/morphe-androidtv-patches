package ajstrick81.morphe.patches.pluto.shared

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

    // Pluto v17.x is a ground-up rebuild on the Paramount+/CBS AVIA player stack
    // (same package name, entirely different internals). Every leanback "Skip ads"
    // seam is gone, so the v17 ad-removal patch targets this DISJOINT version set and
    // the two generations coexist: the CLI's version gating drops whichever generation
    // doesn't match the APK being patched. Targets 17.3.0 (issue #300), the version
    // semicoloncolonel's tv-morphe-patches validated this hook set on. Pick the
    // Android TV build (version code 52…), not the phone build (42…).
    val COMPATIBILITY_V17 = Compatibility(
        name = "Pluto TV Android TV",
        packageName = "tv.pluto.android",
        appIconColor = 0xB8B004,
        targets = listOf(AppTarget("17.3.0"))
    )
}
