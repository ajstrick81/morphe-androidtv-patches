package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// THE AD REMOVER (delivery half). Bundles a frida-gadget into com.netflix.ninja
// running in SCRIPT mode against the bundled ad-kill script (killads.js) — the
// gadget runs inside Netflix's own process at launch, so the ad kills apply with
// no PC, no root, and no frida server. (The same gadget primitive was originally
// stood up for non-root appboot capture; it now ships as the ad-strip delivery.)
//
// Writes into the decoded APK:
//   1. lib/armeabi-v7a/libgadget.so         — the frida-gadget binary (supplied)
//   2. lib/armeabi-v7a/libgadget.script.so  — the ad-kill script (killads.js)
//   3. lib/armeabi-v7a/libgadget.config.so  — gadget config (run the script at load)
//   4. manifest flip: extractNativeLibs=true (so the libs land in an app-readable dir)
//
// The gadget binary is NOT checked into the repo (large, ABI-specific, and not
// ours to redistribute). Drop the armeabi-v7a frida-gadget, renamed to
// libgadget.so, at:
//   patches/src/main/resources/netflix/native/armeabi-v7a/libgadget.so
//
// Companion to loadGadgetPatch, which injects the System.loadLibrary("gadget")
// call and dependsOn() this so the .so + script + config are in place first.
// Default ON.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val bundleGadgetPatch = resourcePatch(
    name = "Remove Netflix ads (bundle engine)",
    description = "Bundles the in-process ad-kill script (killads.js) and its loader into the " +
        "app. This is the ad remover: at launch the app runs the script itself (script mode — no " +
        "PC, no root, no frida server) and empties Netflix's ad breaks (pre-roll, mid-roll) and " +
        "the pause-screen ad overlay. Pairs with the loader patch. Default ON.",
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
