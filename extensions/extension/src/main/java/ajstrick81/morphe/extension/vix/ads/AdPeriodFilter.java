package ajstrick81.morphe.extension.vix.ads;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ViX — "Skip ads": drops server-stitched ad periods from the VOD DASH manifest.
 *
 * ViX on-demand streams come from the Lura platform already stitched: the
 * manifest (dcs4-vod.mp.lura.live/vod/p/manifest.mpd) is multi-period, with
 * ad periods placed before and between the content periods. No client ad request is involved, so emptying the SDK's ad
 * schedule only hides the markers; the ad periods still play.
 *
 * filter(InputStream) is injected at the top of media3's
 * DashManifestParser.parse(Uri, InputStream). It rewrites the manifest text
 * before it is parsed:
 *
 *   - only static (on-demand) manifests are touched;
 *   - a period is an ad period when it carries a Lura ad event stream
 *     (EventStream schemeIdUri="urn:lura:..." with ctx=vast events). Content
 *     periods never do, whether the title is Widevine-protected (movies) or
 *     served in the clear (many series). If no period has the marker, clear
 *     periods among DRM-protected ones are treated as ads instead;
 *   - ad periods are removed and the remaining periods' start times and the
 *     presentation duration are shifted so the timeline stays contiguous.
 *
 * Everything runs on the device on text the player already downloaded; nothing
 * is requested or sent. On any doubt or error the original manifest is returned
 * unchanged, so the worst case is the stock behaviour.
 */
public final class AdPeriodFilter {
    private static final String TAG = "VixAdFilter";

    private static final Pattern PERIOD =
            Pattern.compile("<Period\\b([^>]*)>[\\s\\S]*?</Period\\s*>");
    // Lura tags every stitched ad period with its own event stream carrying the
    // ad's VAST tracking context; content periods never have one.
    private static final Pattern LURA_AD_EVENTS =
            Pattern.compile("<EventStream\\b[^>]*schemeIdUri=\"urn:lura:");
    private static final Pattern MPD_OPEN = Pattern.compile("<MPD\\b[^>]*>");
    private static final Pattern ISO_DURATION = Pattern.compile(
            "P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");

    private AdPeriodFilter() {}

