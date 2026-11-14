package ajstrick81.morphe.patches.mediasetinfinity.ads

import ajstrick81.morphe.patches.mediasetinfinity.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

private const val EXTENSION =
    "Lajstrick81/morphe/extension/mediasetinfinity/ads/MediasetWebViewHelper;"

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "EXPERIMENTAL, not yet verified on a device. Mediaset Infinity's Android TV app " +
        "is a WebView shell, so its ads are requested by the remote web app. This answers the " +
        "web app's ad-decision requests (FreeWheel, Google ad servers) locally with an empty " +
        "response so no client-inserted ad break is scheduled. Ads stitched into the stream " +
        "server-side (possible on live channels) are not affected.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // MediasetWebViewHelper lives in the extension dex; without extendWith the
    // invoke-static below assembles fine but the class is never merged in.
    extendWith("extensions/extension.mpe")

    execute {
        // Prepend to the shell WebViewClient's shouldInterceptRequest:
        //   WebResourceResponse r = MediasetWebViewHelper.intercept(request);
        //   if (r != null) return r;
        // v0 is free at entry (the original body's first write is to v0; the
        // method has 5 locals on 8.1.13). The helper is null-safe, so running
        // ahead of Kotlin's own parameter null-checks is fine.
        //
        // ExternalLabel, not an internal label: an internal label's target is
        // fixed at insert time, so a later prepend by another patch would leave
        // the branch landing mid-instruction (VerifyError at launch) — the Tubi
        // lesson. methodOrNull so a re-anchor miss degrades to "ads still play"
        // instead of aborting the whole patch run.
        ShellWebClientInterceptFingerprint.methodOrNull?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p2 }, $EXTENSION->intercept(Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;
                    move-result-object v0
                    if-eqz v0, :mediaset_pass
                    return-object v0
                """,
                ExternalLabel("mediaset_pass", method.getInstruction(0)),
            )
        }
    }
}
