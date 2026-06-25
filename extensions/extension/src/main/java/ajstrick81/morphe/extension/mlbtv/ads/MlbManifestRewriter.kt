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

    @JvmStatic
    fun wrap(dataSpec: Any, stream: InputStream): InputStream {
        val uri = extractUri(dataSpec) ?: return stream
        val path = uri.toString()
        if (!path.contains(".m3u8")) return stream

        val bytes = stream.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
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
        Log.d(TAG, "ad break detected; stripped ad segments from manifest: $path")
        return ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8))
    }

    /**
     * True if this playlist looks like it carries an active ad break. Covers
     * both MLB's concrete ad-segment URIs and the standard HLS/SCTE-35 markers
     * a break is delimited with, so the overlay still triggers if the segment
     * domains ever change. #EXT-X-CUE-OUT also matches #EXT-X-CUE-OUT-CONT; the
     * bare #EXT-X-CUE-IN (break END) is intentionally not treated as active.
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
     * Drops any #EXTINF segment line (and its preceding tags, up to the
     * previous segment or the start of the playlist) whose URI matches a
     * known ad marker. Anything that isn't an ad segment — including
     * #EXT-X-DISCONTINUITY/#EXT-X-KEY/etc tags attached to real segments —
     * is left untouched, since removing unrelated tags could desync the
     * player's segment timeline.
     */
    private fun stripAdSegments(playlist: String): String {
        val lines = playlist.split("\n")
        val output = ArrayList<String>(lines.size)
        var pendingTags = ArrayList<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    pendingTags.add(line)
                }
                SEGMENT_AD_MARKERS.any { trimmed.contains(it) } -> {
                    // Ad segment URI — discard it and every tag queued for it.
                    pendingTags = ArrayList()
                }
                else -> {
                    output.addAll(pendingTags)
                    output.add(line)
                    pendingTags = ArrayList()
                }
            }
        }
        output.addAll(pendingTags)
        return output.joinToString("\n")
    }
}
