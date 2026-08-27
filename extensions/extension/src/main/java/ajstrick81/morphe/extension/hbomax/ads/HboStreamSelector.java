package ajstrick81.morphe.extension.hbomax.ads;

import android.util.Log;

import java.util.List;

/**
 * HBO Max Android TV — prefer the ad-free FALLBACK stream.
 *
 * <p>An HBO Playable on the ad-supported tier carries two streams
 * ({@code com.discovery.player.common.models.StreamInfo}): a {@code PRIMARY}
 * stream whose manifest is ad-stitched ({@code …/dash.mpd}) and a {@code FALLBACK}
 * stream whose manifest is the clean, non-ad-stitched variant
 * ({@code …/<hash>_fallback.mpd} on the alternate CDN). HBO only switches to the
 * FALLBACK stream through its resiliency recovery after a load failure; normal
 * playback uses PRIMARY.
 *
 * <p>{@code PlayableKt.getStreamInfo(Playable, StreamInfo$Type)} resolves a stream
 * by type. This helper is injected at its entry to remap a {@code PRIMARY} request
 * to {@code FALLBACK} — but only when the Playable actually advertises a FALLBACK
 * stream, so titles without one (or non-ad content) are left exactly as-is. The
 * result: the player loads the ad-free manifest from the first request, on fresh
 * start and resume alike — no ads, no SSAI markers/timer, no timeline gaps, and no
 * resume 39999 (unlike the downstream origin block).
 *
 * <p>All access is by reflection on non-obfuscated
 * {@code com.discovery.player.common.models} names, so the extension needs no
 * compile-time dependency on the app. Fail-open: any error returns the original
 * requested type.
 */
@SuppressWarnings("unused")
public final class HboStreamSelector {

    private static final String TAG = "HboStreamSelector";
    private static final String PRIMARY = "PRIMARY";
    private static final String FALLBACK = "FALLBACK";

    private HboStreamSelector() {
    }

    /**
     * @param playable      the Playable whose stream is being resolved (getStreamInfo p0).
     * @param requestedType the StreamInfo$Type being requested (getStreamInfo p1).
     * @return FALLBACK type when PRIMARY was requested and a FALLBACK stream exists;
     *         otherwise the original requestedType (unchanged).
     */
    public static Object preferFallbackType(Object playable, Object requestedType) {
        try {
            if (playable == null || !(requestedType instanceof Enum)) {
                return requestedType;
            }
            if (!PRIMARY.equals(((Enum<?>) requestedType).name())) {
                return requestedType;
            }
            Object infosObj = playable.getClass().getMethod("getStreamInfos").invoke(playable);
            if (!(infosObj instanceof List)) {
                return requestedType;
            }
            for (Object streamInfo : (List<?>) infosObj) {
                if (streamInfo == null) {
                    continue;
                }
                Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
                if (type instanceof Enum && FALLBACK.equals(((Enum<?>) type).name())) {
                    Log.i(TAG, "remapping PRIMARY -> FALLBACK stream (ad-free manifest)");
                    return type;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "preferFallbackType failed; using requested type", t);
        }
        return requestedType;
    }
}
