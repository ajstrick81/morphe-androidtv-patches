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
        // Both field-verified in Italy by @DarkSofter (#217): no ads on VOD episodes or live
        // channels on a free account. 8.1.20 is the current Play Store build.
        targets = listOf(AppTarget("8.1.20"), AppTarget("8.1.13")),
    )
}
