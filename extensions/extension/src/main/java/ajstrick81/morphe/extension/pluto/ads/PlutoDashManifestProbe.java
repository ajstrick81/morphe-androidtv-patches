package ajstrick81.morphe.extension.pluto.ads;

import android.util.Log;

import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.BaseUrl;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.Representation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pluto TV — VOD ad REMOVAL by DASH period surgery.
 *
 * <p>The shipped {@code getAdBreaks() -> emptyList} hook only empties the ad-break
 * metadata (markers/beacons/UI/seek); the ad VIDEO still plays because the stitched
 * DASH manifest media3 fetches is a MULTI-PERIOD timeline carrying the ads as real
 * {@code <Period>}s. This closes that gap.
 *
 * <p>media3 is <b>not obfuscated</b> in the Pluto build, so {@code skipAdsPatch}
 * Hook 5 routes the parsed manifest through {@link #stripAdPeriods} at
 * {@code DashManifestParser.parse}'s return — post-download, post-parse. It never
 * touches the streaming OkHttp client (wrapping that black-screens playback), so it
 * cannot break the fetch pipeline.
 *
 * <p><b>Classification (issue #144 fix, 2026-09-03, on-device recon of two titles):</b>
 * detect ADS <i>positively</i> by the ad-creative URL signature and keep everything
 * else. The old heuristic — "content == a {@code pDRM} rendition path" — was WRONG:
 * <ul>
 *   <li>Some titles serve their real content <b>un-DRM'd</b> (Supermarket Sweep:
 *       {@code siloh…/627_Fremantle/clip/…_Supermarket_Sweep_Episode_1151/720p/…}),
 *       so the pDRM test dropped all content and collapsed the episode to a 10s stub
 *       → the player looped / skipped / autoplay-advanced (the #144 report).</li>
 *   <li>The only {@code pDRM} period on that title was a 10s <b>ad bumper</b>
 *       ({@code …/196_Pluto_TV_OandO/clip/…_ad_bumper_animation…/1080pDRM/}), so the
 *       pDRM test actually KEPT the ad and threw away the show. That same bumper rides
 *       along on DRM'd titles too, where the old code silently kept it.</li>
 * </ul>
 * Both ad flavors carry an unambiguous marker that content never does:
 * <ul>
 *   <li>ad spots → {@code _ad/creative} in the path (URL-encoded {@code _ad%2Fcreative}
 *       under {@code /v1/mp4/…/p(…)}),</li>
 *   <li>the bumper → {@code _ad_bumper}.</li>
 * </ul>
 * Content clips ({@code …/clip/{id}_{ShowName}_{Episode}/…}) contain neither, DRM or
 * not. Verified: catches all ad spots + both bumpers across Supermarket Sweep (un-DRM'd
 * content) and Workaholics (DRM'd content) with zero content false-positives.
 */
@SuppressWarnings("unused")
public final class PlutoDashManifestProbe {

    private static final String TAG = "MORPHE-DASH-MF";

    // ── Resume-overshoot fix (issue #147) ──────────────────────────────────────
    // Removing ad <Period>s shortens the content timeline (e.g. 29:42 -> 22:02).
    // Pluto restores a "Continue Watching" bookmark expressed in the ORIGINAL
    // (ad-inclusive) timeline and Avia bakes it into the media asset as a start
    // position (AviaPlayer.startExoplayer -> Player.seekTo). If that bookmark sits
    // past the new, shorter duration, ExoPlayer seeks past the end -> STATE_ENDED
    // -> Pluto autoplay-advances to the next episode (the one-off skip in #147).
    //
    // Fix: remember the ad periods we removed from the most-recently stripped VOD
    // manifest, keyed by their ORIGINAL start, so {@link #mapResumePosition} can
    // convert an original-timeline bookmark into stripped-timeline coordinates
    // (subtract the ad time removed before it) — landing the viewer at the correct
    // content position instead of clamping them to the end. Only the current title
    // plays at a time, so the most-recent strip is the right offset table; every
    // fail-open / live passthrough clears it so no stale mapping is ever applied.
    private static volatile long[] removedAdStartsMs = new long[0]; // original-timeline starts, ascending
    private static volatile long[] removedAdDursMs = new long[0];   // matching removed durations
    private static volatile long lastStrippedDurationMs = 0;        // new (content-only) total, ms
    // Land a remapped resume slightly inside content so it never lands exactly at
    // the end (which itself would end the item and autoplay-advance).
    private static final long RESUME_END_MARGIN_MS = 5_000L;

    /** Clear the resume-offset table (called whenever a manifest is passed through unstripped). */
    private static void clearResumeOffsets() {
        removedAdStartsMs = new long[0];
        removedAdDursMs = new long[0];
        lastStrippedDurationMs = 0;
    }

    /**
     * True if a period is an AD (an ad spot or the ad bumper), detected by the
     * ad-creative URL signature. Everything that is NOT an ad — real content, whether
     * DRM'd or not, and any unrecognized period — is kept, so the strip can never drop
     * content it doesn't recognize (the failure mode behind issue #144). At worst an
     * ad flavor we don't yet know stays in (an ad plays), never a lost episode.
     */
    static boolean isAdPeriod(Period period) {
        try {
            for (AdaptationSet set : period.adaptationSets) {
                for (Representation rep : set.representations) {
                    List<BaseUrl> baseUrls = rep.baseUrls;
                    if (baseUrls == null) continue;
                    for (BaseUrl base : baseUrls) {
                        if (base.url == null) continue;
                        String u = base.url;
                        // ad spots: "_ad/creative" — the '/' arrives percent-encoded
                        // (%2F) in the stitched manifest, so match both forms.
                        if (u.contains("_ad%2Fcreative") || u.contains("_ad/creative")) return true;
                        // the pDRM ad-bumper animation between pods
                        if (u.contains("_ad_bumper")) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Defensive: any structural surprise -> treat as NOT an ad (keep it), so we
            // never drop content on a parse we couldn't fully inspect.
        }
        return false;
    }

    /**
     * Called from injected smali at the return of {@code DashManifestParser.parse}.
     * Removes ad periods and rebuilds a contiguous content-only manifest.
     *
     * <p>Drops the ad periods ({@link #isAdPeriod}), re-bases each kept period's
     * {@code startMs} to run immediately after the previous one (closing the gaps the
     * removed ad periods leave — a gap would strand media3 on a missing period), and
     * rebuilds the {@link DashManifest} with the shrunken duration. Fail-open on every
     * edge (dynamic/live manifest, nothing kept, an implausible over-strip, or any
     * exception) → returns the ORIGINAL manifest untouched, so playback never breaks.
     */
    public static DashManifest stripAdPeriods(DashManifest manifest) {
        try {
            if (manifest == null) return null;

            // Reset the resume-offset table up front; only a SUCCESSFUL strip below
            // repopulates it, so every passthrough/fail-open path leaves it cleared
            // and mapResumePosition() becomes a no-op (issue #147).
            clearResumeOffsets();

            // LIVE TV guard. Only VOD is in scope: on-demand titles are STATIC DASH
            // (manifest.dynamic == false). Live/linear channels are DYNAMIC DASH — a
            // moving live edge and wall-clock timeline; re-basing period startMs there
            // corrupts it (player loops/rebuffers, reported on Nvidia Shield v1.17.0).
            // Live ads are real broadcast time and were never removable. VOD's brief
            // dynamic startup manifest is passed through harmlessly (the ad periods live
            // in the static full manifest that follows).
            if (manifest.dynamic) {
                Log.i(TAG, "dynamic (live) manifest -> untouched, periods=" + manifest.getPeriodCount());
                return manifest;
            }

            int n = manifest.getPeriodCount();
            List<Period> kept = new ArrayList<>();
            // Removed ad periods, keyed by ORIGINAL start, for the resume remap (#147).
            List<long[]> removedSpans = new ArrayList<>(); // {originalStartMs, durationMs}
            long cursor = 0;
            int removed = 0;
            for (int i = 0; i < n; i++) {
                Period p = manifest.getPeriod(i);
                long durMs = manifest.getPeriodDurationMs(i);
                if (isAdPeriod(p)) {
                    removed++;
                    // p.startMs is the ORIGINAL (ad-inclusive) timeline start.
                    removedSpans.add(new long[] { p.startMs, durMs });
                } else {
                    // Rebuild at the re-based start; adaptation sets, event streams and
                    // asset identifier carry through intact (segment timelines are
                    // period-relative, so re-basing startMs keeps them addressable).
                    kept.add(new Period(p.id, cursor, p.adaptationSets, p.eventStreams, p.assetIdentifier));
                    cursor += durMs;
                }
            }
            if (kept.isEmpty()) {
                Log.w(TAG, "no content periods kept (of " + n + ") -> passthrough");
                return manifest;
            }
            // FAIL-SAFE GUARD (issue #144 backstop): real ad load is ~18-30% of runtime
            // (content stays >=~70%). If we'd keep less than 40% of the original
            // duration, an unrecognized content shape has probably been mis-dropped;
            // bail to the original so a future stitching change can never re-introduce
            // the collapse-to-stub regression. With the positive ad-detector this should
            // not trip on known titles — it's a safety net, not the primary mechanism.
            if (manifest.durationMs > 0 && cursor < manifest.durationMs * 4 / 10) {
                Log.w(TAG, "GUARD tripped: kept " + kept.size() + "/" + n + " -> "
                        + cursor + "ms of " + manifest.durationMs + "ms (<40%) -> passthrough");
                return manifest;
            }
            DashManifest out = new DashManifest(
                manifest.availabilityStartTimeMs,
                cursor, // new total duration = sum of kept content durations
                manifest.minBufferTimeMs,
                manifest.dynamic,
                manifest.minUpdatePeriodMs,
                manifest.timeShiftBufferDepthMs,
                manifest.suggestedPresentationDelayMs,
                manifest.publishTimeMs,
                manifest.programInformation,
                manifest.utcTiming,
                manifest.serviceDescription,
                manifest.location,
                kept);
            // Publish the resume-offset table for this stripped manifest (#147).
            // removedSpans is already in ascending original-start order (periods are
            // iterated in timeline order).
            long[] starts = new long[removedSpans.size()];
            long[] durs = new long[removedSpans.size()];
            for (int i = 0; i < removedSpans.size(); i++) {
                starts[i] = removedSpans.get(i)[0];
                durs[i] = removedSpans.get(i)[1];
            }
            removedAdStartsMs = starts;
            removedAdDursMs = durs;
            lastStrippedDurationMs = cursor;
            Log.i(TAG, "STRIPPED " + removed + " ad periods, kept " + kept.size()
                + " content, newDurationMs=" + cursor + " (was " + manifest.durationMs + ")");
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "strip failed -> original manifest (playback unaffected): " + t.getMessage());
            return manifest;
        }
    }

    /**
     * Resume-overshoot fix (issue #147). Called from injected smali at
     * {@code AviaPlayer.startExoplayer}, right before the resume seek
     * {@code Player.seekTo(startPosition)}. Maps a bookmark expressed in the
     * ORIGINAL (ad-inclusive) timeline into the shortened, ad-stripped timeline so
     * the player never seeks past the end of the shorter content.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If nothing was stripped (live, fail-open, or a non-VOD asset), or the
     *       bookmark is already within the current (stripped) duration, the value is
     *       returned unchanged — normal seeks and resumes are untouched.</li>
     *   <li>Otherwise the bookmark overshoots the shorter content: subtract the ad
     *       time removed before it (from the strip's offset table) to land at the
     *       correct content position, then clamp just inside the end.</li>
     * </ul>
     * Fully fail-open: any surprise returns the original position, so resume can
     * never be broken by this hook.
     *
     * @param player   the media3 player about to be seeked (for its current duration)
     * @param originalPos the resume position Avia is about to seek to (original ms)
     * @return the position to actually seek to (stripped-timeline ms)
     */
    public static long mapResumePosition(androidx.media3.common.Player player, long originalPos) {
        try {
            if (originalPos <= 0) return originalPos;

            // Authoritative shortened duration: the live player's current window if
            // known, else the last strip's total. Both reflect the ad-stripped length.
            long playerDur = -1;
            try {
                if (player != null) playerDur = player.getDuration();
            } catch (Throwable ignored) { /* getDuration can throw before timeline settles */ }
            long strippedDur = playerDur > 0 ? playerDur : lastStrippedDurationMs;

            // No overshoot (or unknown duration) -> leave the seek exactly as-is.
            if (strippedDur <= 0 || originalPos <= strippedDur) return originalPos;

            // The bookmark is past the shortened content. Subtract the ad time
            // removed at or before it to recover the true content position.
            long[] starts = removedAdStartsMs;
            long[] durs = removedAdDursMs;
            long removedBefore = 0;
            for (int i = 0; i < starts.length && i < durs.length; i++) {
                if (starts[i] < originalPos) removedBefore += durs[i];
                else break; // ascending starts
            }
            long mapped = originalPos - removedBefore;

            // Clamp just inside content so we resume INTO the show, never at the very
            // end (an end-seek re-triggers the ENDED -> autoplay-advance this fixes).
            long maxPos = strippedDur - RESUME_END_MARGIN_MS;
            if (maxPos < 0) maxPos = 0;
            if (mapped > maxPos) mapped = maxPos;
            if (mapped < 0) mapped = 0;

            Log.i(TAG, "resume remap (#147): original=" + originalPos + " removedBefore="
                + removedBefore + " -> " + mapped + " (strippedDur=" + strippedDur + ")");
            return mapped;
        } catch (Throwable t) {
            Log.e(TAG, "resume remap failed -> original position (resume unaffected): " + t.getMessage());
            return originalPos;
        }
    }
}
