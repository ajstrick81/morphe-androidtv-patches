package ajstrick81.morphe.extension.pluto.ads;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pluto TV v17.x (Paramount+/AVIA rebuild) — VOD ad REMOVAL by DASH period surgery
 * at the XML level.
 *
 * <p>v17 replaced the old leanback stack with the Paramount AVIA player; media3 and
 * okhttp are R8-minified, so the leanback object-level {@code DashManifestParser.parse}
 * hook (which references androidx.media3 DashManifest types by name) can no longer be
 * attached, and the okhttp interceptor seam is renamed. Instead, Hook 3 injects a call
 * at the entry of AVIA's own un-renamed
 * {@code AviaDashParser.parse(Uri, InputStream) -> bz3} and swaps the InputStream for a
 * cleaned one via {@link #cleanManifestStream}. We strip the ad {@code <Period>}s from
 * the raw MPD XML, re-base the remaining content periods into a contiguous timeline, and
 * hand the renamed parser the cleaned manifest. Only platform/XML types are touched, so
 * no renamed library type is referenced.
 *
 * <p>Ad discrimination is identical to the leanback model (see {@code PlutoDashManifestProbe}):
 * a period is an AD iff one of its {@code <BaseURL>}s contains {@code _ad/creative}
 * (URL-encoded {@code _ad%2Fcreative}) or {@code _ad_bumper}. Everything else — content,
 * DRM'd or not — is kept, so we never drop content we don't recognize.
 *
 * <p><b>Timeline re-basing (frozen-seekbar fix).</b> Removing an ad {@code <Period>} leaves
 * a hole in the presentation timeline: the content periods after it keep their original
 * (ad-inclusive) {@code start} values, so the player's seekbar clamps at the first removed
 * ad while the media keeps flowing (symptom: scrubber frozen at ~the first ad position,
 * playback continuing). The fix: ad periods are removed WITHOUT advancing the content
 * cursor, and every kept content period's {@code start} is rewritten to run immediately
 * after the previous kept period, with the root {@code mediaPresentationDuration} reduced
 * to the kept total. Segment addressing inside each period is period-relative, so re-basing
 * {@code start} keeps segments addressable.
 *
 * <p>Fail-open on every edge (not a DASH MPD, no ad markers, dynamic/live, nothing kept,
 * implausible over-strip, or any exception) → the original manifest passes through
 * untouched, so playback can never be broken by this hook.
 */
@SuppressWarnings("unused")
public final class PlutoV17ManifestProbe {

    private static final String TAG = "MORPHE-DASH-MF";
    private static final String DASH_IDENTIFIER = "<MPD";

    /** ISO-8601 media duration: {@code PT[nH][nM][nS]}. */
    private static final Pattern PT =
            Pattern.compile("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?");

    // Fail-safe guard (see leanback #144 backstop): real ad load is ~18-30% of runtime, so
    // content stays >=~70%. If we'd keep less than this fraction of the original duration,
    // an unrecognized content shape has probably been mis-dropped -> pass the original through.
    private static final double MIN_KEPT_FRACTION = 0.4;

    // Period-classification diagnostic (issue #284). When true, stripAdPeriodsXml logs one
    // compact line per <Period> — index, AD/CONTENT verdict, duration, and a trimmed first
    // <BaseURL> — under the TAG below, and still runs on manifests with NO recognized ad
    // token (so an ad pod carrying an UNRECOGNIZED BaseURL — the Burn Notice mid-roll leak —
    // is logged as CONTENT with its real URL, revealing the missed discriminator). It never
    // changes what is stripped: a period is removed iff isAdPeriod() matches, exactly as
    // before. Runs only at manifest-parse time (once per title load), so it is not hot.
    // Flip to false (or filter out the TAG) to silence it.
    //   adb logcat -s MORPHE-DASH-MF
    static volatile boolean DIAG = true;

    /** First <BaseURL> text of a period, trimmed for logging (null-safe). */
    private static String firstBaseUrl(Element period) {
        NodeList b = period.getElementsByTagName("BaseURL");
        if (b.getLength() == 0) return "(none)";
        String u = b.item(0).getTextContent();
        if (u == null) return "(null)";
        u = u.trim();
        return u.length() > 180 ? u.substring(0, 180) + "..." : u;
    }

    /**
     * True when the current manifest is a STATIC (on-demand/VOD) MPD. Hook 4 reads this via
     * {@link #shouldSuppressDynamicAdFetch()} to suppress the ID3/CLID3-driven CSAI ad fetch
     * on VOD (where we strip ads) while leaving live/linear (dynamic) untouched. Set per
     * manifest in {@link #cleanManifestStream}.
     */
    public static volatile boolean suppressDynamicAdFetch;

    /** Hook 4 guard: suppress the CLID3 CSAI ad call when a static VOD manifest is in play. */
    public static boolean shouldSuppressDynamicAdFetch() {
        return suppressDynamicAdFetch;
    }

    private static String fmtPt(double seconds) {
        return "PT" + String.format(Locale.US, "%.3f", seconds) + "S";
    }

    private static double parsePt(String s) {
        if (s == null) return -1.0;
        Matcher m = PT.matcher(s.trim());
        if (!m.matches()) return -1.0;
        double d = 0.0;
        if (m.group(1) != null) d += Double.parseDouble(m.group(1)) * 3600.0;
        if (m.group(2) != null) d += Double.parseDouble(m.group(2)) * 60.0;
        if (m.group(3) != null) d += Double.parseDouble(m.group(3));
        return d;
    }

    private static boolean isAdUrl(String u) {
        if (u == null) return false;
        // SSAI ad/bumper creatives: /..._ad/creative... (URL-encoded _ad%2Fcreative) or _ad_bumper.
        if (u.contains("_ad%2Fcreative") || u.contains("_ad/creative") || u.contains("_ad_bumper")) {
            return true;
        }
        // Click-tracked ad clips (the SECOND VOD ad class on v17): short /mp4/v1/...video_NNN.mp4
        // periods tagged id3(p=clik,id=...). Device-confirmed to carry political/gambling ads that
        // the creative/bumper tokens above miss. Content periods use /clip/ (note: "clip", not
        // "clik") + /dash/ segment addressing, so this token never false-matches content.
        return u.contains("id3(p=clik");
    }

    /** A period is an AD iff any of its BaseURLs carries the ad-creative/bumper signature. */
    private static boolean isAdPeriod(Element period) {
        NodeList baseUrls = period.getElementsByTagName("BaseURL");
        for (int i = 0; i < baseUrls.getLength(); i++) {
            if (isAdUrl(baseUrls.item(i).getTextContent())) return true;
        }
        return false;
    }

    /**
     * Remove ID3 emsg {@code <InbandEventStream>} (scheme {@code aomedia.org/emsg/ID3},
     * value {@code 999}) from a kept content period, so no stale ad-beacon ID3 can retrigger
     * the CLID3 CSAI ad path on content. Returns the number removed.
     */
    private static int removeId3Streams(Element period) {
        NodeList list = period.getElementsByTagName("InbandEventStream");
        List<Element> toRemove = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if ("https://aomedia.org/emsg/ID3".equals(e.getAttribute("schemeIdUri"))
                        && "999".equals(e.getAttribute("value"))) {
                    toRemove.add(e);
                }
            }
        }
        for (Element e : toRemove) e.getParentNode().removeChild(e);
        return toRemove.size();
    }

    /**
     * Called from injected smali at the entry of {@code AviaDashParser.parse(Uri, InputStream)}
     * (Hook 3). Reads the manifest stream, strips ad periods, and returns a cleaned stream for
     * the renamed parser; fail-open returns the original bytes (or the original stream).
     */
    public static InputStream cleanManifestStream(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, in.available()));
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            byte[] raw = bos.toByteArray();
            try {
                String mpd = new String(raw, StandardCharsets.UTF_8);
                if (mpd.contains(DASH_IDENTIFIER)) {
                    // static (VOD) -> enable Hook-4 CSAI suppression; dynamic (live) -> leave it on.
                    suppressDynamicAdFetch = !mpd.contains("type=\"dynamic\"");
                }
                String cleaned = stripAdPeriodsXml(mpd);
                if (cleaned != null) {
                    return new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                Log.e(TAG, "v17 strip failed -> original manifest: " + t.getMessage());
            }
            // fail-open: hand back the original (buffered) bytes so the fetch is never consumed/lost.
            return new ByteArrayInputStream(raw);
        } catch (Throwable t) {
            Log.e(TAG, "v17 read failed -> original stream: " + t.getMessage());
            return in;
        }
    }

    /**
     * Strip ad periods from a DASH MPD and re-base the remaining content periods into a
     * contiguous timeline. Returns the cleaned XML, or {@code null} to pass the original
     * through unchanged (not a DASH MPD, no ad markers, dynamic/live, nothing removed,
     * nothing kept, over-strip guard, or any error).
     */
    static String stripAdPeriodsXml(String mpd) {
        try {
            if (mpd == null || mpd.isEmpty() || !mpd.contains(DASH_IDENTIFIER)) return null;
            boolean hasKnownAdToken = mpd.contains("_ad%2Fcreative") || mpd.contains("_ad/creative")
                    || mpd.contains("_ad_bumper") || mpd.contains("id3(p=clik");
            // Normally bail here when no recognized ad token is present. Under DIAG we instead
            // parse and log the period classifications anyway — that's the only way to surface an
            // ad pod whose BaseURL token we DON'T yet recognize (issue #284). Stripping is still a
            // no-op in that case (nothing matches isAdPeriod -> removed==0 -> passthrough below).
            if (!hasKnownAdToken && !DIAG) {
                return null;
            }

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            if (root == null) return null;

            // LIVE guard: dynamic MPDs are live/linear (moving edge, wall-clock timeline);
            // re-basing period starts there corrupts playback, and live ads are real broadcast
            // time that was never removable. Pass through untouched.
            if ("dynamic".equalsIgnoreCase(root.getAttribute("type"))) {
                Log.i(TAG, "v17 dynamic (live) MPD -> untouched");
                return null;
            }

            double totalDur = parsePt(root.getAttribute("mediaPresentationDuration"));

            // Snapshot the periods (we mutate the tree, so don't iterate the live NodeList).
            NodeList periodNodes = root.getElementsByTagName("Period");
            List<Element> periods = new ArrayList<>();
            for (int i = 0; i < periodNodes.getLength(); i++) {
                Node n = periodNodes.item(i);
                if (n instanceof Element) periods.add((Element) n);
            }
            int size = periods.size();
            if (size == 0) return null;

            // Original (ad-inclusive) start of each period, for duration-by-difference below.
            double[] starts = new double[size];
            for (int i = 0; i < size; i++) starts[i] = parsePt(periods.get(i).getAttribute("start"));

            // Duration of each period: explicit @duration, else next.start - this.start, else
            // mediaPresentationDuration - this.start (for the last). Computed from ORIGINAL
            // starts before any removal, so content durations stay correct across removed ads.
            double[] durs = new double[size];
            for (int i = 0; i < size; i++) {
                double d = parsePt(periods.get(i).getAttribute("duration"));
                if (d < 0.0) {
                    double next = (i + 1 < size) ? starts[i + 1] : totalDur;
                    if (next >= 0.0 && starts[i] >= 0.0) d = next - starts[i];
                }
                durs[i] = d > 0.0 ? d : 0.0;
            }

            double cursor = 0.0; // re-based content timeline
            int kept = 0, removed = 0, id3Removed = 0;
            for (int i = 0; i < size; i++) {
                Element p = periods.get(i);
                boolean ad = isAdPeriod(p);
                if (DIAG) {
                    Log.i(TAG, "v17 DIAG period[" + i + "] " + (ad ? "AD     " : "CONTENT")
                            + " dur=" + String.format(Locale.US, "%.1f", durs[i]) + "s id="
                            + p.getAttribute("id") + " base=" + firstBaseUrl(p));
                }
                if (ad) {
                    // AD: remove and DO NOT advance the content cursor. This is the freeze fix —
                    // ad time must never be counted into the kept-content timeline, otherwise the
                    // following content periods start too late and leave a seekbar-clamping gap.
                    p.getParentNode().removeChild(p);
                    removed++;
                    continue;
                }
                // CONTENT: re-base start to run immediately after the previous kept period.
                p.setAttribute("start", fmtPt(cursor));
                cursor += durs[i];
                kept++;
                id3Removed += removeId3Streams(p);
            }

            if (removed == 0) return null; // no ad periods actually matched -> passthrough
            if (kept == 0) {
                Log.w(TAG, "v17 strip: no content periods kept (of " + size + ") -> passthrough");
                return null;
            }
            if (totalDur > 0.0 && cursor < MIN_KEPT_FRACTION * totalDur) {
                Log.w(TAG, "v17 GUARD tripped: kept " + kept + "/" + size + " -> "
                        + cursor + "s of " + totalDur + "s (<40%) -> passthrough");
                return null;
            }

            if (totalDur > 0.0) root.setAttribute("mediaPresentationDuration", fmtPt(cursor));

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            Log.i(TAG, "v17 STRIPPED " + removed + " ad periods, kept " + kept
                    + ", removedId3=" + id3Removed + ", newDur=" + cursor + "s (was " + totalDur + "s)");
            return sw.toString();
        } catch (Throwable t) {
            Log.e(TAG, "v17 strip failed -> original manifest: " + t.getMessage());
            return null;
        }
    }
}
