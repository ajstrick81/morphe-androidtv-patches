package ajstrick81.morphe.patches.purpletv.misc.tv

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.purpletv.shared.Constants
import org.w3c.dom.Element

private const val LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"

// ─────────────────────────────────────────────────────────────────────────────
// Android TV Support (Purple TV)
//
// Twitch's mobile APK — the base that Purple TV patches — ships with no Android
// TV support at all: no leanback feature declaration, no banner, and no
// LEANBACK_LAUNCHER intent-filter, so the Android TV home screen's launcher row
// never surfaces it and the Play/Fire TV store filters it out entirely.
//
// This patch only clears the manifest-level gate that keeps the app off the TV
// launcher and out of installability on TV devices:
//   - Declares android.software.leanback as an optional (not required) feature,
//     and marks the touchscreen feature optional too, so device compatibility
//     checks (Play Store, package installers) stop excluding TV devices that
//     have no touchscreen.
//   - Adds the LEANBACK_LAUNCHER category to whichever activity already carries
//     the standard MAIN/LAUNCHER intent-filter, so the icon appears in the TV
//     launcher's app row.
//
// What this patch does NOT do: the mobile UI underneath is still touch-first
// and not built for D-pad navigation, and there's no TV banner asset bundled
// here (Android falls back to the app icon when a banner is absent, so this is
// cosmetic, not blocking). Real D-pad navigability requires UI-level work this
// patch does not attempt — see project notes for the SmartTube-derived TV UI
// effort being scoped separately.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val androidTvSupportPatch = resourcePatch(
    name = "Android TV support",
    description = "Makes the Twitch APK installable and visible on Android TV: declares the " +
        "leanback feature as optional, marks the touchscreen feature as not required, and adds " +
        "the TV launcher intent-filter to the app's main activity. Does not add D-pad navigation " +
        "to the existing touch-based UI — the app will install and launch on TV, but on-screen " +
        "navigation still assumes a touchscreen or a pointer-emulating remote.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val application = document.getElementsByTagName("application").item(0) as Element

            fun addOptionalFeature(featureName: String) {
                val alreadyDeclared = document.getElementsByTagName("uses-feature")
                    .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
                    .any { it.getAttribute("android:name") == featureName }

                if (alreadyDeclared) return

                val usesFeature = document.createElement("uses-feature")
                usesFeature.setAttribute("android:name", featureName)
                usesFeature.setAttribute("android:required", "false")
                manifest.insertBefore(usesFeature, application)
            }

            addOptionalFeature("android.software.leanback")
            addOptionalFeature("android.hardware.touchscreen")

            // Find the activity carrying the standard launcher intent-filter and
            // add the TV launcher category to that same filter.
            val activities = document.getElementsByTagName("activity")
            var patchedLauncher = false

            for (i in 0 until activities.length) {
                val activity = activities.item(i) as? Element ?: continue
                val intentFilters = activity.getElementsByTagName("intent-filter")

                for (j in 0 until intentFilters.length) {
                    val intentFilter = intentFilters.item(j) as? Element ?: continue

                    val hasMainAction = intentFilter.getElementsByTagName("action")
                        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
                        .any { it.getAttribute("android:name") == "android.intent.action.MAIN" }

                    val categories = intentFilter.getElementsByTagName("category")
                        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

                    val hasLauncherCategory = categories
                        .any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }

                    if (!hasMainAction || !hasLauncherCategory) continue

                    val alreadyHasLeanback = categories
                        .any { it.getAttribute("android:name") == LEANBACK_LAUNCHER }

                    if (!alreadyHasLeanback) {
                        val leanbackCategory = document.createElement("category")
                        leanbackCategory.setAttribute("android:name", LEANBACK_LAUNCHER)
                        intentFilter.appendChild(leanbackCategory)
                    }

                    patchedLauncher = true
                }
            }

            check(patchedLauncher) {
                "Could not find an activity with the standard MAIN/LAUNCHER intent-filter to " +
                    "add the Android TV launcher category to."
            }
        }
    }
}
