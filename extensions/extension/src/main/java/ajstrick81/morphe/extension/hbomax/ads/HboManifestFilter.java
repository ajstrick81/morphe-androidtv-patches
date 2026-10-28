package ajstrick81.morphe.extension.hbomax.ads;

// HBO Max "Remove Ad Periods (keep 5.1)" contributed by Joker4Life12 (github.com/Joker4Life12),
// from his fork Joker4Life12/morphe-androidtv-patches (branch hbo-51). Captured and tested
// on-device by him on HBO Max 7.9.0.61. Thank you!

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the stitched ad Periods from HBO Max's PRIMARY (SSAI) DASH manifest while
 * keeping that manifest's audio (incl. E-AC-3 5.1).
 *
 * Why this works (captured on-device, 7.9.0.61): the PRIMARY manifest consists of the
 * content Periods plus extra ad Periods; the content Periods have exactly the same
 * durations as the Periods of the clean FALLBACK manifest (which has stereo audio only).
 * So the FALLBACK Period durations tell us which PRIMARY Periods are content; every
 * other PRIMARY Period is an ad and is dropped. The Period start times and the MPD
 * duration are then recomputed so the timeline stays contiguous.
 *
 * Fail-open: on ANY doubt (no fallback known, durations do not match, dynamic MPD,
 * parse problem, network problem) the original manifest bytes are returned untouched.
 */
@SuppressWarnings("unused")
public final class HboManifestFilter {

    private static final String TAG = "HboManifestFilter";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final double TOLERANCE_SECONDS = 0.05;

    private static final Pattern PERIOD =
            Pattern.compile("<Period\\b[^>]*>.*?</Period>", Pattern.DOTALL);
    private static final Pattern PERIOD_OPEN = Pattern.compile("<Period\\b[^>]*>");
    private static final Pattern ISO_DURATION = Pattern.compile(
            "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([0-9.]+)S)?)?$");

    private static volatile int lastPlayable;
    private static volatile String fallbackUrl;
    private static volatile Object fallbackInfo;

    private static volatile String cachedUrl;
    private static volatile List<Double> cachedDurations;

    private HboManifestFilter() {
    }

    // ---------------------------------------------------------------------
    // Hook 1 (PlayableKt.getStreamInfo entry): remember the FALLBACK manifest URL.
    // ---------------------------------------------------------------------

