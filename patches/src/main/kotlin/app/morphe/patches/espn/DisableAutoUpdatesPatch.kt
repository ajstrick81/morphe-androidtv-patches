package app.morphe.patches.espn

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// Disable Auto Updates (ESPN Android TV)
//
// Prevents the Google Play Store from silently updating and replacing the
// patched ESPN APK with the official unpatched version — which would restore the
// ad stack and wipe the slate feature with no warning.
//
// There is no manifest attribute that turns off Play Store auto-updates: update
// eligibility is decided entirely by Play Store comparing versionCode against
// what's installed. So this patch bumps android:versionCode on the <manifest>
// root far past the real app's value; Play Store only offers an update when its
// listed versionCode is higher than what's installed, so keeping the patched
// build's versionCode artificially ahead makes it look already up to date.
//
// You can still update deliberately by re-patching a newer APK in Morphe. Does
// not apply to mount-installed apps.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val disableAutoUpdatesPatch = resourcePatch(
    name = "Disable auto-updates",
    description = "Stops the Google Play Store from silently updating ESPN back to the official " +
        "version and wiping out the patch (which would bring the ads back). Works by setting the " +
        "patched build's version number far ahead of anything on the Store, so it's treated as " +
        "already up to date. You can still update deliberately by re-patching a newer APK in " +
        "Morphe. Recommended to leave ON. Does not apply to mount-installed apps.",
) {
    compatibleWith(AppCompatibilities.ESPN_TV)

    execute {
        // Bump android:versionCode on <manifest> past Play Store's real value.
        // Clamped to Int.MAX_VALUE since versionCode is a 32-bit signed field.
        document("AndroidManifest.xml").use { document ->
            val manifestNode = document
                .getElementsByTagName("manifest")
                .item(0)

            val versionCodeAttr = manifestNode
                .attributes
                .getNamedItem("android:versionCode")

            val bumpedVersionCode = (versionCodeAttr.nodeValue.toLong() + 10_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())

            versionCodeAttr.nodeValue = bumpedVersionCode.toString()
        }
    }
}
