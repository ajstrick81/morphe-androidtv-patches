package ajstrick81.morphe.extension.mlbtv.ads

import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Rewrites HLS playlists fetched by Media3's OkHttpDataSource (Lr5/a; in the
 * obfuscated MLB At Bat dex) to strip live SSAI/DAI ad segments before
 * ExoPlayer ever parses them.
 *
 * Why this exists: blocking the IMA SDK's own DAI/SSAI Java methods
 * (AtBatPatch.kt Patches 2/3/4) does NOT stop dclk_video_ads segments from
 * being fetched during live games — confirmed via logcat against v1.5.1.
 * The ad segments are stitched directly into the HLS manifest server-side,
 * so the player just sequentially requests whatever the manifest lists,
 * with no client-side "ad request" call to intercept. The only place left
 * to act is the manifest text itself, before ExoPlayer's HLS parser reads it.
 *
 * [wrap] is called from injected smali at the exact point where the data
 * source assigns the raw response InputStream to its `k` field — right
 * after the network read, before anything parses it. The `dataSpec`
 * parameter is passed through as `Any` (its real type, Lq5/i;, is obfuscated
 * and unavailable to this module at compile time); its Uri is pulled out via
 * reflection rather than by adding a smali register to extract it first,
 * which avoids any register-liveness risk in the injected call site.
 */
object MlbManifestRewriter {

    private const val TAG = "MORPHE-MLB-MANIFEST"

    /**
     * Info-gathering switch. When true, [wrap] logs a one-line census of the
     * ad-signaling markers found in each media playlist (see [logMarkerCensus]).
     * This is how we replace guesswork about MLB's manifest with fact: a single
     * `adb logcat -s $TAG` during a live break tells us exactly which of
     * CUE-OUT / DISCONTINUITY / DATERANGE / dclk MLB's DAI actually emits — the
     * open question the SCTE-35 / producer-side research left unanswered. Flip
     * to false to silence it; it changes no playback behavior either way.
     */
    private const val LOG_MARKER_CENSUS = true

    /**
     * Ad-segment URI substrings. Segments whose URI contains one of these are
     * physically removed from the playlist by [stripAdSegments]. Confirmed
     * present in MLB's live manifests via logcat (dclk_video_ads).
     */
    private val SEGMENT_AD_MARKERS = listOf(
        "dclk_video_ads",
        "doubleclick.net",
        "googlesyndication.com",
    )

    /**
     * Vendor-neutral HLS / SCTE-35 ad-break signaling tag matching a DATERANGE
     * row flagged as an ad. Used only to DETECT an active break (to drive the
     * overlay), not to strip — removing a DATERANGE without the segments it
     * brackets could desync the player timeline.
     */
    private val DATERANGE_AD_REGEX =
        Regex("#EXT-X-DATERANGE:[^\\n]*CLASS=\"ad", RegexOption.IGNORE_CASE)

    private const val TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY"

