package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import ajstrick81.morphe.patches.shared.ads.ImaPauseAdDisplayFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val skipAdsV17Patch = bytecodePatch(
    name = "Skip ads",
    description = "Removes Pluto TV's on-demand (VOD) ads on the v17.x rebuild (the " +
        "Paramount+/AVIA player stack). Four layers: (1+2) force AVIA's DAI and IMA " +
        "shouldPlayAd() gates to false so the pre-roll ad pods are skipped natively; " +
        "(3) drop the ad <Period>s from the stitched multi-period DASH manifest at " +
        "AVIA's own DashParser and re-base the content periods into a contiguous " +
        "timeline, so the mid-roll ad VIDEO is never played AND the seekbar tracks " +
        "correctly; (4) suppress the ID3/CLID3-driven CSAI ad call on static VOD " +
        "manifests. Also hides pause ads and stops ad impression beacons for the " +
        "removed breaks. Fail-open: a manifest it can't rewrite is passed through unchanged " +
        "(ads remain, playback never breaks). LIVE TV ads are real broadcast time in " +
        "the linear feed and are not removable. Validated on-device, 17.2.0.",
) {
    compatibleWith(Constants.COMPATIBILITY_V17)

    // Merges PlutoV17ManifestProbe (cleanManifestStream + shouldSuppressDynamicAdFetch)
    // into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {

        // Hook 1 — AviaDAIResourceProvider.shouldPlayAd(AviaAdPod) -> false
        // Skips the DAI pre-roll pod. Private Z method, offset 0, no live registers.
        AviaDaiShouldPlayAdFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // Hook 2 — AviaIMAResourceProvider.shouldPlayAd(AviaAdPod) -> false  [optional]
        // Not every build routes through the IMA provider; apply only if present.
        AviaImaShouldPlayAdFingerprint.methodOrNull?.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // Hook 3 — AviaDashParser.parse(Uri, InputStream): swap the InputStream for a
        // cleaned, ad-stripped one at entry. p0=this, p1=Uri, p2=InputStream.
        // cleanManifestStream reads the manifest, strips the ad periods, re-bases the
        // content timeline, and returns a fresh stream for the (renamed) parser;
        // fail-open returns the original bytes, so the fetch is never consumed/lost.
        //
        // parse() is register-heavy (observed .locals 23 in 17.2.0), so p2 resolves to a
        // high register (v25). A non-range `invoke-static {p2}` only encodes v0..v15, and
        // the inline compiler silently DROPS the invoke when p2 is above that range,
        // leaving an orphan `move-result-object` at [0x0] -> VerifyError (result0 type=
        // Undefined) -> AviaDashParser rejected -> player never starts (spinner -> menu,
        // device-confirmed 17.2.0). Emit the range form against p2's numeric register so
        // the encoding is valid regardless of the method's local count.
        AviaDashParserParseFingerprint.method.apply {
            // this, Uri, InputStream -> p2 (InputStream) is the last parameter register.
            val p2reg = implementation!!.registerCount - 1
            addInstructions(
                0,
                """
                    invoke-static/range {v$p2reg .. v$p2reg}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->cleanManifestStream(Ljava/io/InputStream;)Ljava/io/InputStream;
                    move-result-object v$p2reg
                """,
            )
        }

        // Hook 4 — CLID3Processor.processAdPodStartAsync(Map, long): suppress the CSAI
        // ad call on static VOD. Equivalent to prepending
        //   if (shouldSuppressDynamicAdFetch()) return;
        // to the method's existing early-return guard — true only once Hook 3 has seen a
        // static (VOD) manifest, so live/linear (dynamic) is untouched. void method.
        Clid3ProcessAdPodStartFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->shouldSuppressDynamicAdFetch()Z
                move-result v0
                if-eqz v0, :pluto_v17_proceed
                return-void
                :pluto_v17_proceed
                nop
            """,
        )

        // Hook 5 — IMA pause ads: return at entry of IMA's pause-ad display handler
        // (shared fingerprint, anchored on its error string). [optional]
        ImaPauseAdDisplayFingerprint.methodOrNull?.addInstructions(0, "return-void")

        // Hook 6 — TV pause ads: CbsPauseWithAdsOverlay's render dispatch returns at
        // entry, so the ad image is never loaded and the overlay never fades in.
        CbsPauseOverlayRenderFingerprint.methodOrNull?.addInstructions(0, "return-void")

        // Hook 7 — ad impression beacons: the beacon tracker's consumeAds returns at
        // entry (its own feature-disabled path), so stripped breaks are not reported.
        BeaconConsumeAdsFingerprint.methodOrNull?.addInstructions(0, "return-void")

        // Hook 8 — report the true content duration to the UI (issue #300).
        //
        // WHY, and why this is NOT a position remap (the thing tried first and reverted):
        // the seekbar's scale comes from AviaPlayerInfo.contentDuration, set once in
        // AbstractAviaPlayer from AviaResourceProvider.getContentDuration(). The DAI
        // provider computes that as
        //     AviaPlayerInfo.getAbsoluteDuration()  -  sum(adPodList durations)
        // i.e. it subtracts ad time from the player's duration. But after Hook 3 the
        // player's duration ALREADY excludes the ads (they are gone from the manifest),
        // while adPodList is still populated from the stitcher/DAI metadata, which this
        // patch does not empty. So the ad time is deducted a SECOND time and the bar is
        // scaled shorter than the content actually is — playback then runs past the end
        // of the bar, which is issue #300's "progress bar goes beyond the estimated
        // time" and makes every seek read as landing in the wrong place.
        //
        // The needle itself is fine: it is the player's own position, and Pluto's resume
        // saves and restores through the same player clock, which is why Continue
        // Watching works and must not be touched. Only the SCALE is wrong, so only the
        // duration is corrected here. An earlier attempt remapped seek positions
        // instead, on the assumption the UI thought in ad-inclusive coordinates; device
        // testing disproved that (repeated forward nudges moved playback backwards,
        // because the UI's targets are already in player coordinates), so that hook was
        // removed rather than extended.
        //
        // correctContentDuration logs what the provider reported alongside the strip's
        // own total, so the on-device log states the discrepancy outright, and returns
        // the stripped duration the strip measured. It is identity whenever no strip is
        // live (live/linear, passthrough, any fail-open), so unstripped playback and
        // live channels keep the app's own value. Applied per provider; only the one in
        // use matters, so each is optional.
        listOf(
            AviaDaiContentDurationFingerprint,
            AviaImaContentDurationFingerprint,
            AviaUriContentDurationFingerprint,
        ).forEach { fingerprint ->
            fingerprint.methodOrNull?.apply {
                val instructions = implementation!!.instructions.toList()
                instructions.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.RETURN_WIDE
                }.map { (index, _) -> index }.asReversed().forEach { returnIndex ->
                    val durationRegister =
                        (instructions[returnIndex] as OneRegisterInstruction).registerA
                    addInstructions(
                        returnIndex,
                        """
                            invoke-static/range {v$durationRegister .. v${durationRegister + 1}}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->correctContentDuration(J)J
                            move-result-wide v$durationRegister
                        """,
                    )
                }
            }
        }
    }
}
