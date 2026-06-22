package app.morphe.patches.hbomax.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val hboAdsPatch = bytecodePatch(
    name = "HBO Max - Disable Ads",
    description = "Suppresses nonlinear overlay ads (Bolt), SSAI linear ad " +
        "timeline registration (GMSS/AdSparx), and live stream preroll ad " +
        "timeline entry generation for all content types.",
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    execute {

        // ─────────────────────────────────────────────────────────────────────
        // Patch 1: BoltNonLinearAdsRequest.write$Self()
        // Prepend return-void so the serializer write method exits immediately
        // before writing any fields to the JSON body. The Bolt server receives
        // an empty request and returns no nonlinear ads. Using addInstructions
        // at index 0 rather than clear+rewrite since the instructions list
        // is unmodifiable in this patcher version.
        // ─────────────────────────────────────────────────────────────────────
        BoltNonLinearAdsRequestWriteSelfFingerprint.method.addInstructions(
            0,
            "return-void",
        )

        // ─────────────────────────────────────────────────────────────────────
        // Patch 2: BoltDynamicAdFetcher$fetchNonLinearAds$1.invokeSuspend()
        // Insert const/4 v8, 0x0 immediately after move-result-object v8
        // following the fetchNonLinearAds call. Discards the real ad list
        // before it reaches the coroutine collector — null != COROUTINE_SUSPENDED
        // so if-ne branch is taken, Result.success(null) returned, no ads
        // scheduled, no crash.
        // ─────────────────────────────────────────────────────────────────────
        BoltDynamicAdFetcherInvokeSuspendFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val moveResultIndex = instructions.indexOfFirst { instruction ->
                val idx = instructions.indexOf(instruction)
                instruction.opcode == Opcode.MOVE_RESULT_OBJECT &&
                    idx > 0 &&
                    instructions[idx - 1].opcode == Opcode.INVOKE_VIRTUAL_RANGE
            }
            addInstructions(
                moveResultIndex + 1,
                "const/4 v8, 0x0",
            )
        }

        // ─────────────────────────────────────────────────────────────────────
        // Patch 3: SsaiInfoTimelineBuilder.buildAdBreaksFromAdSparxAdBreaks()
        // return-void at entry suppresses all SSAI ad break timeline
        // registration for VOD/movies.
        // ─────────────────────────────────────────────────────────────────────
        SsaiInfoTimelineBuilderBuildAdBreaksFingerprint.method.addInstructions(
            0,
            "return-void",
        )

        // ─────────────────────────────────────────────────────────────────────
        // Patch 4: SsaiInfoTimelineBuilder.access$buildAdBreaksFromAdSparxAdBreaks()
        // Synthetic accessor used by buildTimeline inner lambdas.
        // return-void at entry closes the lambda call path.
        // Optional: newer builds removed this synthetic accessor entirely
        // (the lambda calls the private method directly, already neutralized
        // by Patch 3), so this fingerprint may legitimately find no match.
        // ─────────────────────────────────────────────────────────────────────
        try {
            SsaiInfoTimelineBuilderAccessorFingerprint.method.addInstructions(
                0,
                "return-void",
            )
        } catch (_: Exception) {
            // Accessor not present in this build — Patch 3 already covers
            // the only remaining call path, so nothing else to do here.
        }

        // ─────────────────────────────────────────────────────────────────────
        // Patch 5: GenerateLiveTimelineEntriesForAdBreakKt.generateLiveTimelineEntriesForAdBreak()
        // return-void at entry suppresses all AdBreakEntry/AdEntry construction.
        // The caller does addAll() on the result — since the method now returns
        // immediately, no ad entries are added to the live timeline while
        // chapter/content entries are built normally.
        // Note: cannot return an empty list here since the method returns
        // List not void — return-void exits the method cleanly and the
        // caller handles the missing result via its existing null/empty checks.
        // ─────────────────────────────────────────────────────────────────────
        GenerateLiveTimelineEntriesForAdBreakFingerprint.method.addInstructions(
            0,
            "return-void",
        )
        
        // ─────────────────────────────────────────────────────────────────────────────
        // NowtilusEnabledFingerprint — MediaMelon Nowtilus SSAI plugin kill switch
        // classes4.dex — static boolean getter, exact class and method name sufficient.
        // isNowtilusEnabled() reads the static isNowtilus field set by setMMConfig()
        // from the server registration response. Returning false here prevents the
        // Nowtilus SSAI plugin from initializing regardless of what the server sends —
        // the getter is the read point so the field value is irrelevant. Suppresses
        // skippable prerolls on live and episodic content that route through the
        // MediaMelon/Nowtilus CDN-level ad stitching pipeline rather than AdSparx.
        // ─────────────────────────────────────────────────────────────────────────────
        NowtilusEnabledFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
         )
    }
}
