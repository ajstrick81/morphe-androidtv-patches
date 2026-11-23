package ajstrick81.morphe.patches.primevideo.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.primevideo.misc.extension.primeVideoExtensionPatch
import ajstrick81.morphe.patches.primevideo.shared.Constants

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Multi-layer ad suppression targeting the SSAI schedule, impression reporting, and the Volley network chokepoint.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(primeVideoExtensionPatch)

    execute {
        // ─────────────────────────────────────────────────────────────────────
        // Each hook uses methodOrNull (safe-call) so a single unresolved
        // fingerprint does NOT abort the whole patch (issue #120). Prime Video
        // v16 (6.24.x, engine v16.0.0.x) REMOVED the entire Java-reachable ad
        // surface these hooks target — `setAdPlaybackStates`, the media3
        // `ServerSideAdInsertionMediaSource`, `MetricsTransporter`, etc. are all
        // gone from the dex (moved into the native libignite engine). With the
        // hooks mandatory (throwing `.method`), that made the whole Skip-ads patch
        // hard-fail, so users couldn't even build/install a patched v16 (no clone,
        // no cert bypass, no auto-update disable). methodOrNull lets the patch
        // apply on every version: on v15.x/older each hook that still matches fires
        // exactly as before; on v16 the missing hooks are skipped and suppression
        // rests entirely on the native libpvhook GOT strip, which still loads
        // there (see loadNativeHookPatch and Constants). TRADEOFF: a genuinely-drifted
        // hook on a supported version now degrades silently instead of erroring —
        // acceptable vs. a total patch abort. See #120.
        // ─────────────────────────────────────────────────────────────────────

        // Hook 1 — media3 ServerSideAdInsertionMediaSource.setAdPlaybackStates()
        // Strips all AdGroups from the incoming SSAI ad schedule before ExoPlayer
        // sees it. Primary suppression for the standard SSAI path.
        SetAdPlaybackStatesMedia3Fingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, Lajstrick81/morphe/extension/primevideo/ads/SkipAdsPatch;->skipAllMedia3AdGroups(Lcom/google/common/collect/ImmutableMap;)Lcom/google/common/collect/ImmutableMap;
                move-result-object p1
            """
        )

        // Hook 2 — ExoPlayer2 ServerSideAdInsertionMediaSource.setAdPlaybackStates()
        // Same strategy for the GMS Ads SDK ExoPlayer2 variant.
        SetAdPlaybackStatesExo2Fingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, Lajstrick81/morphe/extension/primevideo/ads/SkipAdsPatch;->skipAllExo2AdGroups(Lcom/google/common/collect/ImmutableMap;)Lcom/google/common/collect/ImmutableMap;
                move-result-object p1
            """
        )

        // Hook 3 — MetricsTransporter.transmit(SerializedBatch)
        // Returns a fake SUCCESS UploadResult without any network request, so
        // Amazon's ad server receives no impression delivery data. Inline smali
        // constructs UploadResult("SUCCESS", "ok") directly.
        MetricsTransporterTransmitFingerprint.methodOrNull?.addInstructions(
            0,
            """
                new-instance v0, Lcom/amazon/minerva/client/thirdparty/transport/UploadResult;
                const-string v1, "SUCCESS"
                const-string v2, "ok"
                invoke-direct {v0, v1, v2}, Lcom/amazon/minerva/client/thirdparty/transport/UploadResult;-><init>(Ljava/lang/String;Ljava/lang/String;)V
                return-object v0
            """
        )

        // Hook 4 — Volley BasicNetwork.performRequest(Request)
        // Rejects known ad-decisioning / ad-tracking hosts with a real
        // NoConnectionError that Volley's RetryPolicy handles gracefully.
        // Control-plane companion to Hooks 1–3; does NOT suppress mid-roll ad
        // segments (those go through media3 DefaultHttpDataSource, not Volley).
        BasicNetworkPerformRequestFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {p1}, Lajstrick81/morphe/extension/primevideo/ads/SkipAdsPatch;->enforceAdBlock(Lcom/android/volley/Request;)V
            """
        )
    }
}
