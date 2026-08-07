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
        // frida-gadget auto-loads "<soname>.config.so" from the same dir.
        //
        // We use "listen" (not "script"): "script" mode reads its JS from a
        // filesystem path, but on a non-root device the only writable spot the
        // app can also READ is its own files dir — /data/local/tmp is blocked by
        // SELinux (untrusted_app can't read shell_data_file). "listen" sidesteps
        // that: the gadget opens a local TCP port and we drive it from the PC
        // over `adb forward` with the frida CLI/python (no root, no on-device
        // script file, live hot-reload of scripts).
        //
        // "on_load":"wait" BLOCKS the process at gadget load (Application.onCreate
        // index 0) until a client connects — essential so hooks are installed
        // BEFORE Netflix's native init (nativeGibbonStartup) runs.
        //   host:   adb forward tcp:27042 tcp:27042
        //           frida -H 127.0.0.1:27042 -n Gadget -l <script.js>
        val config = """
            {
              "interaction": {
                "type": "listen",
                "address": "127.0.0.1",
                "port": 27042,
                "on_port_conflict": "fail",
                "on_load": "wait"
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
