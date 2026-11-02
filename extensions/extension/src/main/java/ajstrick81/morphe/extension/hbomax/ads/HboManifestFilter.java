package ajstrick81.morphe.extension.hbomax.ads;

// HBO Max "Remove Ad Periods (keep 5.1)" contributed by Joker4Life12 (github.com/Joker4Life12),
// from his fork Joker4Life12/morphe-androidtv-patches (branch hbo-51). Captured and tested
// on-device by him on HBO Max 7.14.0.69. Thank you!

import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the stitched ad Periods from HBO Max's PRIMARY (SSAI) DASH manifest while
 * keeping that manifest's audio (incl. E-AC-3 5.1), and keeps the app's own timeline
 * (ad UI, seek lock, chapters) consistent with that decision.
 *
 * Identity evidence: the content Periods of the PRIMARY manifest have the same durations as the
 * Periods of the clean FALLBACK manifest (one PRIMARY content period may span several
 * consecutive FALLBACK periods). The PRIMARY Periods are only touched if exactly ONE assignment
 * exists; if several fit, the audio-language count (content 14-24 vs. ads 2-4) must settle it.
 * Otherwise nothing is changed.
 *
 * One instance of this class = one playback session (one title). Primary and fallback are
 * bound by the asset id (UUID) in the manifest URL, so a previous title (or a preloaded
 * next episode) can never filter another title. The session also holds the single shared
 * decision FILTER / PASS-THROUGH that both the manifest hook and the timeline hook obey,
 * regardless of which of the two runs first.
 *
 * Fail-open: on ANY doubt the original manifest bytes / StreamInfo are used untouched.
 *
 * Verified on HBO Max (Android TV) 7.14.0.69 (Nvidia Shield TV): 21 playbacks, 20x FILTER, 1x
 * PASS-THROUGH, 0x MISALIGNED. Not verified on device: a title without a FALLBACK stream and a
 * real duration collision (covered by synthetic tests only).
 */
@SuppressWarnings("unused")
public final class HboManifestFilter implements Runnable {

    private static final String TAG = "HboManifestFilter";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final double TOLERANCE_SECONDS = 0.001;
    /** Overall budget for waiting on / downloading the FALLBACK manifest. */
    private static final long DEADLINE_MS = 3000;
    private static final int MAX_SESSIONS = 8;

    private static final int UNDECIDED = 0;
    private static final int FILTER = 1;
    private static final int PASS_THROUGH = 2;

    private static final Pattern PERIOD =
            Pattern.compile("<Period\\b[^>]*>.*?</Period>", Pattern.DOTALL);
    private static final Pattern PERIOD_OPEN = Pattern.compile("<Period\\b[^>]*>");
    private static final Pattern ISO_DURATION = Pattern.compile(
            "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([0-9.]+)S)?)?$");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Map<String, HboManifestFilter> SESSIONS =
            new LinkedHashMap<String, HboManifestFilter>();
    private static final List<Integer> SEEN = new ArrayList<Integer>();
    private static volatile boolean active;

    // ---- per-session state ------------------------------------------------
    private final String key;
    private final String fallbackUrl;
    private final Object fallbackInfo;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile List<Double> fallbackDurations;
    private int mode = UNDECIDED; // guarded by this
    private volatile boolean parseSeen;
    private volatile boolean timelineSeen;

    private HboManifestFilter(String key, String fallbackUrl, Object fallbackInfo) {
        this.key = key;
        this.fallbackUrl = fallbackUrl;
        this.fallbackInfo = fallbackInfo;
    }

    /** Prefetches the FALLBACK Period durations in the background. */
    @Override
    public void run() {
        try {
            fallbackDurations = fetchPeriodDurations(fallbackUrl);
        } catch (Throwable t) {
            Log.w(TAG, "fallback prefetch failed: " + why(t));
            fallbackDurations = null;
        } finally {
            ready.countDown();
        }
    }

    private synchronized int mode() {
        return mode;
    }

    /** Sets the shared decision once; returns the decision that is now in force. */
    private synchronized int decide(int wanted, String reason) {
        if (mode == UNDECIDED) {
            mode = wanted;
            Log.i(TAG, "decision for " + key + ": "
                    + (wanted == FILTER ? "FILTER" : "PASS-THROUGH") + " (" + reason + ")");
        }
        return mode;
    }

