package ajstrick81.morphe.extension.pluto17;

// Pluto TV 17.x support contributed by Mathias Thur (github.com/AllGray), from his fork
// AllGray/morphe-androidtv-patches. Tested on-device by him (Sony BRAVIA, Android 9). Thank you!

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pluto TV 17: removes the ad periods from the stitched DASH manifest before the (R8-renamed) media3
 * DashManifestParser reads it, so the ad video is never played.
 *
 * Pluto's VOD stream is one multi-period MPD from its stitcher: content periods (DRM'd, under
 * /190_pluto/clip/...) alternate with ad periods whose BaseURL carries the creative path
 * ({@code _ad%2Fcreative%2F...}, URL-encoded) and short pod bumpers ({@code _ad_bumper}). This is the
 * same signature the 5.66 patch (PlutoDashManifestProbe) uses on parsed objects; media3 is obfuscated in
 * Pluto 17, so here it works on the XML text instead: drop every ad period, then give the kept periods
 * contiguous start times and shorten mediaPresentationDuration to match.
 *
 * Fail-open: anything unexpected (a dynamic/live MPD, no ad periods, any kept period without an explicit
 * duration, no /clip/ content left) returns the manifest unchanged, so ads may remain but playback never
 * breaks. A period is only dropped on positive evidence that it is an ad (see isAd).
 */
public final class PlutoMpdFilter {
    private static final String TAG = "MorphePluto17";
    private static final String[] AD_MARKERS = { "_ad%2Fcreative", "_ad/creative", "_ad_bumper", "_ad%2Fbumper" };
    private static final Pattern DURATION = Pattern.compile("duration=\"(PT[^\"]*)\"");
    private static final Pattern START = Pattern.compile("\\sstart=\"PT[^\"]*\"");
    private static final Pattern ISO = Pattern.compile("PT(?:([\\d.]+)H)?(?:([\\d.]+)M)?(?:([\\d.]+)S)?");

    private PlutoMpdFilter() {}

    /** Called at the start of DashManifestParser.parse(Uri, InputStream) with its InputStream. */
    public static InputStream filter(InputStream in) throws IOException {
        byte[] raw = readAll(in);
        try {
            String out = strip(new String(raw, StandardCharsets.UTF_8));
            if (out != null) return new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "manifest left as it was", t);
        }
        return new ByteArrayInputStream(raw);
    }

    static String strip(String mpd) {
        List<int[]> spans = new ArrayList<>();   // [start, end) of each <Period ...>...</Period>
        int from = 0;
        while (true) {
            int a = mpd.indexOf("<Period", from);
            if (a < 0) break;
            int b = mpd.indexOf("</Period>", a);
            if (b < 0) return null;
            b += "</Period>".length();
            spans.add(new int[] { a, b });
            from = b;
        }
        if (spans.isEmpty()) return null;
        // VOD only: the parser hook is global, so never touch a live (dynamic) MPD.
        String root = mpd.substring(0, spans.get(0)[0]);
        if (root.contains("type=\"dynamic\"")) return null;

        StringBuilder out = new StringBuilder(mpd.length());
        out.append(mpd, 0, spans.get(0)[0]);
        double t = 0;
        int dropped = 0, kept = 0, keptClips = 0;
        for (int i = 0; i < spans.size(); i++) {
            String p = mpd.substring(spans.get(i)[0], spans.get(i)[1]);
            if (isAd(p)) { dropped++; continue; }
            int headEnd = p.indexOf('>');
            String head = p.substring(0, headEnd + 1);
            Matcher dm = DURATION.matcher(head);
            double dur = dm.find() ? seconds(dm.group(1)) : -1;
            // Every kept period needs an explicit duration: it places the next period's start and the
            // rewritten mediaPresentationDuration. Without one (even on the last period) the new total
            // would be wrong (e.g. PT0.000S), so leave the manifest alone instead.
            if (dur <= 0) return null;
            if (hasClipBaseUrl(p)) keptClips++;
            String newHead = START.matcher(head).find()
                    ? START.matcher(head).replaceFirst(" start=\"" + iso(t) + "\"")
                    : head.replaceFirst("<Period", "<Period start=\"" + iso(t) + "\"");
            out.append(newHead).append(p, headEnd + 1, p.length());
            t += dur;
            kept++;
        }
        if (dropped == 0 || kept == 0 || keptClips == 0) return null;
        out.append(mpd, spans.get(spans.size() - 1)[1], mpd.length());

        String result = out.toString().replaceFirst("mediaPresentationDuration=\"PT[^\"]*\"",
                "mediaPresentationDuration=\"" + iso(t) + "\"");
        Log.i(TAG, "removed " + dropped + " ad periods, " + kept + " content periods left, " + Math.round(t) + " s");
        return result;
    }

    // Content (the show, Pluto's own warning cards) comes from a partner's /clip/ folder, e.g.
    // /704_Comedy_Central_Nordics/clip/... or /190_pluto/clip/...; ads come from /v1/mp4/... (creatives) and
    // /v1/jit/... (bumpers). On the TV some ads were not marked _ad/creative at all (one played ~100 s), so an
    // unmarked period whose BaseURL is on one of those ad paths is an ad too. A period whose BaseURL is in
    // neither layout is KEPT (unknown layout -> fail open) rather than guessed to be an ad.
    private static final Pattern BASE_URL = Pattern.compile("<BaseURL[^>]*>([^<]*)</BaseURL>");
    private static final String[] AD_PATHS = { "/v1/mp4/", "/v1/jit/" };

    static boolean isAd(String period) {
        for (String m : AD_MARKERS) if (period.contains(m)) return true;
        Matcher m = BASE_URL.matcher(period);
        String adUrl = null;
        while (m.find()) {
            String u = m.group(1);
            if (u.contains("/clip/")) return false;
            for (String a : AD_PATHS) if (u.contains(a) && adUrl == null) adUrl = u;
        }
        if (adUrl != null) {
            Log.i(TAG, "dropping unmarked ad period from " + adUrl.substring(0, Math.min(90, adUrl.length())));
            return true;
        }
        return false;   // no BaseURL, or an unrecognized layout: keep it (fail-open)
    }

    private static boolean hasClipBaseUrl(String period) {
        Matcher m = BASE_URL.matcher(period);
        while (m.find()) if (m.group(1).contains("/clip/")) return true;
        return false;
    }

    private static double seconds(String iso) {
        Matcher m = ISO.matcher(iso);
        if (!m.matches()) return -1;
        double s = 0;
        if (m.group(1) != null) s += Double.parseDouble(m.group(1)) * 3600;
        if (m.group(2) != null) s += Double.parseDouble(m.group(2)) * 60;
        if (m.group(3) != null) s += Double.parseDouble(m.group(3));
        return s;
    }

    private static String iso(double s) {
        return String.format(Locale.ROOT, "PT%.3fS", s);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 18);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
