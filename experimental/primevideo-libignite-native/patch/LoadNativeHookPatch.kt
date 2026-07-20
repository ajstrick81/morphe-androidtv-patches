package ajstrick81.morphe.patches.primevideo.native

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.primevideo.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Loads libpvhook.so into the Prime Video process at startup so the native
// SSL_read / inflate hooks install before the first playback session.
//
// This is the DEX-side half of the in-process native interception. The other
// half is the native library itself (experimental/primevideo-libignite-native/
// jni → libpvhook.so), which must be bundled into the APK's lib/armeabi-v7a/
// by a resource step. See that folder's README "Promotion checklist".
//
// SCAFFOLD — not registered in the build. To activate:
//   1. Confirm ApplicationOnCreateFingerprint's definingClass (see Fingerprints.kt).
//   2. Ensure libpvhook.so is packaged into lib/<abi>/ (resource patch — TODO,
//      no native-asset bundling step exists in this repo yet).
//   3. Add an R8 -keep so this injected loadLibrary site isn't stripped.
//   4. Register loadNativeHookPatch and gate on Constants.COMPATIBILITY.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val loadNativeHookPatch = bytecodePatch(
    name = "Load native ad-strip hook",
    description = "Loads libpvhook.so at startup to strip /iad_ ad segments in-process " +
        "(native SSL_read/inflate interception on libignite).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Inject at index 0 of Application.onCreate so JNI_OnLoad runs before
        // any native media pipeline is constructed. System.loadLibrary throws
        // UnsatisfiedLinkError if the .so is missing — which is exactly what we
        // want during bring-up (fail loud in logcat), not a silent no-op.
        ApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "pvhook"
                invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """
        )
    }
}
