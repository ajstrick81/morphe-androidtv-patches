package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.primevideo.misc.extension.primeVideoExtensionPatch
import ajstrick81.morphe.patches.primevideo.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// DEX-side half of the in-process native interception: load libpvhook.so at
// startup so the native memcpy/memmove hooks (ShadowHook) install before the
// first playback session.
//
// Three patches cooperate:
//   bundleNativeHookPatch (resource) — writes libpvhook.so into lib/<abi>/
//   primeVideoExtensionPatch (bytecode) — merges the extension DEX that
//       contains NativeHookLoader (reuses the SAME extension module as the ads
//       patch; no new extension is built)
//   loadNativeHookPatch (this) — injects the NativeHookLoader.load() call into
//       Application.onCreate
//
// We call the extension's NativeHookLoader.load() rather than inlining
// System.loadLibrary so the load is wrapped in try/catch + logcat ("fail loud"),
// matching the SkipAdsPatch extension convention.
//
// SCAFFOLD — not registered in the build. To activate:
//   1. Confirm ApplicationOnCreateFingerprint's definingClass (see Fingerprints.kt).
//   2. Move NativeHookLoader.java into the extension module:
//        extensions/extension/src/main/java/ajstrick81/morphe/extension/primevideo/nativehook/
//   3. Add an R8 -keep for NativeHookLoader (load) to extensions/proguard-rules.pro
//      so the merged method survives shrinking.
//   4. Register bundleNativeHookPatch + loadNativeHookPatch and gate both on
//      Constants.COMPATIBILITY.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val loadNativeHookPatch = bytecodePatch(
    name = "Load native ad-strip hook",
    description = "Loads libpvhook.so at startup to blank Remote (ad) items from the " +
        "PRS intraTitlePlaylist in-process (memcpy/memmove interception via ShadowHook).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // The .so must be in lib/<abi>/ before we inject the load call, and the
    // extension DEX (NativeHookLoader) must be merged before we reference it.
    dependsOn(bundleNativeHookPatch, primeVideoExtensionPatch)

    execute {
        // Inject at index 0 of Application.onCreate so JNI_OnLoad runs before
        // any native media pipeline is constructed.
        ApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/primevideo/nativehook/NativeHookLoader;->load()V
            """
        )
    }
}
