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
 * <p>Classification (on-device, tv.pluto.android 5.66.0-leanback): content periods
 * carry a DRM rendition path ({@code 720pDRM}/{@code 1080pDRM}/…); BOTH ad flavors
 * are un-DRM'd — {@code siloh…/_ad/creative/…} and {@code …/head(…)/sign/v1/…}. So a
 * {@code pDRM} rendition marker rendition-independently identifies content (verified:
 * 14 content periods = the exact 2:18:41 runtime, 62 un-DRM'd ad periods = 31:17,
 * zero unclassified; 2:49:58 → 2:18:41 with mid-rolls gone).
 */
@SuppressWarnings("unused")
public final class PlutoDashManifestProbe {

    private static final String TAG = "MORPHE-DASH-MF";

    /**
     * True if a period is real movie CONTENT (not an ad): any representation BaseUrl
     * carries a DRM rendition path ({@code pDRM}). Both ad flavors are un-DRM'd.
     */
    static boolean isContentPeriod(Period period) {
        try {
            for (AdaptationSet set : period.adaptationSets) {
                for (Representation rep : set.representations) {
                    List<BaseUrl> baseUrls = rep.baseUrls;
                    if (baseUrls == null) continue;
                    for (BaseUrl base : baseUrls) {
                        if (base.url != null && base.url.contains("pDRM")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Defensive: any structural surprise -> treat as content (never over-strip).
        }
        return false;
    }

    /**
     * Called from injected smali at the return of {@code DashManifestParser.parse}.
     * Removes ad periods and rebuilds a contiguous content-only manifest.
     *
     * <p>Keeps only content periods ({@link #isContentPeriod}), re-bases each kept
     * period's {@code startMs} to run immediately after the previous one (closing the
     * gaps the removed ad periods leave — a gap would strand media3 on a missing
     * period), and rebuilds the {@link DashManifest} with the shrunken duration.
     * Fail-open: if anything is unexpected (no content periods found, any exception)
     * it returns the ORIGINAL manifest untouched, so playback never breaks — at worst
     * the ads remain.
     */
    public static DashManifest stripAdPeriods(DashManifest manifest) {
        try {
            if (manifest == null) return null;

            // LIVE TV guard. Only VOD is in scope: on-demand titles are STATIC DASH
            // (manifest.dynamic == false, a fixed set of periods with a known total
            // duration). Live/linear channels are DYNAMIC DASH — the manifest is
            // continuously re-issued with a moving live edge and a wall-clock
            // timeline, and re-basing period startMs there corrupts that timeline:
            // the player can't advance past a period boundary and loops/rebuffers on
            // the same few seconds (reported on Nvidia Shield, v1.17.0). Live ads are
            // real broadcast time and were never removable anyway, so leave dynamic
            // manifests completely untouched. (VOD briefly serves a dynamic startup
            // manifest too; passing it through is harmless — the ad periods live in
            // the static full manifest that follows.)
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
                if (isContentPeriod(p)) {
                    // Rebuild at the re-based start; adaptation sets, event streams and
                    // asset identifier carry through intact (segment timelines are
                    // period-relative, so re-basing startMs keeps them addressable).
                    kept.add(new Period(p.id, cursor, p.adaptationSets, p.eventStreams, p.assetIdentifier));
                    cursor += durMs;
                } else {
                    removed++;
                }
            }
            if (kept.isEmpty()) {
                Log.w(TAG, "no content periods found (of " + n + ") -> passthrough");
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
