package ajstrick81.morphe.patches.primevideophone.misc.launcher

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"
private const val LEANBACK_CATEGORY = "android.intent.category.LEANBACK_LAUNCHER"

// ─────────────────────────────────────────────────────────────────────────────
// Add TV home-screen tile (LEANBACK_LAUNCHER)
//
// The Prime Video phone app's launcher entry point (an <activity-alias> named
// com.amazon.avod.thirdpartyclient.LauncherActivity -> SplashScreenActivity)
// declares only android.intent.category.LAUNCHER. The Google TV / Android TV
// launcher only surfaces apps whose launcher intent-filter also carries
// android.intent.category.LEANBACK_LAUNCHER, so the phone app installs but shows
// no home-screen tile — it can only be started via adb or a sideload launcher.
//
// This patch finds the intent-filter carrying the LAUNCHER category and adds a
// LEANBACK_LAUNCHER category alongside it, so a proper Prime Video tile appears.
// Targeting "any filter that has LAUNCHER" (rather than the activity-alias by
// name) keeps it robust if the entry-point name shifts between versions.
//
// It also sets android:banner on <application> to the app's existing "prime_logo"
// drawable, so the tile is Prime-branded instead of a greyed placeholder. NOTE:
// prime_logo is 210x66 (~3.18:1); the leanback tile slot is 16:9, so it letterboxes
// or centre-crops. Acceptable for now; a purpose-built 16:9 banner would look right.
// The manifest declares no required touchscreen feature, so nothing else blocks the
// launcher from showing the tile.
//
// Runs in finalize {} (manifest write time). Opt-in (default = false).
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val leanbackLauncherPatch = resourcePatch(
    name = "Add TV home-screen tile",
    description = "Adds the LEANBACK_LAUNCHER category to the launcher activity so the phone " +
        "app shows a tile on the Google TV / Android TV home screen instead of being hidden. " +
        "Opt-in.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    finalize {
        document("AndroidManifest.xml").use { document ->
            // Google TV renders the home-screen tile from android:banner (a 16:9
            // landscape image). The phone app declares none, so the tile shows a
            // greyed placeholder. Point it at the app's existing "prime" logo
            // drawable (a raster PNG that always renders) so a Prime-branded tile
            // appears instead. Set on <application> so it applies to the leanback
            // launcher activity without needing a per-activity attribute.
            (document.getElementsByTagName("application").item(0) as? Element)
                ?.setAttribute("android:banner", "@drawable/prime_logo")

            val intentFilters = document.getElementsByTagName("intent-filter")

            for (i in 0 until intentFilters.length) {
                val filter = intentFilters.item(i) as? Element ?: continue
                val categories = filter.getElementsByTagName("category")

                var hasLauncher = false
                var hasLeanback = false
                for (j in 0 until categories.length) {
                    val category = categories.item(j) as? Element ?: continue
                    when (category.getAttribute("android:name")) {
                        LAUNCHER_CATEGORY -> hasLauncher = true
                        LEANBACK_CATEGORY -> hasLeanback = true
                    }
                }

                if (hasLauncher && !hasLeanback) {
                    val leanback = document.createElement("category")
                    leanback.setAttribute("android:name", LEANBACK_CATEGORY)
                    filter.appendChild(leanback)
                }
            }
        }
    }
}
