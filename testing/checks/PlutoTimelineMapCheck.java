// Standalone check of the #300 timeline mapping math.
// The two mappers below are the bodies from PlutoV17ManifestProbe, with the
// Android Log calls removed, so this exercises the real algorithm.
public class PlutoTimelineMapCheck {

    static long[] removedAdStartsMs = new long[0];
    static long[] removedAdDursMs = new long[0];
    static long strippedDurationMs = 0;
    static long originalDurationMs = 0;
    static final long RESUME_END_MARGIN_MS = 5_000L;

    static boolean hasTimelineMap() {
        return removedAdStartsMs.length > 0 && strippedDurationMs > 0;
    }

    static long toStrippedMs(long originalMs) {
        if (originalMs <= 0 || !hasTimelineMap()) return originalMs;
        long[] starts = removedAdStartsMs;
        long[] durs = removedAdDursMs;
        long removedBefore = 0;
        for (int i = 0; i < starts.length && i < durs.length; i++) {
            if (starts[i] + durs[i] <= originalMs) {
                removedBefore += durs[i];
            } else if (starts[i] < originalMs) {
                removedBefore += originalMs - starts[i];
                break;
            } else {
                break;
            }
        }
        long mapped = originalMs - removedBefore;
        long maxPos = strippedDurationMs - RESUME_END_MARGIN_MS;
        if (maxPos < 0) maxPos = 0;
        if (mapped > maxPos) mapped = maxPos;
        if (mapped < 0) mapped = 0;
        return mapped;
    }

    static long toOriginalMs(long strippedMs) {
        if (strippedMs <= 0 || !hasTimelineMap()) return strippedMs;
        long[] starts = removedAdStartsMs;
        long[] durs = removedAdDursMs;
        long removedBefore = 0;
        for (int i = 0; i < starts.length && i < durs.length; i++) {
            long spanPosStripped = starts[i] - removedBefore;
            if (spanPosStripped <= strippedMs) removedBefore += durs[i];
            else break;
        }
        long mapped = strippedMs + removedBefore;
        if (originalDurationMs > 0 && mapped > originalDurationMs) mapped = originalDurationMs;
        return mapped;
    }

    static int failures = 0;

    static void check(String what, long got, long want) {
        boolean ok = got == want;
        if (!ok) failures++;
        System.out.printf("%-4s %-44s got=%-9d want=%d%n", ok ? "ok" : "FAIL", what, got, want);
    }

    public static void main(String[] args) {
        // A realistic VOD shape: 60 min of content with 4 x 2 min ad pods stitched in.
        // ORIGINAL timeline (ms): content 0-600k, AD 600k-720k, content 720k-1320k,
        // AD 1320k-1440k, content 1440k-2040k, AD 2040k-2160k, content 2160k-2760k,
        // AD 2760k-2880k, content 2880k-3480k.  original total 3480k (58 min),
        // stripped total 3000k (50 min of content), 480k of ads removed.
        removedAdStartsMs = new long[] { 600_000, 1_320_000, 2_040_000, 2_760_000 };
        removedAdDursMs   = new long[] { 120_000,   120_000,   120_000,   120_000 };
        strippedDurationMs = 3_000_000;
        originalDurationMs = 3_480_000;

        System.out.println("-- ORIGINAL -> STRIPPED (restoring a bookmark) --");
        check("start of title",              toStrippedMs(0),         0);          // identity guard
        check("before first ad",            toStrippedMs(300_000),    300_000);
        check("just before first ad",        toStrippedMs(599_999),    599_999);
        check("inside first ad pod",         toStrippedMs(660_000),    600_000);   // where content resumes
        check("end of first ad pod",         toStrippedMs(719_999),    600_000);
        check("first frame after ad pod",    toStrippedMs(720_000),    600_000);
        check("after 1 ad removed",          toStrippedMs(900_000),    780_000);
        check("after 2 ads removed",         toStrippedMs(1_800_000),  1_560_000);
        check("after 3 ads removed",         toStrippedMs(2_400_000),  2_040_000);
        check("after 4 ads removed",         toStrippedMs(3_000_000),  2_520_000);
        check("bookmark past stripped end",  toStrippedMs(3_470_000),  2_990_000);
        // A bookmark the app wrote at the very end of the ORIGINAL timeline must land
        // inside content, not at the stripped end (that is the #147 autoplay failure).
        check("end-of-title bookmark clamped", toStrippedMs(3_480_000), 2_995_000);

        System.out.println();
        System.out.println("-- STRIPPED -> ORIGINAL (saving a bookmark) --");
        check("start of title",             toOriginalMs(0),         0);
        check("before first ad",            toOriginalMs(300_000),    300_000);
        check("after 1 ad",                 toOriginalMs(780_000),    900_000);
        check("after 2 ads",                toOriginalMs(1_560_000),  1_800_000);
        check("after 3 ads",                toOriginalMs(2_040_000),  2_400_000);
        check("after 4 ads",                toOriginalMs(2_520_000),  3_000_000);
        check("near stripped end",          toOriginalMs(2_995_000),  3_475_000);
        check("clamped to original dur",    toOriginalMs(3_400_000),  3_480_000);

        System.out.println();
        System.out.println("-- round trip S -> O -> S must be identity on content positions --");
        boolean rtOk = true;
        for (long s = 1_000; s <= strippedDurationMs - RESUME_END_MARGIN_MS; s += 1_000) {
            long back = toStrippedMs(toOriginalMs(s));
            if (back != s) {
                System.out.println("FAIL round trip at stripped=" + s + " -> " + back);
                rtOk = false;
                failures++;
                break;
            }
        }
        if (rtOk) System.out.println("ok   round trip identity for every second of content");

        System.out.println();
        System.out.println("-- monotonicity: both mappers must never go backwards --");
        boolean monoOk = true;
        long prev = -1;
        for (long o = 0; o <= originalDurationMs; o += 500) {
            long m = toStrippedMs(o);
            if (m < prev) { System.out.println("FAIL O->S not monotone at " + o); monoOk = false; failures++; break; }
            prev = m;
        }
        prev = -1;
        for (long s = 0; s <= strippedDurationMs; s += 500) {
            long m = toOriginalMs(s);
            if (m < prev) { System.out.println("FAIL S->O not monotone at " + s); monoOk = false; failures++; break; }
            prev = m;
        }
        if (monoOk) System.out.println("ok   both mappers monotonically non-decreasing");

        System.out.println();
        System.out.println("-- no map loaded (passthrough title) must be identity --");
        removedAdStartsMs = new long[0];
        removedAdDursMs = new long[0];
        strippedDurationMs = 0;
        originalDurationMs = 0;
        check("O->S identity", toStrippedMs(1_234_567), 1_234_567);
        check("S->O identity", toOriginalMs(1_234_567), 1_234_567);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) System.exit(1);
    }
}
