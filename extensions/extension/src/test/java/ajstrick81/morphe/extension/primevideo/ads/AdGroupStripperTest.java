package ajstrick81.morphe.extension.primevideo.ads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host unit test for {@link AdGroupStripper} — the shared strip-and-rebuild loop
 * behind skipAllMedia3AdGroups / skipAllExo2AdGroups. Uses a fake AdState so it
 * runs on a plain JVM with no media3 / exoplayer2 / Guava / Gradle:
 *
 *   javac -d /tmp/adtest \
 *     main/java/ajstrick81/morphe/extension/primevideo/ads/AdGroupStripper.java \
 *     test/java/ajstrick81/morphe/extension/primevideo/ads/AdGroupStripperTest.java
 *   java -cp /tmp/adtest ajstrick81.morphe.extension.primevideo.ads.AdGroupStripperTest
 *
 * Exits non-zero on any failed assertion.
 */
public class AdGroupStripperTest {

    private static int failures = 0;

    private static void check(boolean cond, String what) {
        if (!cond) { System.out.println("FAIL: " + what); failures++; }
        else       { System.out.println("ok:   " + what); }
    }

    // Stand-in for an AdPlaybackState: immutable pair of (total, removed).
    private static final class FakeState {
        final int total, removed;
        FakeState(int total, int removed) { this.total = total; this.removed = removed; }
    }

    // The seam a wrapper would supply, but over FakeState. withAllRemoved
    // returns a NEW instance with removed == total (mirrors withRemovedAdGroupCount).
    private static final AdGroupStripper.AdState FAKE_OPS = new AdGroupStripper.AdState() {
        public int adGroupCount(Object s) { return ((FakeState) s).total; }
        public int removedAdGroupCount(Object s) { return ((FakeState) s).removed; }
        public Object withAllRemoved(Object s) {
            FakeState f = (FakeState) s;
            return new FakeState(f.total, f.total);
        }
    };

    private static Map<Object, Object> map(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) {
        // ── single state, fully unstripped → all groups removed ──────────────
        {
            FakeState s = new FakeState(3, 0);
            AdGroupStripper.Result r = AdGroupStripper.stripAll(map("k", s), FAKE_OPS);
            check(r.strippedGroups == 3, "single: stripped count = total - removed (3)");
            FakeState out = (FakeState) r.map.get("k");
            check(out.total == 3 && out.removed == 3, "single: value now fully removed");
            check(out != s, "single: stripped value is a new instance");
        }

        // ── partially-removed state → strips only the remainder ──────────────
        {
            FakeState s = new FakeState(5, 2);
            AdGroupStripper.Result r = AdGroupStripper.stripAll(map("k", s), FAKE_OPS);
            check(r.strippedGroups == 3, "partial: stripped = 5 - 2 = 3");
            check(((FakeState) r.map.get("k")).removed == 5, "partial: removed bumped to total");
        }

        // ── already fully-stripped state → untouched, same instance, 0 count ─
        {
            FakeState s = new FakeState(2, 2);
            AdGroupStripper.Result r = AdGroupStripper.stripAll(map("k", s), FAKE_OPS);
            check(r.strippedGroups == 0, "already-stripped: contributes 0");
            check(r.map.get("k") == s, "already-stripped: same instance carried over (no rebuild)");
        }

        // ── zero-group state → untouched, 0 ──────────────────────────────────
        {
            FakeState s = new FakeState(0, 0);
            AdGroupStripper.Result r = AdGroupStripper.stripAll(map("k", s), FAKE_OPS);
            check(r.strippedGroups == 0, "zero-group: contributes 0");
            check(r.map.get("k") == s, "zero-group: same instance carried over");
        }

        // ── mixed map: keys preserved, order preserved, counts summed ────────
        {
            FakeState a = new FakeState(2, 0);  // strip 2
            FakeState b = new FakeState(3, 3);  // already done, 0
            FakeState c = new FakeState(4, 1);  // strip 3
            Map<Object, Object> in = map("a", a, "b", b, "c", c);
            AdGroupStripper.Result r = AdGroupStripper.stripAll(in, FAKE_OPS);

            check(r.strippedGroups == 5, "mixed: total stripped = 2 + 0 + 3 = 5");
            check(r.map.size() == 3, "mixed: all entries retained");
            check(r.map.containsKey("a") && r.map.containsKey("b") && r.map.containsKey("c"),
                  "mixed: all keys preserved");
            check(r.map.get("b") == b, "mixed: already-done entry untouched");
            check(((FakeState) r.map.get("a")).removed == 2 &&
                  ((FakeState) r.map.get("c")).removed == 4, "mixed: stripped entries fully removed");

            List<Object> order = new ArrayList<>(r.map.keySet());
            check(order.equals(java.util.Arrays.asList("a", "b", "c")), "mixed: insertion order preserved");
        }

        // ── empty map → empty result, 0 ──────────────────────────────────────
        {
            AdGroupStripper.Result r = AdGroupStripper.stripAll(map(), FAKE_OPS);
            check(r.map.isEmpty() && r.strippedGroups == 0, "empty: empty result, 0 stripped");
        }

        // ── ops exception propagates (wrapper owns the fail-safe) ────────────
        {
            AdGroupStripper.AdState throwing = new AdGroupStripper.AdState() {
                public int adGroupCount(Object s) { throw new ClassCastException("not an AdPlaybackState"); }
                public int removedAdGroupCount(Object s) { return 0; }
                public Object withAllRemoved(Object s) { return s; }
            };
            boolean threw = false;
            try {
                AdGroupStripper.stripAll(map("k", new Object()), throwing);
            } catch (RuntimeException ex) {
                threw = true;
            }
            check(threw, "fail-safe: ops exception propagates out of stripAll (not swallowed)");
        }

        System.out.println();
        System.out.println((failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED")
                + " (" + failures + " failure(s))");
        System.exit(failures == 0 ? 0 : 1);
    }
}