    /** Injection entry point. Never throws; returns a stream the parser can read. */
    public static InputStream filter(InputStream in) {
        if (in == null) return null;
        byte[] original;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            original = buf.toByteArray();
        } catch (Throwable t) {
            // Partially consumed: nothing sensible to hand back but the stream itself.
            return in;
        }
        try {
            String xml = new String(original, StandardCharsets.UTF_8);
            StringBuilder note = new StringBuilder();
            String out = filterManifest(xml, note);
            if (note.length() > 0) Log.i(TAG, note.toString());
            if (out != null) return new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "manifest left unchanged: " + t);
        }
        return new ByteArrayInputStream(original);
    }

    /**
     * Pure text transform (host-testable). Returns the rewritten manifest, or
     * null when the manifest should be used as-is.
     */
    static String filterManifest(String xml, StringBuilder note) {
        Matcher mpd = MPD_OPEN.matcher(xml);
        if (!mpd.find()) return null;
        String mpdTag = mpd.group();
        if ("dynamic".equals(attr(mpdTag, "type"))) return null; // live: not ours

        List<int[]> spans = new ArrayList<>();
        List<String> openTags = new ArrayList<>();
        List<Boolean> isAd = new ArrayList<>();
        List<Boolean> isProtected = new ArrayList<>();
        Matcher m = PERIOD.matcher(xml);
        int protectedCount = 0;
        int markedCount = 0;
        while (m.find()) {
            String period = m.group();
            spans.add(new int[] {m.start(), m.end()});
            openTags.add("<Period" + m.group(1) + ">");
            boolean prot = period.contains("ContentProtection");
            boolean marked = LURA_AD_EVENTS.matcher(period).find() && period.contains("ctx=vast");
            if (prot) protectedCount++;
            if (marked) markedCount++;
            isProtected.add(prot);
            isAd.add(marked);
        }
        int count = spans.size();
        if (markedCount == 0 && protectedCount > 0) {
            // Fallback for a manifest without the ad event marker: clear periods
            // sitting among DRM-protected ones.
            for (int i = 0; i < count; i++) isAd.set(i, !isProtected.get(i));
            markedCount = count - protectedCount;
        }
        int adCount = markedCount;
        if (count < 2 || adCount == 0 || adCount == count) {
            note.append("manifest left unchanged: ").append(count).append(" periods, ")
                    .append(adCount).append(" ad, ").append(protectedCount).append(" protected");
            return null;
        }

        // Resolve each period's start/duration the way the DASH spec does.
        double total = seconds(attr(mpdTag, "mediaPresentationDuration"));
        double[] start = new double[count];
        double[] dur = new double[count];
        double next = 0;
        for (int i = 0; i < count; i++) {
            double s = seconds(attr(openTags.get(i), "start"));
            if (Double.isNaN(s)) s = next;
            if (Double.isNaN(s)) return bail(note, "period " + i + " has no resolvable start");
            start[i] = s;
            dur[i] = seconds(attr(openTags.get(i), "duration"));
            next = Double.isNaN(dur[i]) ? Double.NaN : s + dur[i];
        }
        for (int i = 0; i < count; i++) {
            if (!Double.isNaN(dur[i])) continue;
            double end = i + 1 < count ? start[i + 1] : total;
            if (Double.isNaN(end)) {
                // Only an ad period needs a known length (to shift what follows it).
                if (isAd.get(i) && i + 1 < count) return bail(note, "ad period " + i + " has no resolvable duration");
                continue;
            }
            dur[i] = end - start[i];
        }

        StringBuilder out = new StringBuilder(xml.length());
        int cursor = 0;
        double removed = 0;
        for (int i = 0; i < count; i++) {
            int[] span = spans.get(i);
            out.append(xml, cursor, span[0]);
            cursor = span[1];
            if (isAd.get(i)) {
                if (!Double.isNaN(dur[i])) removed += dur[i];
                continue;
            }
            String body = xml.substring(span[0], span[1]);
            String open = openTags.get(i);
            String shifted = setAttr(open, "start", iso(start[i] - removed));
            out.append(shifted).append(body, open.length(), body.length());
        }
        out.append(xml, cursor, xml.length());

        String result = out.toString();
        if (!Double.isNaN(total) && removed > 0) {
            String newMpdTag = setAttr(mpdTag, "mediaPresentationDuration", iso(Math.max(0, total - removed)));
            result = result.replace(mpdTag, newMpdTag);
        }
        note.append("removed ").append(adCount).append(" of ").append(count)
                .append(" periods (").append(String.format(Locale.US, "%.1f", removed)).append(" s of ads)");
        return result;
    }

    private static String bail(StringBuilder note, String why) {
        note.append("manifest left unchanged: ").append(why);
        return null;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String setAttr(String tag, String name, String value) {
        Matcher m = Pattern.compile("(\\s" + name + "\\s*=\\s*\")[^\"]*(\")").matcher(tag);
        if (m.find()) return tag.substring(0, m.start()) + m.group(1) + value + m.group(2) + tag.substring(m.end());
        int end = tag.endsWith("/>") ? tag.length() - 2 : tag.length() - 1;
        return tag.substring(0, end) + " " + name + "=\"" + value + "\"" + tag.substring(end);
    }

    /** ISO-8601 duration to seconds; NaN when absent or unparseable. */
    private static double seconds(String iso) {
        if (iso == null) return Double.NaN;
        Matcher m = ISO_DURATION.matcher(iso.trim());
        if (!m.matches() || iso.trim().length() < 2) return Double.NaN;
        double s = 0;
        if (m.group(1) != null) s += Double.parseDouble(m.group(1)) * 86400;
        if (m.group(2) != null) s += Double.parseDouble(m.group(2)) * 3600;
        if (m.group(3) != null) s += Double.parseDouble(m.group(3)) * 60;
        if (m.group(4) != null) s += Double.parseDouble(m.group(4));
        return s;
    }

    private static String iso(double seconds) {
        return "PT" + String.format(Locale.US, "%.3f", Math.max(0, seconds)) + "S";
    }
}
