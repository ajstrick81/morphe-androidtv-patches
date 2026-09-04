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
            long cursor = 0;
            int removed = 0;
            for (int i = 0; i < n; i++) {
                Period p = manifest.getPeriod(i);
                long durMs = manifest.getPeriodDurationMs(i);
                if (isAdPeriod(p)) {
                    removed++;
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
            Log.i(TAG, "STRIPPED " + removed + " ad periods, kept " + kept.size()
                + " content, newDurationMs=" + cursor + " (was " + manifest.durationMs + ")");
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "strip failed -> original manifest (playback unaffected): " + t.getMessage());
            return manifest;
        }
    }
}
