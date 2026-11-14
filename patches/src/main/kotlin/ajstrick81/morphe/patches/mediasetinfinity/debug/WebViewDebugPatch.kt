package ajstrick81.morphe.patches.mediasetinfinity.debug

import ajstrick81.morphe.patches.mediasetinfinity.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val webViewDebugPatch = bytecodePatch(
    name = "Enable WebView debugging",
    description = "Debug only: enables chrome://inspect on the Mediaset Infinity WebView so the " +
        "web app's console, network requests and native-bridge calls can be inspected. " +
        "Do not leave enabled.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        ShellActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                invoke-static {v0}, Landroid/webkit/WebView;->setWebContentsDebuggingEnabled(Z)V
            """,
        )
    }
}