    @JvmStatic
    fun wrap(dataSpec: Any, stream: InputStream): InputStream {
        val uri = extractUri(dataSpec) ?: return stream
        val path = uri.toString()
        if (!path.contains(".m3u8")) return stream

        val bytes = stream.readBytes()
        val text = bytes.toString(Charsets.UTF_8)

        if (LOG_MARKER_CENSUS) logMarkerCensus(path, text)

        if (!containsAdBreakMarkers(text)) {
            return ByteArrayInputStream(bytes)
        }

        // Ground-truth ad-break signal. A live media playlist actually carrying
        // ad markers is the most reliable indication a commercial break is on
        // air — far more dependable than IMA's onAdBreakStarted() callback,
        // which was never observed firing. Drive the "Commercial Break in
        // Progress" overlay straight off it so it appears mid-inning even when
        // the IMA callback path is dead. See AdBreakOverlayHelper.
        AdBreakOverlayHelper.signalAdBreak()

        val rewritten = stripAdSegments(text)
        return ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8))
    }

    /**
     * True if this playlist looks like it carries an active ad break. Covers
     * both MLB's concrete ad-segment URIs and the standard HLS/SCTE-35 markers
     * a break is delimited with, so the overlay still triggers if the segment
     * domains ever change. #EXT-X-CUE-OUT also matches #EXT-X-CUE-OUT-CONT; the
     * bare #EXT-X-CUE-IN (break END) is intentionally not treated as active.
     *
     * Note: a bare #EXT-X-DISCONTINUITY is deliberately NOT a trigger here — it
     * marks any timeline break (codec/PID/timestamp change), not specifically an
     * ad, so it would false-fire the overlay. Discontinuity is used only for the
     * census and for cleaning up the strip (see [stripAdSegments]).
     */
    private fun containsAdBreakMarkers(text: String): Boolean {
        if (SEGMENT_AD_MARKERS.any { text.contains(it) }) return true
        if (text.contains("#EXT-X-CUE-OUT")) return true
        return DATERANGE_AD_REGEX.containsMatchIn(text)
    }

    private fun extractUri(dataSpec: Any): Uri? = try {
        dataSpec.javaClass.fields
            .firstOrNull { it.type == Uri::class.java }
            ?.apply { isAccessible = true }
            ?.get(dataSpec) as? Uri
    } catch (t: Throwable) {
        Log.w(TAG, "failed to read DataSpec uri via reflection", t)
        null
    }

    /**
     * Emits a one-line census of ad-signaling markers for a media playlist, so
     * a field-test logcat reveals exactly what MLB's DAI puts in the manifest.
     * Skips the master playlist and pure-content media playlists (nothing
     * informative to report) to keep the log readable.
     */
    private fun logMarkerCensus(path: String, text: String) {
        var segments = 0
        var adSegments = 0
        var discontinuity = 0
        var discontinuitySeq = 0
        var cueOut = 0
        var cueIn = 0

        for (raw in text.split("\n")) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> segments++
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> discontinuitySeq++
                line == TAG_DISCONTINUITY -> discontinuity++
                line.startsWith("#EXT-X-CUE-OUT") -> cueOut++
                line.startsWith("#EXT-X-CUE-IN") -> cueIn++
                line.isNotEmpty() && !line.startsWith("#") &&
                    SEGMENT_AD_MARKERS.any { line.contains(it) } -> adSegments++
            }
        }
        val dateRangeAd = DATERANGE_AD_REGEX.findAll(text).count()

        // Only log playlists that carry at least one signal worth studying.
        if (adSegments == 0 && discontinuity == 0 && cueOut == 0 && cueIn == 0 && dateRangeAd == 0) {
            return
        }
        Log.d(
            TAG,
            "manifest census: segments=$segments adSegments=$adSegments " +
                "discontinuity=$discontinuity discontinuitySeq=$discontinuitySeq " +
                "cueOut=$cueOut cueIn=$cueIn dateRangeAd=$dateRangeAd :: $path",
        )
    }

    /**
     * Drops any #EXTINF segment line (and its preceding tags, up to the
     * previous segment or the start of the playlist) whose URI matches a
     * known ad marker. Anything that isn't an ad segment — including
     * #EXT-X-KEY/etc tags attached to real segments — is left untouched, since
     * removing unrelated tags could desync the player's segment timeline.
     *
     * DISCONTINUITY handling: an ad break is normally bracketed by a
     * #EXT-X-DISCONTINUITY on each side (content→ad, ad→content). Removing the
     * ad segments already drops the opening bracket (it rides in the ad
     * segment's pending tags); the closing bracket is kept, so one discontinuity
     * remains where the break was. That is deliberately conservative — a spare
     * discontinuity costs at most a decoder reset, whereas a missing one that
     * was actually needed causes a hard timeline desync. As a safety net,
     * [collapseAdjacentDiscontinuities] removes any *consecutive* duplicates
     * that back-to-back break removal could leave behind.
     */
    private fun stripAdSegments(playlist: String): String {
        val lines = playlist.split("\n")
        val output = ArrayList<String>(lines.size)
        var pendingTags = ArrayList<String>()
        var removedSegments = 0

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    pendingTags.add(line)
                }
                SEGMENT_AD_MARKERS.any { trimmed.contains(it) } -> {
                    // Ad segment URI — discard it and every tag queued for it.
                    pendingTags = ArrayList()
                    removedSegments++
                }
                else -> {
                    output.addAll(pendingTags)
                    output.add(line)
                    pendingTags = ArrayList()
                }
            }
        }
        output.addAll(pendingTags)

        val collapsed = collapseAdjacentDiscontinuities(output)
        Log.d(TAG, "stripped $removedSegments ad segment(s) from manifest")
        return collapsed.joinToString("\n")
    }

    /**
     * Removes a #EXT-X-DISCONTINUITY line whenever the previous non-blank line
     * is also a #EXT-X-DISCONTINUITY, so ad removal can never leave two
     * discontinuities in a row (which the player would read as two timeline
     * breaks and could over-reset the decoder on).
     */
    private fun collapseAdjacentDiscontinuities(lines: List<String>): List<String> {
        val result = ArrayList<String>(lines.size)
        var lastNonBlankWasDiscontinuity = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == TAG_DISCONTINUITY && lastNonBlankWasDiscontinuity) {
                continue
            }
            result.add(line)
            if (trimmed.isNotEmpty()) {
                lastNonBlankWasDiscontinuity = trimmed == TAG_DISCONTINUITY
            }
        }
        return result
    }
}
