package ajstrick81.morphe.extension.primevideo.ads;

import android.util.Log;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;

/**
 * Prime Video ATV — ad suppression extension.
 *
 * Entry points called from patched bytecode:
 *
 *   skipAllMedia3AdGroups — strips ad schedule from media3 AdPlaybackState map
 *   skipAllExo2AdGroups   — same for ExoPlayer2 / GMS Ads variant
 *   enforceAdBlock        — Volley BasicNetwork.performRequest() host filter
 *
 * Note: Hook 3 (MetricsTransporter.transmit) uses pure inline smali to
 * construct a fake UploadResult("SUCCESS", "ok") directly — no Java extension
 * method needed since UploadResult lives in the app's own DEX.
 *
 * --- AdPlaybackState suppression (Hooks 1 & 2) ---
 *
 * withRemovedAdGroupCount(adGroupCount) physically removes all ad groups
 * from the AdPlaybackState before ExoPlayer sees the map. Operates at the
 * SSAI schedule layer — primary suppression for standard ad delivery.
 *
 * Confirmed via dex disassembly that there is no separate Java-level
 * pipeline for the WASM-rendered Ignite UI layer (pause overlays, promo
 * panels, etc.) — that UI is driven entirely by Amazon's native/WASM
 * IgniteRenderer via an opaque message bus (GMBMessageProcessor), with no
 * discrete interceptable class. Any ad content rendered through that path
 * is out of scope for bytecode hooking.
 *
 * --- Volley network-layer filtering (Hook 4) ---
 *
 * Prime Video has zero OkHttp classes anywhere in its dex set; its
 * app-layer HTTP client is Volley, wired by Amazon's own VolleyModule
 * (com.amazon.ignitionshared.network.VolleyModule) through a HurlStack
 * wrapped in BasicNetwork — the sole implementation of Volley's Network
 * interface in the app, with no app-specific subclass. enforceAdBlock()
 * runs at the top of BasicNetwork.performRequest(Request) and rejects
 * known ad-decisioning/ad-tracking hosts, plus the getVideoAds path on
 * the dual-use atv-ps.amazon.com playback host, before any HTTP work
 * happens.
 *
 * Throwing a real NoConnectionError (rather than faking a successful
 * response) matters: Volley's own RetryPolicy/NetworkDispatcher already
 * knows how to handle this exception type for genuine connectivity
 * failures, so the request fails the same way it would on a real network
 * error — no faked contract, no risk of hanging a caller that expects a
 * specific response shape.
 *
 * This hook does NOT suppress mid-roll ad segments — those are fetched by
 * Amazon's native MediaPipelineBackend (libcurl), a completely separate HTTP
 * stack that never touches Volley. Confirmed via on-device logcat: the
 * DOWNLOADER tag (native) fetches manifests/segments directly, including ad
 * segments delivered at an /iad_X/ path (where X is a segment ID) on the SAME safe-harbored content
 * CDN host as the movie itself - a path-only distinction invisible to both
 * this hook and DNS blocking. No bytecode-reachable mitigation exists for
 * that delivery mode on this build.
 *
 * All methods wrapped in try/catch - failures are logged to TAG below instead
 * of silently swallowed, so logcat can confirm whether the hook fired and
 * whether the strip actually took effect.
 */
