/*
 * Attribution:
 *
 * See Fingerprints.kt in this package for full credit to Purple TV,
 * TwitchAdSolutions, and Xtra for Twitch — the projects whose published
 * techniques informed this patch's ad-suppression strategy.
 */
package ajstrick81.morphe.patches.twitch.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.twitch.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Reduces Twitch live ads: spoofs the stream access-token playerType to " +
        "\"embed\" (usher serves an ad-free stream for that context — kills the long SSAI " +
        "mid-rolls), forces the GrandDads ad-eligibility response to AdContextUnavailable, and " +
        "forces the ExoPlayer stitched-ad (SSAI) metadata parser to treat every segment as " +
        "non-ad (suppresses ad tracking/beacons). On-device (v30.2.2, Onn 4K): the long " +
        "mid-roll ads are gone and the join pre-roll drops to ~15s; a residual short pre-roll " +
        "remains because it is server-stitched in the native IVS path, which bytecode cannot reach.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // Layer 1 — playerType spoofing (access-token / clean-stream route)
        //
        // Locate PlaybackAccessTokenParams via its toString() string anchor,
        // then find the constructor in the same class. The constructor takes
        // the playerType as a String parameter (field `d`, register p1). We
        // overwrite it before the constructor body runs.
        //
        // Why "embed": the playerType is baked into the signed streamPlayback
        // access token, and usher (usher.ttvnw.net) serves an AD-FREE stream
        // for the "backup" web player contexts. TwitchAdSolutions' `vaft`
        // treats `embed`/`popout`/`autoplay` as the ad-free player types
        // (embed = Source quality) and uses `popout` as its forced
        // access-token type. "embed" is tried first: best quality, and unlike
        // the vaft web script — which must fight Twitch client-integrity to
        // spoof the type on the logged-in session and therefore also swaps the
        // m3u8 — we rewrite the token request at the bytecode source, so the
        // returned token genuinely encodes `embed` and usher should hand us the
        // ad-free stream directly. (Prior value "background_audio" did NOT stop
        // ads on-device 2026-07-31; if "embed" also fails try "popout", then
        // "autoplay" (360p), then escalate to a backup-stream m3u8 swap.)
        //
        // Technique credit: TwitchAdSolutions / vaft (pixeltris) — ad-free
        // player-type + clean-stream approach.
        //
        // Verified 2026-07-28 against v30.2.2 smali (class `Ls1r;`): the
        // toString() field order in this build is
        // disableHTTPS,hasAdblock,playerBackend,playerType,maid,notificationID
        // — playerType is field `d` (String, non-Optional/required), and the
        // single physical constructor is `<init>(Ljava/lang/String;Lu1q;I)V`
        // where p1 is iput directly into `d`. So p1 IS playerType here.
        val toStringMethod = PlaybackAccessTokenParamsToStringFingerprint.method
        val paramsClass = toStringMethod.definingClass

        val constructor = mutableClassDefBy(paramsClass).methods.first { it.name == "<init>" }

        constructor.addInstructions(
            0,
            """
                const-string p1, "embed"
            """,
        )

        // Layer 2 — force GrandDads ad-eligibility to "AdContextUnavailable"
        //
        // The decisive choke point. The GrandDads GQL response mapper
        // (Lwyh;->invoke, tag-4 branch) turns the query result into a
        // GrandDadsResponse (sealed base Lp0i): a null decision node yields the
        // AdContextUnavailable singleton Ln0i (INSTANCE = Ln0i;->a:Ln0i;),
        // otherwise the ads-available variant Lo0i. We short-circuit the mapper
        // to ALWAYS return the AdContextUnavailable sentinel, so the ad system
        // never receives a usable ad context — the obfuscated analog of Purple
        // TV's shouldDeclineAds → GrandDadsResponse.AdContextUnavailable.INSTANCE.
        //
        // The guard only fires for tag 4 (the GrandDads mapping). That branch
        // begins `check-cast p1, Lg0i;` (GrandDadsQuery.Data), so any
        // non-GrandDads tag-4 use of the shared Lwyh dispatch class would
        // already ClassCastException — proving tag-4 is GrandDads-exclusive and
        // this is correctly scoped. Registers v0/v1 are re-initialized by the
        // original method body (which starts by re-reading the tag), so
        // clobbering them at entry is safe (.locals 12).
        //
        // See Fingerprints.kt for the full "query GrandDads" → Li0i → Lhs9;->f
        // → Lwyh(0x4) → Ln0i derivation. Version-pinned obfuscated names.
        //
        // Technique credit: Purple TV (GrandDads short-circuit →
        // AdContextUnavailable). Re-derived independently against v30.2.2.
        GrandDadsResponseMapperFingerprint.method.addInstructions(
            0,
            """
                iget v0, p0, Lwyh;->a:I
                const/16 v1, 0x4
                if-ne v0, v1, :not_granddads
                sget-object v0, Ln0i;->a:Ln0i;
                return-object v0
                :not_granddads
                nop
            """,
        )

        // Layer 3 — ExoPlayer stitched-ad metadata suppression (the real SSAI kill)
        //
        // tv.twitch.android.shared.player.core.b's onMetadata handler parses each
        // #EXT-X-DATERANGE tag from the live HLS stream and tests its CLASS
        // attribute with `"twitch-stitched-ad".equals(class)`. A true result puts
        // the player into ad handling. We locate that equals→move-result pair and
        // overwrite the result register with 0, so onMetadata always concludes the
        // segment is NOT a stitched ad and takes the player's built-in no-ad path.
        //
        // The injection index and the result register are resolved dynamically
        // (not a fixed offset) so register-allocation / instruction drift across
        // versions doesn't silently move the target — same resilience rationale as
        // the repo's Peacock dynamic injectors.
        //
        // Technique credit: TwitchAdSolutions (client-side stitched-ad strip).
        StitchedAdMetadataFingerprint.method.apply {
            val insns = implementation!!.instructions.toList()
            // First "twitch-stitched-ad" literal = the CLASS-equality check
            // (a later identical literal is only the ad-event name, not a check).
            val checkStrIndex = insns.indexOfFirst { insn ->
                insn.opcode == Opcode.CONST_STRING &&
                    ((insn as ReferenceInstruction).reference as? StringReference)?.string ==
                    "twitch-stitched-ad"
            }
            // The boolean result of the equals() lands in the next move-result.
            val moveResultIndex = (checkStrIndex until insns.size).first { i ->
                insns[i].opcode == Opcode.MOVE_RESULT
            }
            val resultRegister = (insns[moveResultIndex] as OneRegisterInstruction).registerA
            // const/4 covers v0..v15 (register is 4-bit); use const/16 above that.
            val zeroInsn = if (resultRegister <= 15) {
                "const/4 v$resultRegister, 0x0"
            } else {
                "const/16 v$resultRegister, 0x0"
            }
            addInstructions(moveResultIndex + 1, zeroInsn)
        }
    }
}
