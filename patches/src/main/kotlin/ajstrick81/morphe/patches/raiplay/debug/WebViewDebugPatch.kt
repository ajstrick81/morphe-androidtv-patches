package ajstrick81.morphe.patches.raiplay.debug

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.raiplay.shared.Constants

@Suppress("unused")
val webViewDebugPatch = bytecodePatch(
    name = "Enable WebView debugging",
    description = "Debug only: enables chrome://inspect on the RaiPlay WebView so the SPA " +
        "console and native-bridge traffic can be inspected. Do not leave enabled.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        RaiPlayApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                invoke-static {v0}, Landroid/webkit/WebView;->setWebContentsDebuggingEnabled(Z)V
            """,
        )
    }
}
