package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// THE AD REMOVER (loader half). DEX-side: load libgadget.so at startup so the
// bundled ad-kill script (killads.js, placed by bundleGadgetPatch) is armed as
// early as possible — before the nrdp runtime schedules any ad break.
//
// The gadget only needs the library on the linker path, so we inline
// System.loadLibrary("gadget"); its config (libgadget.config.so) points it at
// the bundled script in script mode. No extension DEX required.
//
// Injected at index 0 of Application.onCreate so it loads as early as possible.
// Companion to bundleGadgetPatch (dependsOn), which stages the .so + script +
// config first. Default ON.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val loadGadgetPatch = bytecodePatch(
    name = "Remove Netflix ads (loader)",
    description = "Arms the ad remover: injects the startup call that loads the in-process " +
        "ad-kill engine (bundled by the companion patch) as early as possible, so ads are " +
        "emptied before playback. Requires the bundle-engine patch. Default ON.",
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
