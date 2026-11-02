package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import ajstrick81.morphe.patches.shared.ads.ImaPauseAdDisplayFingerprint

@Suppress("unused")
val skipAdsV17Patch = bytecodePatch(
    name = "Skip ads (v17)",
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
    }
}
