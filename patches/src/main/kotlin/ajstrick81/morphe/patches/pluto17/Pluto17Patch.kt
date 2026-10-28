/*
 * Pluto TV 17.x — Ad Patch
 *
 * Pluto TV 17 is a rebuild on Paramount+'s app platform (com.paramount.android.*, com.cbs.app.*), and
 * media3 is R8-renamed, so the 5.66.0-leanback hooks in patches/pluto don't exist in it. On-device
 * (Sony BRAVIA, Android 9, 17.2.0 TV build) its VOD still comes from Pluto's own stitcher as ONE
 * multi-period DASH manifest — the same shape 5.66 used: DRM'd content periods alternating with ad
 * periods whose BaseURL holds the creative path (_ad%2Fcreative%2F…) and pod bumpers (_ad_bumper).
 * The AVIA DAI ad gate the first version relied on is not used for this stream.
 *
 * Two layers, as in the 5.66 patch:
 *   1. The ad VIDEO: the manifest text goes through PlutoMpdFilter before media3's (renamed)
 *      DashManifestParser reads it; ad periods are dropped and the rest re-based contiguous.
 *      Fail-open: a manifest it can't rewrite is passed through unchanged.
 *   2. The ad TIMELINE: AdsData(adBreaks=…) — what draws the seekbar markers, fires the ad beacons
 *      and blocks seeking during breaks — is created with an empty list. With the ad periods gone its
 *      offsets would point into the wrong places anyway.
 *
 *   3. Pause ads: CbsPauseWithAdsOverlay's render step (R8-renamed) is a no-op.
 *
 * Not covered yet: live channels, and re-mapping a resume bookmark saved in the old
 * (ad-inclusive) timeline (5.66 issue #147).
 */

package ajstrick81.morphe.patches.pluto17

// Pluto TV 17.x support contributed by Mathias Thur (github.com/AllGray), from his fork
// AllGray/morphe-androidtv-patches. Tested on-device by him (Sony BRAVIA, Android 9). Thank you!

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import com.android.tools.smali.dexlib2.Opcode

private const val FILTER = "Lajstrick81/morphe/extension/pluto17/PlutoMpdFilter;"

@Suppress("unused")
val pluto17SkipAdsPatch = bytecodePatch(
    name = "Skip ads (Pluto TV 17)",
    description = "Removes on-demand ads in Pluto TV 17.x (the Paramount-based app): drops the ad " +
        "periods from the stitched DASH stream so the ad video never plays, and empties the ad-break " +
        "timeline (markers, beacons, seek blocking). Fail-open: a stream it can't rewrite plays as before. " +
        "Live TV ads are not covered. Experimental.",
) {
    compatibleWith(Constants.COMPATIBILITY_17)

    extendWith("extensions/extension.mpe")

    execute {
        // 1. DashManifestParser.parse(Uri p1, InputStream p2): filter the stream before anything reads it.
        DashManifestParse17Fingerprint.method.addInstructions(
            0,
            """
                invoke-static {p2}, $FILTER->filter(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p2
            """.trimIndent(),
        )

        // 2. AdsData.<init>(List adBreaks p1, String, long, long): always an empty list. The constructor has
        //    no free locals, so the empty list goes straight into the parameter register p1.
        val adsDataType = AdsDataToString17Fingerprint.classDef.type
        val adsData = mutableClassDefByOrNull { it.type == adsDataType }
            ?: error("Pluto 17: AdsData class $adsDataType not found")
        val init = adsData.methods.first {
            it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == "Ljava/util/List;"
        }
        init.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object p1
            """.trimIndent(),
        )

        // 2b. StitcherSession: once built, its adBreaks (the first ArrayList field) and adBreakLocations (its only List
        //     field) are emptied, so the player's time and countdown no longer count the removed breaks. clips (the
        //     second ArrayList) and the current adBreak are left alone. Field names are R8 letters; their types and
        //     order (as in the data class's toString) identify them.
        val sessionType = StitcherSessionToString17Fingerprint.classDef.type
        val session = mutableClassDefByOrNull { it.type == sessionType }
            ?: error("Pluto 17: StitcherSession class $sessionType not found")
        val adBreaksField = session.fields.first { it.type == "Ljava/util/ArrayList;" }.name
        val locationsField = session.fields.first { it.type == "Ljava/util/List;" }.name
        session.methods.filter { it.name == "<init>" }.forEach { ctor ->
            val returns = ctor.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.reversed()
            check(ctor.implementation!!.registerCount > ctor.parameters.size + 1) { "Pluto 17: no free register in StitcherSession.<init>" }
            returns.forEach { i ->
                ctor.addInstructions(
                    i,
                    """
                        new-instance v0, Ljava/util/ArrayList;
                        invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                        iput-object v0, p0, $sessionType->$adBreaksField:Ljava/util/ArrayList;
                        invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                        move-result-object v0
                        iput-object v0, p0, $sessionType->$locationsField:Ljava/util/List;
                    """.trimIndent(),
                )
            }
        }

        // 3. Pause ads (TV build): the overlay's render step returns at once, so the ad image is never
        //    loaded or faded in. The phone/tablet build has no such overlay, hence OrNull.
        PauseAdsRender17Fingerprint.methodOrNull?.addInstructions(0, "return-void")

        // Paramount's DAI gate, kept from the first version: harmless if unused.
        ShouldPlayAd17Fingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
    }
}
