package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.primevideo.shared.Constants
import ajstrick81.morphe.patches.shared.arch.requireNativeLib

// ─────────────────────────────────────────────────────────────────────────────
// Bundles the prebuilt native ad-strip library into the APK.
//
// This is the piece that had no precedent in the repo — the DEX patches never
// shipped a native .so. It uses the same resource-file API the ViX
// CertificatePinningPatch uses (get(path) -> java.io.File -> write), except it
// writes a binary into lib/<abi>/ instead of XML into res/.
//
// libpvhook.so must be a bundled patch resource so it's on the classpath at
// patch time:
//
//   patches/src/main/resources/native/armeabi-v7a/libpvhook.so
//
// CI and release compile it from source (experimental/primevideo-libignite-
// native/jni/, NDK r28c, after its host unit test passes) and install it over
// that path before packaging, so a released bundle always matches the source.
// The committed copy is what a local Gradle build packages.
//
// Target ABI is armeabi-v7a only, per Constants (6.23.23+v15.5.0.70-armv7a).
// Add arm64-v8a here too if/when a 64-bit target ships.
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
        // Fail clearly on an architecture-optimized input (no armeabi-v7a engine lib)
        // instead of producing an APK that crashes on the TV.
        requireNativeLib(appName = "Prime Video", mainLib = "libignite.so")

        // ── 1. Copy the prebuilt .so into lib/armeabi-v7a/ ───────────────────
        // Read the bundled binary from the patch classpath. object{}.javaClass
        // resolves through the patches classloader; the leading '/' anchors to
        // the resources root (patches/src/main/resources/).
        val abi = "armeabi-v7a"
        val resourcePath = "/native/$abi/libpvhook.so"

        val soBytes = object {}.javaClass.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("bundleNativeHookPatch: $resourcePath not found on the patch " +
                "classpath — build ../jni and check the .so into " +
                "patches/src/main/resources/native/$abi/")

        // get(path) returns a File in the decoded-APK workspace. The lib/<abi>/
        // directory may not exist yet (apps with no bundled libs, or a
        // different ABI set), so ensure the parent before writing.
        get("lib/$abi/libpvhook.so").apply {
            parentFile?.mkdirs()
            writeBytes(soBytes)
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
