package ajstrick81.morphe.patches.primevideophone.controls

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val dpadPlayerControlsPatch = bytecodePatch(
    name = "D-pad player controls",
    description = "Adds TV-remote (D-pad) control of playback to the phone app's touch-only " +
        "player: OK = play/pause, Left/Right = rewind/fast-forward, Up/Down = subtitles/settings. " +
        "Installs a wrapping Window.Callback on PlayerActivity that converts directional key " +
        "presses into synthetic taps on the on-screen transport controls (the player UI is not " +
        "D-pad focusable and has no MediaSession key handling). Opt-in.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    // extendWith populates Patch.extensionInputStream — without it the
    // DpadPlayerControls class referenced by the invoke-static below never gets
    // merged into the patched APK's dex, even though the smali assembles fine.
    extendWith("extensions/extension.mpe")

    execute {
        // Inject at onResume offset 0. p0 = this (PlayerActivity, an Activity).
        // install() is idempotent — it no-ops if the window is already wrapped —
        // so re-invoking on every resume is safe and re-asserts the wrapper if
        // the app replaced its Window.Callback after onCreate.
        PlayerActivityOnResumeFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lajstrick81/morphe/extension/primevideophone/controls/DpadPlayerControls;->install(Landroid/app/Activity;)V"
        )
    }
}
