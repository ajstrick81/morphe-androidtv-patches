/*
 * Attribution: the SSAI ad-segment scrub approach mirrors this repo's Peacock
 * WebView interception (PeacockWebViewHelper) and MLB manifest rewriter, plus
 * the general TwitchAdSolutions insight that Twitch stitched ads are marked in
 * the HLS playlist. See TwitchAtvWebViewHelper for the mechanism.
 */
package ajstrick81.morphe.patches.twitchatv.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val blockTvAdsPatch = bytecodePatch(
    name = "Block TV ads",
    description = "Blocks live ads on the Twitch ANDROID TV app (tv.twitch.android.app v13.0.0.2, " +
        "the \"Starshot\" living-room build — NOT the phone app). That app runs the Twitch web " +
        "player (laserarray/hls.js) in a WebView, and live ads are server-stitched into the " +
        "video-weaver HLS playlist. This wraps the app's WebViewClient so a native " +
        "shouldInterceptRequest override re-fetches the weaver playlist and strips the " +
        "twitch-stitched-ad segments before hls.js sees them — timing-independent, no proxy, " +
        "Twitch's own servers only.",
) {
    compatibleWith(
        Compatibility(
            name = "Twitch",
            packageName = "tv.twitch.android.app",
            appIconColor = 0x9147FF,
            targets = listOf(AppTarget("13.0.0.2")),
        ),
    )

    extendWith("extensions/extension.mpe")

    execute {
        // Wrap the WebViewClient at the setWebViewClient(...) call so our scrubbing
        // shouldInterceptRequest override delegates to the app's real client. Locate
        // the invoke-virtual setWebViewClient, read the client-argument register, and
        // route it through TwitchAtvWebViewHelper.wrapClient() first.
        SetWebViewClientFingerprint.method.apply {
            val insns = implementation!!.instructions.toList()
            val callIndex = insns.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                    (insn as? ReferenceInstruction)?.reference?.toString()
                        ?.contains("->setWebViewClient(") == true
            }
            check(callIndex >= 0) { "setWebViewClient call not found in StarshotActivity" }
            // invoke-virtual {vReceiver, vClient} — 1st register is the WebView, 2nd the client.
            val call = insns[callIndex] as FiveRegisterInstruction
            val webViewReg = call.registerC
            val clientReg = call.registerD
            // The client register is REUSED after this call for a typed field access
            // (iget Ls4/k;->a), so we must not leave a generic WebViewClient in it.
            // Save the original into a scratch local that is dead at this point (v3 —
            // it held a value already stored back to a field), wrap into the client
            // register for the setWebViewClient call, then restore the original so the
            // later typed use still verifies.
            val scratch = 3
            check(scratch != webViewReg && scratch != clientReg)
            addInstructions(
                callIndex,
                """
                    move-object/16 v$scratch, v$clientReg
                    invoke-static { v$clientReg }, Lajstrick81/morphe/extension/twitchatv/ads/TwitchAtvWebViewHelper;->wrapClient(Landroid/webkit/WebViewClient;)Landroid/webkit/WebViewClient;
                    move-result-object v$clientReg
                """,
            )
            // Original setWebViewClient is now at callIndex+3; restore the client after it.
            addInstructions(
                callIndex + 4,
                """
                    move-object/16 v$clientReg, v$scratch
                """,
            )
        }
    }
}
