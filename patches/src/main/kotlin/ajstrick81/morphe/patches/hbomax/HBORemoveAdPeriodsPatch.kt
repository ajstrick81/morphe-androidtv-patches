package ajstrick81.morphe.patches.hbomax.ads

// HBO Max "Remove Ad Periods (keep 5.1)" contributed by Joker4Life12 (github.com/Joker4Life12),
// from his fork Joker4Life12/morphe-androidtv-patches (branch hbo-51). Captured and tested
// on-device by him on HBO Max 7.9.0.61. Thank you!

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Remove Ad Periods (keeps the PRIMARY stream's 5.1 audio)
//
// "Prefer Ad-Free Stream" plays HBO's FALLBACK manifest, which is ad-free but
// carries stereo audio only (captured on-device: codecs=[mp4a.40.5],
// audioChannelConfiguration=[2]). The PRIMARY manifest has E-AC-3 5.1
// (codecs include ec-3, channel config 6) but the ads are stitched in as extra
// <Period> elements. The content Periods of PRIMARY have exactly the same
// durations as the Periods of the FALLBACK manifest.
//
// This patch keeps the PRIMARY stream and removes the ad Periods from its manifest
// before the player parses it:
//   1. PlayableKt.getStreamInfo (entry): HboManifestFilter.captureStreams(playable)
//      remembers the FALLBACK manifest URL.
//   3. CreateTimelineUseCase.invoke(StreamInfo) (entry): the app builds its ad UI
//      ("Werbung 1/2", seek lock) and chapter markers (skip intro / next episode) from the
//      StreamInfo's timeline payload. The PRIMARY payload still lists the ad breaks and
//      uses the stitched time base; the FALLBACK payload has no ads and uses the content
//      time base. So the timeline builder gets the FALLBACK StreamInfo.
//   2. ExoPlayerDashManifestParser.parse(Uri, InputStream) (entry): the manifest
//      stream is replaced by HboManifestFilter.filter(uri, stream). It fetches the
//      FALLBACK manifest, keeps only the PRIMARY Periods whose durations match the
//      FALLBACK content Periods, drops the rest, and recomputes Period start times
//      and the MPD duration.
// Fail-open: on any doubt the original manifest is passed through unchanged.
//
// Use WITHOUT "HBO Max - Prefer Ad-Free Stream" (that one forces the stereo stream).
// Recommended together with "HBO Max - Disable Ads".
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
        "stitched ad periods from its DASH manifest before playback. Do NOT combine with " +
        "'Prefer Ad-Free Stream' (that one forces the stereo-only fallback stream). " +
        "Fail-open: if the manifest cannot be matched, it is left unchanged.",
    default = false,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

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
