package ajstrick81.morphe.patches.primevideophone.misc.launcher

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.util.Base64

private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"
private const val LEANBACK_CATEGORY = "android.intent.category.LEANBACK_LAUNCHER"

// The new banner is written here and referenced as @drawable/morphe_banner. nodpi
// so aapt keeps the single asset unscaled across densities.
private const val BANNER_RES_PATH = "res/drawable-nodpi/morphe_banner.png"
private const val BANNER_DRAWABLE = "@drawable/morphe_banner"

// PNG 8-byte signature — used to fail loudly if the embedded asset is corrupt.
private val PNG_MAGIC = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
)

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
// It also gives the tile a proper 16:9 banner. The earlier version pointed
// android:banner at the app's own "prime_logo" (res/CDe.png) — but that asset is
// 210x66 (~3.18:1), so Google TV centre-cropped it to "prim". Instead we inject a
// purpose-built 320x180 banner (the wordmark on a Prime-navy field, embedded in
// BannerAsset) as a NEW drawable and point the banner at it. Writing a new file
// under res/ and referencing it by @type/name is the same technique the cert-
// pinning patch uses for res/xml/network_security_config.xml — the patcher's
// resource recompile assigns it an id. If it fails to register, the aapt link
// step fails the whole build, so a broken banner reference can never ship.
//
// The drawable is written in execute {} (before resource compilation); the
// manifest edits run in finalize {}. Opt-in (default = false).
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val leanbackLauncherPatch = resourcePatch(
    name = "Add TV home-screen tile",
    description = "Adds the LEANBACK_LAUNCHER category to the launcher activity so the phone " +
        "app shows a tile on the Google TV / Android TV home screen instead of being hidden, " +
        "with a purpose-built 16:9 Prime banner. Opt-in.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        val bytes = try {
            Base64.getDecoder().decode(BannerAsset.BANNER_PNG_BASE64)
        } catch (e: IllegalArgumentException) {
            throw PatchException("Embedded launcher banner base64 is malformed", e)
        }
        // Fail loudly on a corrupt asset rather than writing a bad file that aapt
        // would compile into a broken tile.
        if (bytes.size < PNG_MAGIC.size ||
            !bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)
        ) {
            throw PatchException("Embedded launcher banner is not a valid PNG")
        }

        // res/drawable-nodpi/ may not exist in the decoded resources; create it.
        get(BANNER_RES_PATH).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    finalize {
        document("AndroidManifest.xml").use { document ->
            // Bump versionCode by 1. Google TV's LauncherX caches an app's tile
            // bitmap keyed by package and only re-fetches it when the app's
            // version changes — so a same-version reinstall keeps showing a stale
            // banner (confirmed on-device: survives both a reinstall AND a full
            // reboot; only `pm clear` of the launcher or a version bump refreshes
            // it). Making the patched app one code higher than stock means its
            // first install registers as an upgrade and the tile re-renders. The
            // app never receives Play updates, so a higher code is harmless.
            document.documentElement.let { manifest ->
                manifest.getAttribute("android:versionCode").toLongOrNull()?.let { code ->
                    manifest.setAttribute("android:versionCode", (code + 1).toString())
                }
            }

            // Google TV renders the home-screen tile from android:banner (a 16:9
            // landscape image). Point it at the injected 16:9 banner. Set on
            // <application> so it applies to the leanback launcher activity without
            // needing a per-activity attribute.
            (document.getElementsByTagName("application").item(0) as? Element)
                ?.setAttribute("android:banner", BANNER_DRAWABLE)

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