    private boolean awaitFallback(long millis) {
        try {
            if (millis > 0) {
                ready.await(millis, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        List<Double> d = fallbackDurations;
        return ready.getCount() == 0 && d != null && !d.isEmpty();
    }

    /** True once the "keep 5.1" patch is installed (its hooks have run at least once). */
    public static boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------------
    // Hook 1 (PlayableKt.getStreamInfo entry): bind the FALLBACK stream to this title.
    // ---------------------------------------------------------------------

    public static void captureStreams(Object playable) {
        active = true;
        try {
            if (playable == null) {
                return;
            }
            Integer id = System.identityHashCode(playable);
            synchronized (SEEN) {
                if (SEEN.contains(id)) {
                    return;
                }
                SEEN.add(id);
                while (SEEN.size() > 32) {
                    SEEN.remove(0);
                }
            }
            Object infosObj = playable.getClass().getMethod("getStreamInfos").invoke(playable);
            if (!(infosObj instanceof List)) {
                return;
            }
            for (Object streamInfo : (List<?>) infosObj) {
                if (streamInfo == null) {
                    continue;
                }
                Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
                if (!isFallback(type)) {
                    continue;
                }
                Object url = streamInfo.getClass().getMethod("getUrl").invoke(streamInfo);
                if (url == null || !String.valueOf(url).startsWith("http")) {
                    continue;
                }
                String u = String.valueOf(url);
                String k = keyOf(u);
                HboManifestFilter session = new HboManifestFilter(k, u, streamInfo);
                synchronized (SESSIONS) {
                    SESSIONS.remove(k);
                    SESSIONS.put(k, session);
                    while (SESSIONS.size() > MAX_SESSIONS) {
                        String eldest = SESSIONS.keySet().iterator().next();
                        SESSIONS.remove(eldest);
                    }
                }
                Log.i(TAG, "session " + k + ": fallback " + hostAndFile(u));
                Thread t = new Thread(session, "HboFallbackPrefetch");
                t.setDaemon(true);
                t.start();
                return;
            }
            Log.i(TAG, "playable has no fallback stream - nothing is filtered for it");
        } catch (Throwable t) {
            Log.w(TAG, "captureStreams failed: " + why(t));
        }
    }

    private static HboManifestFilter sessionFor(String url) {
        String k = keyOf(url);
        synchronized (SESSIONS) {
            return SESSIONS.get(k);
        }
    }

    private static boolean isFallback(Object type) {
        if (type == null) {
            return false;
        }
        String s = String.valueOf(type).toLowerCase(Locale.ROOT);
        if (s.contains("fallback") || s.contains("secondary")) {
            return true;
        }
        if (type instanceof Enum) {
            return ((Enum<?>) type).name().toLowerCase(Locale.ROOT).contains("fallback");
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Hook 3 (CreateTimelineUseCase.invoke(StreamInfo) entry): the app builds its ad UI
    // (ad label/counter, seek lock) and its chapter markers (skip intro / next episode)
    // from the StreamInfo's timeline payload. The PRIMARY payload lists the ad breaks and
    // uses the stitched time base; the FALLBACK payload has no ads and uses the content
    // time base. Swap only if the manifest is (or will be) filtered for this session.
    // ---------------------------------------------------------------------

    public static Object timelineStreamInfo(Object streamInfo) {
        try {
            if (streamInfo == null) {
                return null;
            }
            Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
            if (isFallback(type)) {
                return streamInfo;
            }
            Object primaryUrl = streamInfo.getClass().getMethod("getUrl").invoke(streamInfo);
            if (primaryUrl == null) {
                return streamInfo;
            }
            HboManifestFilter s = sessionFor(String.valueOf(primaryUrl));
            if (s == null) {
                Log.i(TAG, "timeline: no fallback bound to " + hostAndFile(String.valueOf(primaryUrl))
                        + " - unchanged");
                return streamInfo;
            }
            s.timelineSeen = true;
            int m = s.mode();
            Log.i(TAG, "hook order: timeline hook runs " + (s.parseSeen ? "AFTER" : "BEFORE")
                    + " the manifest parse (decision so far: " + modeName(m) + ")");
            if (m == UNDECIDED) {
                // Timeline first (observed on device: always, on a worker thread). Decide up
                // front by a dry run on the PRIMARY manifest, so the timeline is only switched
                // when the manifest filter will really succeed. Never block the main thread.
                boolean main = isMainThread();
                String reason;
                boolean ok = s.awaitFallback(main ? 0 : DEADLINE_MS);
                if (!ok) {
                    reason = "fallback not available" + (main ? " (main thread, no wait)" : "");
                } else if (main) {
                    reason = "main thread, no dry run";
                    ok = false;
                } else {
                    String[] why = new String[1];
                    try {
                        String xml = fetchText(String.valueOf(primaryUrl), DEADLINE_MS);
                        s.apply(String.valueOf(primaryUrl), xml, why, true);
                        ok = why[0] == null;
                        reason = ok ? "dry run on primary manifest ok" : "dry run: " + why[0];
                    } catch (Throwable t) {
                        ok = false;
                        reason = "dry run failed: " + why(t);
                    }
                }
                m = s.decide(ok ? FILTER : PASS_THROUGH, "timeline first, " + reason);
            }
            if (m != FILTER) {
                return streamInfo;
            }
            Object payload = s.fallbackInfo.getClass().getMethod("getTimelinePayload")
                    .invoke(s.fallbackInfo);
            if (payload == null || String.valueOf(payload).isEmpty()) {
                return streamInfo;
            }
            Log.i(TAG, "timeline: using FALLBACK timeline (len=" + String.valueOf(payload).length()
                    + ") instead of PRIMARY");
            return s.fallbackInfo;
        } catch (Throwable t) {
            Log.w(TAG, "timelineStreamInfo failed: " + why(t));
            return streamInfo;
        }
    }

    private static boolean isMainThread() {
        try {
            return Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String modeName(int m) {
        return m == FILTER ? "FILTER" : m == PASS_THROUGH ? "PASS-THROUGH" : "undecided";
    }

    // ---------------------------------------------------------------------
    // Hook 2 (ExoPlayerDashManifestParser.parse entry): filter the manifest stream.
    // ---------------------------------------------------------------------

    public static InputStream filter(Object uri, InputStream in) {
        if (in == null) {
            return null;
        }
        byte[] original;
        try {
            original = readAll(in, Long.MAX_VALUE);
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + why(t));
            // Stream is consumed/broken; nothing sensible to return.
            return new ByteArrayInputStream(new byte[0]);
        }
        try {
            byte[] result = rewrite(String.valueOf(uri), original);
            return new ByteArrayInputStream(result != null ? result : original);
        } catch (Throwable t) {
            Log.w(TAG, "filter failed, passing manifest through: " + why(t));
            return new ByteArrayInputStream(original);
        }
    }

    /** Returns rewritten bytes, or null to pass the original through. */
    private static byte[] rewrite(String uri, byte[] original) throws Exception {
        if (uri.contains("_fallback")) {
            return null; // this IS the clean manifest
        }
        HboManifestFilter s = sessionFor(uri);
        if (s == null) {
            Log.i(TAG, "manifest " + hostAndFile(uri) + ": no fallback bound to this title - pass through");
            return null;
        }
        s.parseSeen = true;
        int m = s.mode();
        Log.i(TAG, "hook order: manifest parse runs " + (s.timelineSeen ? "AFTER" : "BEFORE")
                + " the timeline hook (decision so far: " + modeName(m) + ")");
        if (m == PASS_THROUGH) {
            Log.i(TAG, "manifest left unchanged to match the unchanged timeline");
            return null;
        }
        String problem = null;
        byte[] out = null;
        if (!s.awaitFallback(DEADLINE_MS)) {
            problem = "fallback manifest not available";
        } else {
            String[] why = new String[1];
            out = s.apply(uri, new String(original, "UTF-8"), why, false);
            problem = why[0];
        }
        if (problem != null) {
            if (m == FILTER) {
                Log.w(TAG, "MISALIGNED: timeline was already switched to the fallback timeline but the"
                        + " manifest stays unfiltered (" + problem + ")");
            } else {
                s.decide(PASS_THROUGH, "manifest: " + problem);
            }
            return null;
        }
        // Success (out == null means: nothing to remove - the content-time timeline still fits).
        int now = s.decide(FILTER, "manifest filtered");
        if (now != FILTER) {
            Log.i(TAG, "timeline already decided pass-through - manifest left unchanged");
            return null;
        }
        return out;
    }

    /**
     * Aligns the PRIMARY periods with the FALLBACK content and, unless {@code dry}, returns the
     * rewritten manifest. why[0] == null means "alignment ok" (the result may still be null when
     * there is nothing to remove or when dry); otherwise why[0] holds the problem.
     */
    private byte[] apply(String uri, String xml, String[] why, boolean dry) {
        List<Double> content = fallbackDurations;
        if (xml == null || !xml.contains("<MPD") || xml.contains("type=\"dynamic\"")) {
            why[0] = "not a static MPD";
            return null;
        }
        List<int[]> spans = new ArrayList<int[]>();
        List<Double> durations = new ArrayList<Double>();
        List<Integer> langCounts = new ArrayList<Integer>();
        Matcher pm = PERIOD.matcher(xml);
        while (pm.find()) {
            spans.add(new int[] {pm.start(), pm.end()});
            Matcher open = PERIOD_OPEN.matcher(pm.group());
            if (!open.find()) {
                why[0] = "unparsable period";
                return null;
            }
            double d = attrDuration(open.group(), "duration");
            if (d < 0) {
                why[0] = "period without duration";
                return null;
            }
            durations.add(d);
            langCounts.add(langs(pm.group()).size());
        }
        if (spans.isEmpty()) {
            why[0] = "no periods";
            return null;
        }
        boolean[] keep = align(durations, langCounts, content, why);
        if (keep == null) {
            logPeriods(xml, spans, durations, null);
            return null;
        }
        int removed = 0;
        for (boolean k : keep) {
            if (!k) {
                removed++;
            }
        }
        if (dry) {
            Log.i(TAG, "dry run for " + key + ": alignment ok, " + removed + " ad period(s) would be removed");
            return null;
        }
        logPeriods(xml, spans, durations, keep);
        if (removed == 0) {
            Log.i(TAG, "no ad periods found in manifest for " + key);
            return null;
        }

        int firstStart = spans.get(0)[0];
        int lastEnd = spans.get(spans.size() - 1)[1];
        double cursor = 0;
        int kept = 0;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            if (!keep[i]) {
                continue;
            }
            String period = xml.substring(spans.get(i)[0], spans.get(i)[1]);
            body.append(withStart(period, cursor));
            cursor += durations.get(i);
            kept++;
        }
        String prefix = xml.substring(0, firstStart)
                .replaceFirst("mediaPresentationDuration=\"[^\"]*\"",
                        "mediaPresentationDuration=\"" + iso(cursor) + "\"");
        StringBuilder out = new StringBuilder(xml.length());
        out.append(prefix).append(body).append(xml.substring(lastEnd));
        Log.i(TAG, "removed " + removed + " ad period(s); kept " + kept
                + " content period(s) (" + content.size() + " fallback period(s)); new duration "
                + iso(cursor) + " for " + hostAndFile(uri));
        try {
            return out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            why[0] = "encoding";
            return null;
        }
    }

    /**
     * Assigns the FALLBACK (content) periods to PRIMARY periods, in order. One PRIMARY content
     * period may cover SEVERAL consecutive FALLBACK periods (the stitcher merges content that
     * is not interrupted by an ad break); every other PRIMARY period is an ad. Returns the
     * keep-mask only if the alignment is unique; if several alignments fit, the one in which
     * every kept period has strictly more audio languages than every dropped period wins
     * (observed: content 14-24, ads 2-4); otherwise null (fail open).
     */
    private static boolean[] align(List<Double> primary, List<Integer> langCounts,
            List<Double> content, String[] why) {
        int n = primary.size();
        int m = content.size();
        if (m == 0) {
            why[0] = "fallback has no periods";
            return null;
        }
        double[] ps = new double[m + 1];
        for (int j = 0; j < m; j++) {
            ps[j + 1] = ps[j] + content.get(j);
        }
        // suf[i][j] = number of ways to finish from (primary period i, fallback period j)
        long[][] suf = new long[n + 1][m + 1];
        suf[n][m] = 1;
        for (int i = n - 1; i >= 0; i--) {
            for (int j = 0; j <= m; j++) {
                long ways = suf[i + 1][j]; // period i is an ad
                for (int k = 1; j + k <= m; k++) {
                    if (fits(primary.get(i), ps[j + k] - ps[j], k)) {
                        ways += suf[i + 1][j + k];
                    }
                }
                suf[i][j] = Math.min(ways, 100000L);
            }
        }
        long total = suf[0][0];
        if (total == 0) {
            why[0] = "content periods do not match the fallback";
            return null;
        }
        if (total > 200) {
            why[0] = "ambiguous match (" + total + " possible alignments)";
            return null;
        }
        List<boolean[]> found = new ArrayList<boolean[]>();
        enumerate(primary, ps, suf, 0, 0, new boolean[n], found);
        boolean[] chosen = null;
        if (found.size() == 1) {
            chosen = found.get(0);
        } else {
            int good = 0;
            for (boolean[] cand : found) {
                int minKept = Integer.MAX_VALUE;
                int maxDropped = -1;
                for (int i = 0; i < n; i++) {
                    if (cand[i]) {
                        minKept = Math.min(minKept, langCounts.get(i));
                    } else {
                        maxDropped = Math.max(maxDropped, langCounts.get(i));
                    }
                }
                if (minKept > maxDropped) {
                    good++;
                    chosen = cand;
                }
            }
            if (good != 1) {
                why[0] = "ambiguous match (" + found.size() + " alignments, " + good
                        + " pass the audio-language check)";
                return null;
            }
            Log.i(TAG, "ambiguous durations resolved by audio-language count (" + found.size()
                    + " alignments)");
        }
        return chosen;
    }

    private static boolean fits(double primaryDuration, double fallbackSum, int merged) {
        return Math.abs(primaryDuration - fallbackSum) <= 0.002 + 0.001 * merged;
    }

    private static void enumerate(List<Double> primary, double[] ps, long[][] suf, int i, int j,
            boolean[] keep, List<boolean[]> out) {
        int n = primary.size();
        int m = ps.length - 1;
        if (out.size() >= 200) {
            return;
        }
        if (i == n) {
            if (j == m) {
                out.add(keep.clone());
            }
            return;
        }
        if (suf[i + 1][j] > 0) { // period i is an ad
            keep[i] = false;
            enumerate(primary, ps, suf, i + 1, j, keep, out);
        }
        for (int k = 1; j + k <= m; k++) {
            if (fits(primary.get(i), ps[j + k] - ps[j], k) && suf[i + 1][j + k] > 0) {
                keep[i] = true;
                enumerate(primary, ps, suf, i + 1, j + k, keep, out);
                keep[i] = false;
            }
        }
    }

    private static void logPeriods(String xml, List<int[]> spans, List<Double> durations,
            boolean[] keep) {
        for (int i = 0; i < spans.size() && i < 24; i++) {
            String period = xml.substring(spans.get(i)[0], spans.get(i)[1]);
            Log.i(TAG, (keep == null ? "?" : keep[i] ? "keep" : "DROP") + " period[" + i + "] dur="
                    + durations.get(i) + " audioLangs=" + langs(period) + " " + signature(period));
        }
    }

    private static List<String> langs(String period) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<AdaptationSet\\b[^>]*contentType=\"audio\"[^>]*>").matcher(period);
        while (m.find() && out.size() < 24) {
            Matcher l = Pattern.compile("\\slang=\"([^\"]*)\"").matcher(m.group());
            out.add(l.find() ? l.group(1) : "?");
        }
        return out;
    }

    /** Structure hints (ids, masked paths) - diagnostic only, used to find more identity evidence. */
    private static String signature(String period) {
        StringBuilder sb = new StringBuilder();
        int sets = 0;
        List<String> ids = new ArrayList<String>();
        Matcher m = Pattern.compile("<AdaptationSet\\b[^>]*>").matcher(period);
        while (m.find()) {
            sets++;
            Matcher id = Pattern.compile("\\sid=\"([^\"]*)\"").matcher(m.group());
            if (id.find() && ids.size() < 8) {
                ids.add(id.group(1));
            }
        }
        sb.append("sets=").append(sets).append(" ids=").append(ids);
        Matcher media = Pattern.compile("(?:media|initialization)=\"([^\"]+)\"").matcher(period);
        if (media.find()) {
            sb.append(" media=").append(mask(media.group(1)));
        }
        Matcher base = Pattern.compile("<BaseURL[^>]*>([^<]*)</BaseURL>").matcher(period);
        if (base.find()) {
            sb.append(" base=").append(mask(base.group(1)));
        }
        return sb.toString();
    }

    private static String mask(String s) {
        int q = s.indexOf('?');
        String t = q >= 0 ? s.substring(0, q) : s;
        t = UUID.matcher(t).replaceAll("<uuid>");
        t = t.replaceAll("[0-9a-fA-F]{12,}", "<hex>").replaceAll("[0-9]+", "#");
        return t.length() > 100 ? t.substring(0, 100) + "..." : t;
    }

    private static String withStart(String period, double startSeconds) {
        Matcher open = PERIOD_OPEN.matcher(period);
        if (!open.find()) {
            return period;
        }
        String tag = open.group();
        String newTag;
        if (tag.matches("(?s).*\\sstart=\"[^\"]*\".*")) {
            newTag = tag.replaceFirst("(\\sstart=\")[^\"]*(\")",
                    "$1" + iso(startSeconds) + "$2");
        } else {
            newTag = tag.replaceFirst("<Period", "<Period start=\"" + iso(startSeconds) + "\"");
        }
        return newTag + period.substring(tag.length());
    }

    private static String fetchText(String url, long budgetMillis) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMillis;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) {
                Log.i(TAG, "manifest HTTP " + c.getResponseCode() + " for " + hostAndFile(url));
                return null;
            }
            return new String(readAll(c.getInputStream(), deadline), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static List<Double> fetchPeriodDurations(String url) throws Exception {
        String xml = fetchText(url, DEADLINE_MS);
        if (xml == null) {
            return null;
        }
        List<Double> list = new ArrayList<Double>();
        Matcher m = PERIOD_OPEN.matcher(xml);
        while (m.find()) {
            double d = attrDuration(m.group(), "duration");
            if (d < 0) {
                return null;
            }
            list.add(d);
        }
        return list;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Title key: the asset UUID in the URL path (same for primary and fallback), else the directory. */
    static String keyOf(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int scheme = u.indexOf("://");
        int slash = scheme >= 0 ? u.indexOf('/', scheme + 3) : 0;
        String path = slash >= 0 ? u.substring(slash) : u;
        Matcher m = UUID.matcher(path);
        if (m.find()) {
            return m.group().toLowerCase(Locale.ROOT);
        }
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    private static double attrDuration(String tag, String attr) {
        Matcher m = Pattern.compile("\\s" + attr + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? parseIso(m.group(1)) : -1;
    }

    static double parseIso(String s) {
        Matcher m = ISO_DURATION.matcher(s);
        if (!m.matches()) {
            return -1;
        }
        double d = m.group(1) != null ? Double.parseDouble(m.group(1)) * 86400 : 0;
        d += m.group(2) != null ? Double.parseDouble(m.group(2)) * 3600 : 0;
        d += m.group(3) != null ? Double.parseDouble(m.group(3)) * 60 : 0;
        d += m.group(4) != null ? Double.parseDouble(m.group(4)) : 0;
        return d;
    }

    static String iso(double seconds) {
        return String.format(Locale.US, "PT%.6fS", seconds);
    }

    private static byte[] readAll(InputStream in, long deadlineMillis) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IllegalStateException("manifest too large");
                }
                if (deadlineMillis != Long.MAX_VALUE && System.currentTimeMillis() > deadlineMillis) {
                    throw new IOException("deadline exceeded");
                }
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String hostAndFile(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int scheme = u.indexOf("://");
        int slash = scheme >= 0 ? u.indexOf('/', scheme + 3) : -1;
        return (slash >= 0 ? u.substring(0, slash) : u) + "/.../" + u.substring(u.lastIndexOf('/') + 1);
    }

    private static String why(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 4; i++) {
            sb.append(" | ").append(st[i]);
        }
        return sb.toString();
    }
}
