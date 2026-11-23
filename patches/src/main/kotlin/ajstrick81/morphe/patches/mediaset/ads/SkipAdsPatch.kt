package ajstrick81.morphe.patches.mediaset.ads

import ajstrick81.morphe.patches.mediaset.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

private const val AD_BLOCKER =
    "Lajstrick81/morphe/extension/mediaset/ads/MediasetAdBlocker;"

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Blocks the FreeWheel and Google ad calls the Mediaset Infinity Android TV " +
        "web app makes, so no pre-roll, mid-roll, pause or welcome ad is ever chosen. " +
        "Ads stitched into a live channel's stream on the server (if any) are not affected.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    extendWith("extensions/extension.mpe")

    execute {
        // At the top of the shell's shouldInterceptRequest: ask the extension first.
        // A non-null answer (an empty response for an ad host) is returned as-is;
        // null falls through to the app's original code via an external label bound
        // to the original first instruction (never an in-block label at index 0).
        ShouldInterceptRequestFingerprint.method.apply {
            val original = getInstruction(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p2 }, $AD_BLOCKER->intercept(Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;
                    move-result-object v0
                    if-eqz v0, :mediaset_continue
                    return-object v0
                """,
                ExternalLabel("mediaset_continue", original),
            )
        }
    }
}
