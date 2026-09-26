package ajstrick81.morphe.patches.tubi.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import ajstrick81.morphe.patches.tubi.ads.TubiWebClientInterceptFingerprint
import ajstrick81.morphe.patches.tubi.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

// ─────────────────────────────────────────────────────────────────────────────
// Block analytics & tracking (OPT-IN, default OFF).
//
// From the 2026-09 privacy study of the Tubi Android TV build: besides crash
// reporting, Tubi ships Adjust, Branch, Braze, Nielsen, Conviva, NPAW/Youbora,
// Mux, Firebase/Google Analytics and Meta App Events. Its own analytics
// endpoint (analytics-ingestion) receives advertiser_id, device_id and
// postal_code / latitude / longitude fields.
//
// Layers:
//   1. Manifest: the SDKs' own documented kill switches (Firebase Analytics
//      deactivated, no SSAID / ad-ID collection; Meta auto-logged events and
//      ad-ID collection off). Facebook sign-in itself is left alone.
//   2. Adjust never starts (initSdk / onCreate -> return-void).
//   3. One host blocklist (TrackerBlocker) enforced at every network path the
//      analytics use: all URL.openConnection() call sites, every OkHttp client,
//      and the SPA WebView's shouldInterceptRequest.
//
// Not touched: crash/performance reporting, Statsig feature flags, OneTrust
// consent, sign-in, ads/playback/DRM. A blocked request fails like an offline
// network, which these SDKs already tolerate.
// ─────────────────────────────────────────────────────────────────────────────

private const val EXTENSION = "Lajstrick81/morphe/extension/tubi/privacy/TrackerBlocker;"
private const val EXTENSION_PACKAGE = "Lajstrick81/morphe/extension/"

private val manifestKillSwitches = mapOf(
    "firebase_analytics_collection_deactivated" to "true",
    "google_analytics_ssaid_collection_enabled" to "false",
    "google_analytics_adid_collection_enabled" to "false",
    "google_analytics_default_allow_ad_personalization_signals" to "false",
    "com.facebook.sdk.AutoLogAppEventsEnabled" to "false",
    "com.facebook.sdk.AdvertiserIDCollectionEnabled" to "false",
)

private val analyticsKillSwitchesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val existing = mutableMapOf<String, Element>()
            val metaData = application.getElementsByTagName("meta-data")
            for (i in 0 until metaData.length) {
                val element = metaData.item(i) as? Element ?: continue
                existing[element.getAttribute("android:name")] = element
            }
            for ((name, value) in manifestKillSwitches) {
                val element = existing[name] ?: document.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                element.setAttribute("android:value", value)
            }
        }
    }
}

private fun Instruction.isUrlOpenConnection(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == "Ljava/net/URL;" &&
        reference.name == "openConnection" &&
        reference.parameterTypes.isEmpty()
}

private fun Method.callsUrlOpenConnection() =
    implementation?.instructions?.any { it.isUrlOpenConnection() } == true

@Suppress("unused")
val blockAnalyticsPatch = bytecodePatch(
    name = "Block analytics & tracking",
    description = "Stops Tubi's analytics and tracking: Adjust, Branch, Braze, Nielsen, " +
        "Conviva, NPAW/Youbora, Mux, Firebase/Google Analytics, Meta app events, and Tubi's " +
        "own analytics endpoint (which receives your advertiser ID, device ID and ZIP/location " +
        "fields). Blocking happens on the device, inside the app. Crash reporting, feature " +
        "flags, sign-in and playback are left alone. Recommendations may be less personalised. " +
        "Opt-in / experimental; test on-device.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(analyticsKillSwitchesPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Layer 2 — Adjust never starts.
        AdjustInitSdkFingerprint.methodOrNull?.addInstructions(0, "return-void")
        AdjustOnCreateFingerprint.methodOrNull?.addInstructions(0, "return-void")

        // Layer 3a — every OkHttp client gets the blocking interceptor. The call
        // sits before the super() call, which the verifier allows because it
        // doesn't touch `this`.
        OkHttpClientInitFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, $EXTENSION->install(Lokhttp3/OkHttpClient\$Builder;)V",
        )

        // Layer 3b — the SPA WebView. Same method the Skip ads patch hooks; its
        // v0-v4 usage there shows the method has the locals this needs.
        // Branch to the method's current first instruction via ExternalLabel, not
        // an internal label: an internal label's target is fixed at insert time,
        // so when Skip ads later prepends its own block the branch lands
        // mid-instruction (VerifyError "invalid branch target" at launch).
        TubiWebClientInterceptFingerprint.methodOrNull?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p2 }, $EXTENSION->blockWebRequest(Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;
                    move-result-object v0
                    if-eqz v0, :privacy_pass
                    return-object v0
                """,
                ExternalLabel("privacy_pass", method.getInstruction(0)),
            )
        }

        // Layer 3c — route every URL.openConnection() in the app through the
        // blocklist. Collect first, mutate after: mutableClassDefBy swaps the
        // class map entry, which must not happen mid-iteration. Extension
        // classes are skipped, since TrackerBlocker itself calls openConnection.
        val targets = mutableListOf<String>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(EXTENSION_PACKAGE) &&
                classDef.methods.any { it.callsUrlOpenConnection() }
            ) {
                targets += classDef.type
            }
        }

        val replacement = "$EXTENSION->openConnection(Ljava/net/URL;)Ljava/net/URLConnection;"
        targets.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                // Snapshot: replaceInstruction mutates the live list.
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    if (!instruction.isUrlOpenConnection()) return@forEachIndexed
                    val smali = when (instruction) {
                        is FiveRegisterInstruction ->
                            "invoke-static { v${instruction.registerC} }, $replacement"
                        is RegisterRangeInstruction ->
                            "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister} }, $replacement"
                        else -> return@forEachIndexed
                    }
                    method.replaceInstruction(index, smali)
                }
            }
        }
    }
}
