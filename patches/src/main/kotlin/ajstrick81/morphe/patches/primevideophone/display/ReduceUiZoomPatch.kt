package ajstrick81.morphe.patches.primevideophone.display

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val reduceUiZoomPatch = bytecodePatch(
    name = "Reduce UI zoom (experimental)",
    description = "Scales down the app's OWN display density (~213/320, the tvdpi look) so the " +
        "phone UI is less zoomed on a TV. Wraps AppCompatActivity.attachBaseContext, so it " +
        "affects only Prime Video's resources — the system display is untouched (unlike `wm " +
        "density`, which crashes the Google TV launcher). Defaults to 80%; apply the \"Morphe " +
        "settings\" patch to change it in-app. Opt-in / experimental.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    // Required — without extendWith the extension dex is never merged, so enabling
    // this patch on its own (i.e. without "D-pad player controls", which happens to
    // declare it) would leave the DensityHelper invoke-static below pointing at a
    // missing class: NoClassDefFoundError on every Activity creation.
    extendWith("extensions/extension.mpe")

    execute {
        // Replace the incoming base Context (p1) with a density-scaled one before
        // AppCompatActivity's own attachBaseContext logic runs. The helper is
        // defensive — it returns the original context on any error.
        AppCompatAttachBaseContextFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, Lajstrick81/morphe/extension/primevideophone/display/DensityHelper;->wrap(Landroid/content/Context;)Landroid/content/Context;
                move-result-object p1
            """
        )
    }
}
