package ajstrick81.morphe.extension.primevideo.ads;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prime Video ATV — SSAI ad-group strip (pure).
 *
 * The rebuild loop shared by {@link SkipAdsPatch#skipAllMedia3AdGroups} and
 * {@link SkipAdsPatch#skipAllExo2AdGroups}, which were byte-for-byte identical
 * apart from the concrete AdPlaybackState type. Both media3's
 * {@code androidx.media3.common.AdPlaybackState} and exoplayer2's
 * {@code com.google.android.exoplayer2.source.ads.AdPlaybackState} are Android
 * classes that can't be loaded on a plain JVM, so the loop is decoupled from
 * them through the {@link AdState} seam and can be unit-tested with a fake.
 *
 * The two wrappers keep only their type-specific {@link AdState} lambdas, the
 * ImmutableMap conversion, and the fail-safe try/catch.
 */
public final class AdGroupStripper {

    private AdGroupStripper() {}

    /**
     * Abstraction over one SSAI ad-playback state: its total ad-group count,
     * how many groups are already removed, and how to produce a copy with every
     * group removed. Keeps this class free of any concrete AdPlaybackState type.
     */
    public interface AdState {
        int adGroupCount(Object state);
        int removedAdGroupCount(Object state);
        Object withAllRemoved(Object state);
    }

    /** Rebuilt map (insertion order preserved) plus the total ad groups removed. */
    public static final class Result {
        public final Map<Object, Object> map;
        public final int strippedGroups;

        Result(Map<Object, Object> map, int strippedGroups) {
            this.map = map;
            this.strippedGroups = strippedGroups;
        }
    }

    /**
     * Rebuild {@code in} with every ad group removed from each value. A value
     * whose adGroupCount already equals removedAdGroupCount is left untouched
     * (the same instance is carried over — withAllRemoved is not called).
     *
     * Never swallows exceptions: if {@code ops} throws (e.g. a value isn't the
     * expected AdPlaybackState type), it propagates so the caller's fail-safe
     * can return the original, unmodified map.
     *
     * @return the rebuilt map (original iteration order preserved) and the count
     *         of ad groups stripped across all entries.
     */
    public static Result stripAll(Map<Object, Object> in, AdState ops) {
        Map<Object, Object> out = new LinkedHashMap<>();
        int stripped = 0;
        for (Map.Entry<Object, Object> e : in.entrySet()) {
            Object state = e.getValue();
            int total = ops.adGroupCount(state);
            int removed = ops.removedAdGroupCount(state);
            if (total > removed) {
                stripped += total - removed;
                state = ops.withAllRemoved(state);
            }
            out.put(e.getKey(), state);
        }
        return new Result(out, stripped);
    }
}
