package ajstrick81.morphe.patches.discoveryplus.ads

import ajstrick81.morphe.patches.discoveryplus.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// ─────────────────────────────────────────────────────────────────────────────
// discovery+ (Android TV) — ad patches.
//
// discovery+ shares WBD's Bolt/Beam player with HBO Max. Its linear ads are
// server-side stitched (SSAI) into the DASH stream, and the app builds an ad-break
// timeline from the GMSS/AdSparx payload. Three independent layers, each on by default:
//
//   1. Prefer Ad-Free Stream  — if the Playable advertises a FALLBACK stream (on HBO that
//      is a clean, non-stitched manifest), play it instead of the stitched PRIMARY one.
//      No-op when there is no FALLBACK stream.
//   2. Skip Ad Breaks         — treat every ad break as already watched. The app's own
//      "skip already-watched ad break" logic then jumps over each break as playback
//      reaches it, and seeking forward over breaks is no longer locked. This is the
//      safety net for titles whose only stream is ad-stitched.
//   3. Disable Ads            — nonlinear (overlay / pause) ad requests and live preroll
//      timeline entries.
//
// NOTE: unlike HBO's "Disable Ads", this deliberately does NOT suppress
// SsaiInfoTimelineBuilder.buildAdBreaksFromAdSparxAdBreaks. Without a manifest filter,
// removing the ad-break ranges from the timeline would make the stitched ads play as if
// they were content — and leave layer 2 nothing to skip.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val discoveryPlusPreferFallbackStreamPatch = bytecodePatch(
    name = "discovery+ - Prefer Ad-Free Stream",
    description = "Plays the FALLBACK stream instead of the ad-stitched PRIMARY stream " +
        "when the title advertises one (same mechanism as HBO Max's ad-free stream). " +
        "Does nothing for titles without a FALLBACK stream.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY)

    // Merges DplayStreamSelector into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // getStreamInfo is static: p0 = Playable, p1 = StreamInfo$Type.
        DplayGetStreamInfoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, Lajstrick81/morphe/extension/discoveryplus/ads/DplayStreamSelector;->preferFallbackType(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, Lcom/discovery/player/common/models/StreamInfo${'$'}Type;
            """.trimIndent(),
        )
    }
}

@Suppress("unused")
val discoveryPlusSkipAdBreaksPatch = bytecodePatch(
    name = "discovery+ - Skip Ad Breaks",
    description = "Marks every server-stitched ad break as already watched, so the player " +
        "jumps over mid-rolls and lets you seek past ad breaks. The first ad of a " +
        "pre-roll may still play briefly.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Static (TimelineState, I)Z with .locals 0 — reuse p0 as the return register.
        DplayIsAdBreakAlreadyWatchedFingerprint.method.addInstructions(
            0,
            """
                const/4 p0, 0x1
                return p0
            """.trimIndent(),
        )
    }
}

@Suppress("unused")
val discoveryPlusDisableAdsPatch = bytecodePatch(
    name = "discovery+ - Disable Ads",
    description = "Suppresses nonlinear (overlay / pause) ad requests and live-stream " +
        "preroll ad timeline entries.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // 1. Nonlinear ad request serializer: exit before writing any fields.
        DplayBoltNonLinearAdsRequestWriteSelfFingerprint.method.addInstructions(
            0,
            "return-void",
        )

        // 2. Null the fetched nonlinear ad list right after fetchNonLinearAds returns.
        //    Read the destination register instead of hard-coding it (it drifts per build).
        DplayBoltDynamicAdFetcherInvokeSuspendFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val moveResultIndex = instructions.indices.first { i ->
                i > 0 &&
                    instructions[i].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    instructions[i - 1].opcode == Opcode.INVOKE_VIRTUAL_RANGE
            }
            val register = (instructions[moveResultIndex] as OneRegisterInstruction).registerA
            val nullInstruction =
                if (register <= 0xF) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"
            addInstructions(moveResultIndex + 1, nullInstruction)
        }

        // 3. Live preroll entries: return an empty list (never return-void in a
        //    List-returning method — ART rejects the whole class).
        DplayGenerateLiveTimelineEntriesForAdBreakFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )
    }
}