    public static void captureStreams(Object playable) {
        try {
            if (playable == null) {
                return;
            }
            int id = System.identityHashCode(playable);
            if (id == lastPlayable) {
                return;
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
                if (url != null && String.valueOf(url).startsWith("http")) {
                    fallbackUrl = String.valueOf(url);
                    fallbackInfo = streamInfo;
                    lastPlayable = id;
                    Log.i(TAG, "fallback manifest known (" + hostAndFile(fallbackUrl) + ")");
                }
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "captureStreams failed: " + why(t));
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
    // from the StreamInfo's timeline payload. The PRIMARY payload still describes the
    // ad breaks and uses the stitched time base, which no longer matches the filtered
    // manifest. The FALLBACK payload has no ads and uses the content time base, so
    // hand the timeline builder the FALLBACK StreamInfo instead.
    // ---------------------------------------------------------------------

    public static Object timelineStreamInfo(Object streamInfo) {
        try {
            Object fb = fallbackInfo;
            if (fb == null || streamInfo == null || streamInfo == fb) {
                return streamInfo;
            }
            Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
            if (isFallback(type)) {
                return streamInfo;
            }
            Object primaryUrl = streamInfo.getClass().getMethod("getUrl").invoke(streamInfo);
            Object fallbackUrlObj = fb.getClass().getMethod("getUrl").invoke(fb);
            if (primaryUrl == null || fallbackUrlObj == null
                    || !sameDirectory(String.valueOf(primaryUrl), String.valueOf(fallbackUrlObj))) {
                Log.i(TAG, "timeline: primary/fallback do not belong together - unchanged");
                return streamInfo;
            }
            Object payload = fb.getClass().getMethod("getTimelinePayload").invoke(fb);
            if (payload == null || String.valueOf(payload).isEmpty()) {
                return streamInfo;
            }
            Log.i(TAG, "timeline: using FALLBACK timeline (len=" + String.valueOf(payload).length()
                    + ") instead of PRIMARY");
            return fb;
        } catch (Throwable t) {
            Log.w(TAG, "timelineStreamInfo failed: " + why(t));
            return streamInfo;
        }
    }

    private static boolean sameDirectory(String a, String b) {
        return directoryOf(a).equals(directoryOf(b));
    }

    private static String directoryOf(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int scheme = u.indexOf("://");
        int slash = scheme >= 0 ? u.indexOf('/', scheme + 3) : 0;
        String path = slash >= 0 ? u.substring(slash) : u;
        return path.substring(0, path.lastIndexOf('/') + 1);
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
            original = readAll(in);
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
        String fb = fallbackUrl;
        if (fb == null) {
            Log.i(TAG, "no fallback manifest known - pass through");
            return null;
        }
        if (uri.equals(fb) || uri.contains("_fallback")) {
            return null; // this IS the clean manifest
        }
        String xml = new String(original, "UTF-8");
        if (!xml.contains("<MPD") || xml.contains("type=\"dynamic\"")) {
            return null;
        }

        List<Double> content = fallbackDurations(fb);
        if (content == null || content.isEmpty()) {
            Log.i(TAG, "fallback durations unavailable - pass through");
            return null;
        }

        List<int[]> spans = new ArrayList<>();      // [start, end] of each <Period>...</Period>
        List<Double> durations = new ArrayList<>();
        Matcher m = PERIOD.matcher(xml);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            Matcher open = PERIOD_OPEN.matcher(m.group());
            if (!open.find()) {
                return null;
            }
            double d = attrDuration(open.group(), "duration");
            if (d < 0) {
                Log.i(TAG, "period without duration - pass through");
                return null;
            }
            durations.add(d);
        }
        if (spans.isEmpty()) {
            return null;
        }

        boolean[] keep = new boolean[spans.size()];
        int j = 0;
        int removed = 0;
        for (int i = 0; i < spans.size(); i++) {
            if (j < content.size()
                    && Math.abs(durations.get(i) - content.get(j)) <= TOLERANCE_SECONDS) {
                keep[i] = true;
                j++;
            } else {
                removed++;
            }
        }
        if (j != content.size()) {
            Log.i(TAG, "content periods do not match fallback (" + j + "/" + content.size()
                    + ") - pass through");
            return null;
        }
        if (removed == 0) {
            Log.i(TAG, "no ad periods found - pass through");
            return null;
        }

        StringBuilder out = new StringBuilder(xml.length());
        int firstStart = spans.get(0)[0];
        int lastEnd = spans.get(spans.size() - 1)[1];
        double cursor = 0;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            if (!keep[i]) {
                continue;
            }
            String period = xml.substring(spans.get(i)[0], spans.get(i)[1]);
            body.append(withStart(period, cursor));
            cursor += durations.get(i);
        }
        String prefix = xml.substring(0, firstStart)
                .replaceFirst("mediaPresentationDuration=\"[^\"]*\"",
                        "mediaPresentationDuration=\"" + iso(cursor) + "\"");
        out.append(prefix).append(body).append(xml.substring(lastEnd));

        Log.i(TAG, "removed " + removed + " ad period(s); kept " + content.size()
                + " content period(s); new duration " + iso(cursor));
        return out.toString().getBytes("UTF-8");
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

    private static List<Double> fallbackDurations(String url) throws Exception {
        if (url.equals(cachedUrl) && cachedDurations != null) {
            return cachedDurations;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) {
                Log.i(TAG, "fallback manifest HTTP " + c.getResponseCode());
                return null;
            }
            String xml = new String(readAll(c.getInputStream()), "UTF-8");
            List<Double> list = new ArrayList<>();
            Matcher m = PERIOD_OPEN.matcher(xml);
            while (m.find()) {
                double d = attrDuration(m.group(), "duration");
                if (d < 0) {
                    return null;
                }
                list.add(d);
            }
            cachedUrl = url;
            cachedDurations = list;
            return list;
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IllegalStateException("manifest too large");
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
