package ajstrick81.morphe.patches.hbomax.ads

// HBO Max "Remove Ad Periods (keep 5.1)" contributed by Joker4Life12 (github.com/Joker4Life12),
// from his fork Joker4Life12/morphe-androidtv-patches (branch hbo-51). Captured and tested
// on-device by him on HBO Max 7.14.0.69. Thank you!

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Remove Ad Periods (keeps the PRIMARY stream's 5.1 audio)
//
// "Prefer Ad-Free Stream" plays HBO's FALLBACK manifest, which is ad-free but carries
// stereo audio only (captured on device: codecs=[mp4a.40.5], channel config 2). The
// PRIMARY manifest has E-AC-3 5.1 (codecs include ec-3, channel config 6) but the ads are
// stitched in as extra <Period> elements. The content Periods of PRIMARY have the same
// durations as the Periods of the FALLBACK manifest; one PRIMARY content period may span
// several consecutive FALLBACK periods (seen on device: 1 vs. 5).
//
// This patch keeps the PRIMARY stream and removes the ad Periods before the player parses
// the manifest. Three hooks, all in HboManifestFilter:
//   1. PlayableKt.getStreamInfo (entry): captureStreams(playable) binds the FALLBACK
//      stream to the title (session keyed by the asset UUID in the URL) and prefetches the
//      FALLBACK manifest in the background (3 s total deadline).
//   2. CreateTimelineUseCase.invoke(StreamInfo) (entry): the app builds its ad UI ("Ad 1 of
//      2", seek lock) and chapter markers (skip intro / next episode) from the StreamInfo's
//      timeline payload. PRIMARY's payload lists the ad breaks in the stitched time base;
//      FALLBACK's has none and uses the content time base. On device this hook runs BEFORE
//      the manifest parse, on a worker thread. It runs a dry run of the period filter on
//      the PRIMARY manifest and only switches to the FALLBACK StreamInfo if that succeeds.
//   3. ExoPlayerDashManifestParser.parse(Uri, InputStream) (entry): filter(uri, stream)
//      drops the ad periods and recomputes Period starts and the MPD duration.
// A per-session decision (FILTER / PASS-THROUGH) is shared by hooks 2 and 3, so timeline
// and manifest can not disagree. Period matching: 1 ms tolerance, exactly ONE alignment of
// content periods; ambiguity is resolved only by audio-language count (content 14-24 vs.
// ads 2-4), otherwise nothing is changed. Fail-open on any doubt.
//
// Exclusive with "HBO Max - Prefer Ad-Free Stream": when this patch is installed that remap
// is switched off at runtime (dependsOn makes this patch's hook run first).
// Recommended together with "HBO Max - Disable Ads".
//
// Verified on HBO Max (Android TV) 7.14.0.69 (Nvidia Shield TV): 21 playbacks (movies, series),
// 20x FILTER, 1x PASS-THROUGH (fallback unavailable), 0x MISALIGNED; 5.1 stays, ad UI and
// seek lock are gone, skip intro / next episode timings are correct. Not verified on
// device: a title without a FALLBACK stream and a real duration collision (synthetic tests only).
// ─────────────────────────────────────────────────────────────────────────────

internal object ExoPlayerDashManifestParserParseFingerprint : Fingerprint(
    custom = { method, _ ->
        // The class name is not obfuscated. A bridge method with return type Object
        // also exists; the real one returns the (obfuscated) DashManifest type.
        method.definingClass ==
            "Lcom/discovery/player/exoplayer/ExoPlayerDashManifestParser;" &&
            method.name == "parse" &&
            method.returnType != "Ljava/lang/Object;" &&
            method.parameterTypes.size == 2
    },
)

internal object CreateTimelineUseCaseInvokeFingerprint : Fingerprint(
    custom = { method, _ ->
        // Unobfuscated class. A bridge invoke(Object) also exists; the real one takes StreamInfo.
        method.definingClass ==
            "Lcom/discovery/adtech/sdk/compat/services/CreateTimelineUseCase;" &&
            method.name == "invoke" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString() == "Lcom/discovery/player/common/models/StreamInfo;"
    },
)

@Suppress("unused")
val hboRemoveAdPeriodsPatch = bytecodePatch(
    name = "HBO Max - Remove Ad Periods (keep 5.1)",
    description = "Keeps the normal PRIMARY stream (with 5.1 / E-AC-3 audio) but removes the " +
        "stitched ad periods from its DASH manifest before playback. Overrides " +
        "'Prefer Ad-Free Stream' (its stereo-only fallback remap is switched off automatically). " +
        "Fail-open: if the manifest cannot be matched, it is left unchanged.",
    default = false,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    // Exclusivity with "Prefer Ad-Free Stream": that patch's PRIMARY->FALLBACK remap defeats
    // this one. Depending on it makes it run BEFORE this patch, so this patch's
    // captureStreams() hook (added at index 0 afterwards) executes first at runtime and
    // switches the remap off (HboStreamSelector.preferFallbackType returns early when
    // HboManifestFilter.isActive()). So it is safe to leave "Prefer Ad-Free Stream" on.
    dependsOn(hboPreferFallbackStreamPatch)

    // Merges HboManifestFilter into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // getStreamInfo is static: p0 = Playable. Remember the FALLBACK manifest URL.
        GetStreamInfoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, Lajstrick81/morphe/extension/hbomax/ads/HboManifestFilter;->captureStreams(Ljava/lang/Object;)V
            """.trimIndent(),
        )

        // parse(Uri, InputStream): p0 = this, p1 = Uri, p2 = InputStream.
        // Replace the manifest stream with the filtered one.
        ExoPlayerDashManifestParserParseFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1, p2}, Lajstrick81/morphe/extension/hbomax/ads/HboManifestFilter;->filter(Ljava/lang/Object;Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p2
            """.trimIndent(),
        )

        // invoke(StreamInfo): p0 = this, p1 = StreamInfo. The ad UI, seek lock and chapter
        // markers are built from the StreamInfo's timeline payload; use the FALLBACK
        // StreamInfo's (ad-free, content time base) instead of the PRIMARY's.
        CreateTimelineUseCaseInvokeFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, Lajstrick81/morphe/extension/hbomax/ads/HboManifestFilter;->timelineStreamInfo(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, Lcom/discovery/player/common/models/StreamInfo;
            """.trimIndent(),
        )
    }
}
