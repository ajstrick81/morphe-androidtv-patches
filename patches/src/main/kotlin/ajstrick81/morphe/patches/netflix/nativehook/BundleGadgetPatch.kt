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

        // ── 2. Generate the gadget config next to the .so ────────────────────
        // frida-gadget auto-loads "<soname>.config.so" from the same dir. In
        // "script" interaction mode it runs the JS at `path` on load with no
        // host attach required (works in a pure listen-less capture run). The
        // script is pushed separately to /data/local/tmp (see frida/README.md);
        // on_change=reload lets you iterate the script without reinstalling.
        val config = """
            {
              "interaction": {
                "type": "script",
                "path": "/data/local/tmp/dump_appboot.js",
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
            application.setAttribute("android:extractNativeLibs", "true")
            // debuggable=true makes the dumps in the app's private files dir
            // pullable via `run-as` without root (see frida/README.md Step 4).
            application.setAttribute("android:debuggable", "true")
        }
    }
}
