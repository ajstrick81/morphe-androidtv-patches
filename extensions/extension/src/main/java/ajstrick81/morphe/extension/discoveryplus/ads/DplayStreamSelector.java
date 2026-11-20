package ajstrick81.morphe.extension.discoveryplus.ads;

import android.util.Log;

import java.util.List;

/**
 * discovery+ Android TV — prefer the FALLBACK stream over the ad-stitched PRIMARY one.
 *
 * <p>Injected at the entry of {@code PlayableKt.getStreamInfo(Playable, StreamInfo$Type)}.
 * A {@code PRIMARY} request is remapped to {@code FALLBACK} only when the Playable actually
 * advertises a FALLBACK stream; everything else is returned unchanged. Logs one line per
 * decision (tag {@code DplayStreamSelector}) so a device test can tell which path ran.
 *
 * <p>Reflection only, on non-obfuscated {@code com.discovery.player.common.models} names.
 * Fail-open: any error returns the original requested type.
 */
@SuppressWarnings("unused")
public final class DplayStreamSelector {

    private static final String TAG = "DplayStreamSelector";
    private static final String PRIMARY = "PRIMARY";
    private static final String FALLBACK = "FALLBACK";

    private DplayStreamSelector() {
    }

    public static Object preferFallbackType(Object playable, Object requestedType) {
        try {
            if (playable == null || !(requestedType instanceof Enum)
                    || !PRIMARY.equals(((Enum<?>) requestedType).name())) {
                return requestedType;
            }
            Object infos = playable.getClass().getMethod("getStreamInfos").invoke(playable);
            if (!(infos instanceof List)) {
                return requestedType;
            }
            for (Object streamInfo : (List<?>) infos) {
                if (streamInfo == null) {
                    continue;
                }
                Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
                if (type instanceof Enum && FALLBACK.equals(((Enum<?>) type).name())) {
                    Log.i(TAG, "remapping PRIMARY -> FALLBACK stream");
                    return type;
                }
            }
            Log.i(TAG, "no FALLBACK stream (" + ((List<?>) infos).size() + " streams); keeping PRIMARY");
        } catch (Throwable t) {
            Log.w(TAG, "preferFallbackType failed; using requested type", t);
        }
        return requestedType;
    }
}
