package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import ajstrick81.morphe.patches.shared.ads.ImaPauseAdDisplayFingerprint
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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
        // live channels keep the app's own value.
        //
        // The DAI provider is REQUIRED, not optional. Pluto VOD routes through it (Hook 1
        // depends on the same class), and an optional lookup here was a real mistake the
        // first time: all three were methodOrNull, so when nothing matched the patch still
        // reported "Applied" and silently did nothing. The bar stayed wrong and the absent
        // log line was indistinguishable from "no discrepancy found". A hard lookup makes a
        // fingerprint break loud instead. IMA and Uri stay optional — a given title routes
        // through one provider, so the other two legitimately may not be present.
        // Hook 8 — neutralise the stale stitcher ad-break metadata.
        //
        // This is the v17 port of the leanback patch's Hook 4
        // (StitcherSession.getAdBreaks() -> emptyList()). On 5.66 that single hook was
        // enough, because every consumer — the timebar's AdBreakPositionSource, the slot
        // controllers, the ID3 beacon tracker — read the breaks through that one getter.
        // v17 has no tv/pluto package and no such getter: the data arrives as a Kotlin
        // data class, R8-minified, whose list is read directly with iget-object. The
        // equivalent choke point is therefore its CONSTRUCTOR, which every instance
        // passes through.
        //
        // Two separate pieces of ad metadata have to be neutralised, because v17 splits
        // what leanback kept in one place:
        //
        //   AdsData.adBreaks            the ad-break list itself (markers, ad state,
        //                               beacons, the early end-of-content transition)
        //   ThumbnailTemplate
        //     .adBreaksDurationInSec    the pre-summed ad total that app code subtracts
        //                               from the content duration
        //
        // The second is the #300 seekbar bug, traced on-device: app code does
        //     bar = contentDuration - adBreaksDurationInSec
        // but after Hook 3 contentDuration ALREADY excludes the ads, so the ad time comes
        // off twice. Measured on 17.2.0: content 2524.031s, ads 761.141s, bar 1762.89s =
        // 29:23 against a 42:04 episode. Playback then runs past the end of the bar, and
        // AviaDAIResourceProvider$Listener's "position + 500ms < contentDuration" check
        // reports end-of-content ~12 minutes early, which is the likely cause of the
        // stuck ad overlay with a locked timeline.
        //
        // NOT hooked: AviaResourceProvider.getContentDuration(). That was tried and
        // reverted — on-device it returns -1 (an early-exit path), so it never fed the
        // bar at all. The log proved the hook ran and substituted a correct value with no
        // effect on the UI, which is what pointed at app-level code instead.
        //
        // Both classes are matched by a string constant from their own toString(), since
        // R8 renames the classes (observed as gm and xuh in 17.2.0) but cannot rename
        // string literals. Both substitutions are identity whenever no strip is live, so
        // live/linear and passthrough titles keep the app's own values.
        val adsDataClass = mutableClassDefByOrNull { classDef ->
            classDef.hasStringConstant(ADS_DATA_MARKER)
        }
        checkNotNull(adsDataClass) {
            "skipAdsV17Patch Hook 8: no class with the $ADS_DATA_MARKER constant"
        }
        adsDataClass.methods
            .filter {
                // parameterTypes holds CharSequence, so compare via toString().
                it.name == "<init>" &&
                    it.parameterTypes.firstOrNull()?.toString() == "Ljava/util/List;"
            }
            .also {
                check(it.isNotEmpty()) {
                    "skipAdsV17Patch Hook 8: AdsData has no (List, ...) constructor"
                }
            }
            .forEach { constructor ->
                // p1 is the adBreaks list: the first parameter after `this`.
                constructor.addInstructions(
                    0,
                    """
                        invoke-static {p1}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->neutralizeAdBreaks(Ljava/util/List;)Ljava/util/List;
                        move-result-object p1
                    """,
                )
            }

        // StitcherSession.adBreaks — the actual leanback Hook 4 target.
        //
        // The leanback patch emptied tv.pluto.library.playercommon.data.StitcherSession
        // .getAdBreaks(). That class survives in v17: R8 renames it (v1h in 17.2.0) but
        // its toString() still spells "StitcherSession(id=", and its adBreaks field is
        // still the list the timebar's position source reads. Emptying AdsData.adBreaks
        // and zeroing ThumbnailTemplate.adBreaksDurationInSec both took effect on device
        // and neither moved the seekbar, which is what identified this as the real one.
        //
        // adBreaks is the FIRST ArrayList parameter of the constructor (followed by
        // adBreakLocations), so it is located by type and position rather than by a
        // hardcoded register, and the register is computed from the parameter widths so
        // the injection survives a signature change.
        val stitcherSessionClass = mutableClassDefByOrNull { classDef ->
            classDef.hasStringConstant(STITCHER_SESSION_MARKER)
        }
        checkNotNull(stitcherSessionClass) {
            "skipAdsV17Patch Hook 8: no class with the $STITCHER_SESSION_MARKER constant"
        }
        stitcherSessionClass.methods
            .filter { method ->
                method.name == "<init>" &&
                    method.parameterTypes.any { it.toString() == "Ljava/util/ArrayList;" }
            }
            .also {
                check(it.isNotEmpty()) {
                    "skipAdsV17Patch Hook 8: StitcherSession has no (… ArrayList …) constructor"
                }
            }
            .forEach { constructor ->
                val parameters = constructor.parameterTypes.map { it.toString() }
                val adBreaksIndex = parameters.indexOfFirst { it == "Ljava/util/ArrayList;" }
                fun widthOf(type: String) = if (type == "J" || type == "D") 2 else 1
                // p0 (`this`) sits at registerCount minus the total parameter width.
                val parameterWidth = 1 + parameters.sumOf { widthOf(it) }
                val thisRegister = constructor.implementation!!.registerCount - parameterWidth
                val adBreaksRegister = thisRegister + 1 +
                    parameters.take(adBreaksIndex).sumOf { widthOf(it) }
                constructor.addInstructions(
                    0,
                    """
                        invoke-static/range {v$adBreaksRegister .. v$adBreaksRegister}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->neutralizeStitcherAdBreaks(Ljava/util/ArrayList;)Ljava/util/ArrayList;
                        move-result-object v$adBreaksRegister
                    """,
                )
            }

        val thumbnailTemplateClass = mutableClassDefByOrNull { classDef ->
            classDef.hasStringConstant(AD_BREAK_DURATION_MARKER)
        }
        checkNotNull(thumbnailTemplateClass) {
            "skipAdsV17Patch Hook 8: no class with the $AD_BREAK_DURATION_MARKER constant"
        }
        thumbnailTemplateClass.methods
            .filter { it.name == "<init>" && it.parameterTypes.size > 1 }
            .also {
                check(it.isNotEmpty()) {
                    "skipAdsV17Patch Hook 8: ThumbnailTemplate has no multi-arg constructor"
                }
            }
            .forEach { constructor ->
                // adBreaksDurationInSec is the LAST parameter and is wide, so it occupies
                // the top two registers. Range form for the Hook 3 encoding reason.
                val registerCount = constructor.implementation!!.registerCount
                val low = registerCount - 2
                constructor.addInstructions(
                    0,
                    """
                        invoke-static/range {v$low .. v${low + 1}}, Lajstrick81/morphe/extension/pluto/ads/PlutoV17ManifestProbe;->zeroAdBreakDuration(J)J
                        move-result-wide v$low
                    """,
                )
            }
    }
}

// toString() literals from the two ad-metadata data classes. R8 renames the classes but
// not these constants, so they are the stable anchors.
private const val STITCHER_SESSION_MARKER = "StitcherSession(id="
private const val ADS_DATA_MARKER = "AdsData(adBreaks="
private const val AD_BREAK_DURATION_MARKER = ", adBreaksDurationInSec="

/** True if any method of this class loads [text] as a string constant. */
private fun ClassDef.hasStringConstant(text: String): Boolean = methods.any { method ->
    method.implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == text
    } == true
}

