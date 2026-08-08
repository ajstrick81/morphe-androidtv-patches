package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// DEX-side half of the non-root capture: load libgadget.so at startup so the
// frida-gadget script (dump_appboot.js) is armed before the nrdp runtime
// fetches/caches the appboot UI bundle.
//
// Unlike the Prime Video native-hook (which calls an extension's
// NativeHookLoader.load() for fail-loud logging), the gadget only needs the
// library on the linker path — so we inline System.loadLibrary("gadget"). No
// extension DEX is required for capture. The gadget's own config
// (libgadget.config.so, written by bundleGadgetPatch) drives what runs.
//
// Injected at index 0 of Application.onCreate so the gadget loads as early as
// possible in-process.
//
// SCAFFOLD — not registered in the build. To activate:
//   1. Confirm NetflixApplicationOnCreateFingerprint's definingClass (Fingerprints.kt).
//   2. Drop the armeabi-v7a frida-gadget at the path bundleGadgetPatch documents.
//   3. Register bundleGadgetPatch + loadGadgetPatch, gate both on Constants.COMPATIBILITY.
// This is CAPTURE tooling, not the ad-strip itself — remove it from any shipping
// build once the appboot schema is in hand and the seam-A transform is written.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val loadGadgetPatch = bytecodePatch(
    name = "Load frida-gadget (Netflix appboot capture)",
    description = "Loads libgadget.so at startup to capture the appboot UI bundle " +
        "in-process on a non-rooted device (post-MSL, post-signature).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // The .so + config must be in lib/<abi>/ before we inject the load call.
    dependsOn(bundleGadgetPatch)

    execute {
        NetflixApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "gadget"
                invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """
        )
    }
}
