package ajstrick81.morphe.patches.mediaset.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // Android TV build only (leanback MainActivity under it.mediaset.mediasetplay.ctv).
    // The phone/tablet app is a different package (it.fabbricadigitale.android.videomediaset).
    val COMPATIBILITY = Compatibility(
        name = "Mediaset Infinity Android TV",
        packageName = "it.mediaset.infinitytv",
        appIconColor = 0x1A6BFF,
        // Experimental until a viewer in Italy confirms playback on-device (#217):
        // the content is geo-restricted, so it can't be fully verified from the US test TV.
        targets = listOf(AppTarget("8.1.13", isExperimental = true)),
    )
}
