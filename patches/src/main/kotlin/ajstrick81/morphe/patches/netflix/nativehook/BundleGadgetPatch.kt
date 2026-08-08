package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Bundles frida-gadget into com.netflix.ninja so we can capture the appboot UI
// bundle in-process on a NON-rooted Onn (no frida-server needed — the gadget
// runs inside Netflix's own process). Acquisition tooling for the reopened
// seam-A/B work; see experimental/netflix-native-adstrip/REOPENING.md and
// frida/README.md.
//
// Writes three things into the decoded APK:
//   1. lib/armeabi-v7a/libgadget.so         — the frida-gadget binary (supplied)
//   2. lib/armeabi-v7a/libgadget.config.so  — gadget config (generated here)
//   3. manifest flips: extractNativeLibs=true, debuggable=true
//
// The gadget binary is NOT checked into the repo (large, ABI-specific, and not
// ours to redistribute). Drop the armeabi-v7a frida-gadget, renamed to
// libgadget.so, at:
//   patches/src/main/resources/netflix/native/armeabi-v7a/libgadget.so
//
// SCAFFOLD — not registered in the build. Companion to loadGadgetPatch, which
// injects the System.loadLibrary("gadget") call and dependsOn() this so the
// .so + config are in place first.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val bundleGadgetPatch = resourcePatch(
    name = "Bundle frida-gadget (Netflix appboot capture)",
    description = "Packages libgadget.so + its config into com.netflix.ninja for " +
        "in-process, non-root capture of the appboot UI bundle (post-MSL, post-signature).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        val abi = "armeabi-v7a"

        // ── 1. Copy the supplied frida-gadget into lib/<abi>/ ────────────────
        val resourcePath = "/netflix/native/$abi/libgadget.so"
        val soBytes = object {}.javaClass.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("bundleGadgetPatch: $resourcePath not found on the patch " +
                "classpath — download the $abi frida-gadget, rename to libgadget.so, " +
                "and drop it at patches/src/main/resources/netflix/native/$abi/")

        get("lib/$abi/libgadget.so").apply {
            parentFile?.mkdirs()
            writeBytes(soBytes)
        }

        // ── 2a. Bundle the ad-kill script INSIDE the apk (next to the .so) ───
        // SHIPPABLE (self-contained): the gadget runs the script itself at load —
        // no PC, no frida-CLI. "script" mode normally can't read /data/local/tmp
        // (SELinux blocks untrusted_app from shell_data_file), so we place the
        // script in the app's OWN lib dir (named .so so extractNativeLibs
        // extracts it) and reference it by a RELATIVE path, which frida resolves
        // next to the gadget library — a location the app can always read.
        val scriptBytes = object {}.javaClass.getResourceAsStream("/netflix/native/killads.js")
            ?.use { it.readBytes() }
            ?: error("bundleGadgetPatch: /netflix/native/killads.js not found on the patch classpath")
        get("lib/$abi/libgadget.script.so").apply {
            parentFile?.mkdirs()
            writeBytes(scriptBytes)
        }

        // ── 2b. Gadget config: run the bundled script at load ────────────────
        // frida-gadget auto-loads "<soname>.config.so" from the same dir; here it
        // runs the bundled killads script (relative path -> resolved beside
        // libgadget.so in the extracted lib dir, which is app-readable). No
        // on_load:"wait", so the app launches NORMALLY and self-applies the kills.
        val config = """
            {
              "interaction": {
                "type": "script",
                "path": "libgadget.script.so",
                "on_change": "reload"
              }
            }
        """.trimIndent()
        get("lib/$abi/libgadget.config.so").apply {
            parentFile?.mkdirs()
            writeText(config)
        }

        // ── 3. Manifest flips ────────────────────────────────────────────────
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
                    as? org.w3c.dom.Element ?: return@use
            // base.apk ships extractNativeLibs="false"; injected libs that
            // aren't page-aligned fail to mmap → force extraction to sidestep.
            // Also required so the bundled killads script (libgadget.script.so)
            // is extracted to the app-readable lib dir for the gadget to load.
            application.setAttribute("android:extractNativeLibs", "true")
            // NOTE: shippable build is NOT debuggable — the ad-kill script
            // self-applies via the gadget and logs proof through liblog
            // (adb logcat, tags KILL/OBS), so run-as is not needed.
        }
    }
}
