package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.primevideo.shared.Constants
import ajstrick81.morphe.patches.shared.arch.requirePresentNativeAbis

// ─────────────────────────────────────────────────────────────────────────────
// Bundles the prebuilt native ad-strip library into the APK.
//
// This is the piece that had no precedent in the repo — the DEX patches never
// shipped a native .so. It uses the same resource-file API the ViX
// CertificatePinningPatch uses (get(path) -> java.io.File -> write), except it
// writes a binary into lib/<abi>/ instead of XML into res/.
//
// libpvhook.so must be a bundled patch resource so it's on the classpath at
// patch time, one per ABI:
//
//   patches/src/main/resources/native/armeabi-v7a/libpvhook.so
//   patches/src/main/resources/native/arm64-v8a/libpvhook.so
//
// CI and release compile both from source (experimental/primevideo-libignite-
// native/jni/, NDK r28c, after its host unit test passes) and install them over
// those paths before packaging, so a released bundle always matches the source.
// The committed copies are what a local Gradle build packages — and a committed
// copy going stale is not hypothetical: the armeabi-v7a blob sat at its
// 2026-09-19 build for two months, so #260's fix shipped in source but not in
// the .so any device ran (see #282). Never hand-edit these; rebuild them.
//
// BOTH ABIs ship. v15.5 (6.23.23) is armeabi-v7a only, but the v16 bundles
// (6.24.7 / 6.24.13) are allAbis, and on a 64-bit install the app loads arm64
// libignite — so a v7a-only hook is never loaded there and ads come back with no
// error, no crash and no log line. We inject into every ABI the input actually
// carries, which is also why this must not hard-require armeabi-v7a.
//
// Companion to loadNativeHookPatch, which injects the load() call and
// dependsOn() this so the .so is in place first.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val bundleNativeHookPatch = resourcePatch(
    name = "Bundle native ad-strip hook",
    description = "Packages libpvhook.so into the APK's native lib dir for the " +
        "in-process ad strip (libignite memcpy/memmove GOT/PLT import hook).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Fail clearly on an architecture-optimized input (no engine lib for any
        // ABI we know) instead of producing an APK that crashes on the TV.
        val abis = requirePresentNativeAbis(appName = "Prime Video", mainLib = "libignite.so")

        // ── 1. Copy the prebuilt .so into lib/<abi>/ for each present ABI ────
        // Read each bundled binary from the patch classpath. object{}.javaClass
        // resolves through the patches classloader; the leading '/' anchors to
        // the resources root (patches/src/main/resources/).
        //
        // A missing resource for one ABI is NOT fatal: it means this bundle was
        // built without that slice (a local Gradle build with no NDK run), and
        // the other ABI should still get its hook. Missing resources for ALL
        // present ABIs is fatal, because then the patch would silently do
        // nothing — exactly the failure this patch exists to avoid. CI and
        // release compile both slices before packaging, so a released bundle
        // always carries both.
        val written = abis.filter { abi ->
            val soBytes = object {}.javaClass.getResourceAsStream("/native/$abi/libpvhook.so")
                ?.use { it.readBytes() }
                ?: return@filter false

            // get(path) returns a File in the decoded-APK workspace. The
            // lib/<abi>/ directory may not exist yet, so ensure the parent.
            get("lib/$abi/libpvhook.so").apply {
                parentFile?.mkdirs()
                writeBytes(soBytes)
            }
            true
        }

        if (written.isEmpty()) {
            error("bundleNativeHookPatch: this APK carries libignite.so for " +
                "${abis.joinToString()} but the patch bundle has no libpvhook.so for " +
                "any of them — build ../jni for each ABI and install the result into " +
                "patches/src/main/resources/native/<abi>/libpvhook.so")
        }

        // ── 2. Guarantee loadLibrary can find it: extractNativeLibs=true ─────
        // If the app ships extractNativeLibs="false", the OS mmaps .so files
        // straight from the APK and requires them page-aligned & uncompressed;
        // an injected-then-repackaged .so that isn't aligned fails at runtime
        // with UnsatisfiedLinkError. Forcing extraction sidesteps alignment
        // entirely (libs are unpacked to nativeLibraryDir at install) at a
        // small install-size cost. Reliable choice for bring-up; revisit only
        // if you confirm the patcher's repackage step already aligns libs.
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
                    as? org.w3c.dom.Element ?: return@use
            application.setAttribute("android:extractNativeLibs", "true")
        }
    }
}