@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public class SkipAdsPatch {

    private static final String TAG = "SkipAdsPatch";

    // ── AdPlaybackState suppression ───────────────────────────────────────────

    /**
     * Transforms an AdPlaybackState map for the media3 SSAI pipeline.
     *
     * Called at index 0 of:
     *   androidx.media3.exoplayer.source.ads.ServerSideAdInsertionMediaSource
     *       .setAdPlaybackStates(ImmutableMap, Timeline)
     */
    // Type-specific seam for media3's AdPlaybackState — the only part of the
    // strip that differs from the exo2 variant. The shared loop is in
    // AdGroupStripper (pure, unit-tested).
    private static final AdGroupStripper.AdState MEDIA3_OPS = new AdGroupStripper.AdState() {
        public int adGroupCount(Object s) {
            return ((androidx.media3.common.AdPlaybackState) s).adGroupCount;
        }
        public int removedAdGroupCount(Object s) {
            return ((androidx.media3.common.AdPlaybackState) s).removedAdGroupCount;
        }
        public Object withAllRemoved(Object s) {
            androidx.media3.common.AdPlaybackState st = (androidx.media3.common.AdPlaybackState) s;
            return st.withRemovedAdGroupCount(st.adGroupCount);
        }
    };

    public static ImmutableMap skipAllMedia3AdGroups(ImmutableMap adPlaybackStates) {
        try {
            AdGroupStripper.Result r = AdGroupStripper.stripAll(adPlaybackStates, MEDIA3_OPS);
            Log.i(TAG, "skipAllMedia3AdGroups: entries=" + adPlaybackStates.size()
                    + " strippedGroups=" + r.strippedGroups);
            return ImmutableMap.builder().putAll(r.map).build();
        } catch (Exception e) {
            Log.e(TAG, "skipAllMedia3AdGroups failed", e);
            return adPlaybackStates;
        }
    }

    /**
     * Transforms an AdPlaybackState map for the ExoPlayer2 SSAI pipeline
     * bundled inside the Google Mobile Ads SDK (classes4.dex).
     *
     * Called at index 0 of:
     *   com.google.android.exoplayer2.source.ads.ServerSideAdInsertionMediaSource
     *       .setAdPlaybackStates(ImmutableMap)
     */
    // Type-specific seam for exoplayer2's AdPlaybackState (GMS Ads SDK variant).
    private static final AdGroupStripper.AdState EXO2_OPS = new AdGroupStripper.AdState() {
        public int adGroupCount(Object s) {
            return ((com.google.android.exoplayer2.source.ads.AdPlaybackState) s).adGroupCount;
        }
        public int removedAdGroupCount(Object s) {
            return ((com.google.android.exoplayer2.source.ads.AdPlaybackState) s).removedAdGroupCount;
        }
        public Object withAllRemoved(Object s) {
            com.google.android.exoplayer2.source.ads.AdPlaybackState st =
                    (com.google.android.exoplayer2.source.ads.AdPlaybackState) s;
            return st.withRemovedAdGroupCount(st.adGroupCount);
        }
    };

    public static ImmutableMap skipAllExo2AdGroups(ImmutableMap adPlaybackStates) {
        try {
            AdGroupStripper.Result r = AdGroupStripper.stripAll(adPlaybackStates, EXO2_OPS);
            Log.i(TAG, "skipAllExo2AdGroups: entries=" + adPlaybackStates.size()
                    + " strippedGroups=" + r.strippedGroups);
            return ImmutableMap.builder().putAll(r.map).build();
        } catch (Exception e) {
            Log.e(TAG, "skipAllExo2AdGroups failed", e);
            return adPlaybackStates;
        }
    }

    // ── Volley network-layer filtering ────────────────────────────────────────

    /**
     * Called at index 0 of:
     *   com.android.volley.toolbox.BasicNetwork.performRequest(Request)
     *
     * Rejects requests to known ad-decisioning/ad-tracking hosts before any
     * HTTP work happens, by throwing a real NoConnectionError — the same
     * exception type Volley already raises for genuine connectivity
     * failures, so its RetryPolicy/NetworkDispatcher handle it exactly as
     * they would a real network outage.
     */
    public static void enforceAdBlock(Request<?> request) throws NoConnectionError {
        // Policy lives in AdHostFilter (pure, JVM-testable). This wrapper is
        // just the Volley surface: pull the URL, ask, and fail fast with a real
        // NoConnectionError — the same exception Volley raises for genuine
        // connectivity failures, so its RetryPolicy/NetworkDispatcher resolve
        // the app's ad-loading state machine immediately instead of stalling on
        // a response that will never arrive.
        //
        // The blocked set is the control-plane (Volley-routed) ad hosts plus the
        // getVideoAds decisioning PATH on the dual-use atv-ps.amazon.com host.
        // Media-plane ad segment CDNs (*.pv-cdn.net / *.akamaihd.net) go through
        // media3's DefaultHttpDataSource, not Volley, and are out of scope here.
        String reason = AdHostFilter.blockReason(request.getUrl());
        if (reason != null) {
            Log.i(TAG, "enforceAdBlock: blocking " + reason);
            throw new NoConnectionError(new IOException("ads_blocked: " + reason));
        }
    }

}
