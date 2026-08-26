package ajstrick81.morphe.extension.hbomax.ads;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * HBO Max Android TV — SSAI manifest launderer (removes stitched ad periods).
 *
 * <p>This is the resume-safe alternative to {@link HboAdOriginFilter}. Instead of
 * failing the ad-segment fetch <em>downstream</em> (which only recovers when it
 * happens at video start, and throws a fatal "Couldn't Play Content" 39999 when a
 * <em>resumed</em> session hits a mid-roll — see the resume-39999 investigation),
 * this rewrites the DASH manifest <em>before</em> it is parsed so the ad periods
 * never exist. With no ad {@code <Period>}s, the player never requests a
 * {@code -free} ad segment at all → no ads, and nothing to fatal on, on fresh
 * start or resume alike.
 *
 * <p>Wired at the entry of media3's {@code DashManifestParser.parse(Uri, InputStream)}
 * (R8-renamed {@code oq1} on 7.9.0.61): the patch swaps the {@code InputStream}
 * argument for the return of {@link #launderManifest(InputStream)} so the parser
 * consumes the cleaned document.
 *
 * <p>Ad-period discriminator (confirmed on 7.9.0.61): ad periods carry their own
 * per-period {@code <BaseURL>} pointing at the {@code -free.prd.media.max.com}
 * origin, while content periods inherit the clean MPD-level BaseURL. So a
 * {@code <Period>…</Period>} block that contains {@link #AD_BASEURL_NEEDLE} is an
 * ad period and is dropped.
 *
 * <p><b>Known limitation / next step:</b> straight removal leaves the surviving
 * content periods at their original {@code start} offsets, i.e. a temporal gap
 * where each ad used to be. media3 multi-period VOD may tolerate this or may need
 * the gaps closed by re-baselining subsequent {@code start=} attributes (the
 * podwash technique). That is gated on tomorrow's on-device test — see
 * {@link #TODO_REBASELINE}.
 *
 * <p>Fail-open: any error returns the ORIGINAL bytes so playback is never worse
 * than an unpatched parse.
 */
@SuppressWarnings("unused")
public final class HboManifestFilter {

    private static final String TAG = "HboManifestFilter";

    // Substring identifying an ad period's -free segment origin (same needle the
    // origin filter blocks). A <Period> containing it is a stitched ad period.
    private static final String AD_BASEURL_NEEDLE = "-free.prd.media.max.com";

    // If straight period removal leaves playback gaps on-device, re-baseline the
    // surviving <Period start="PT..S"> offsets to be contiguous here (podwash).
    private static final boolean TODO_REBASELINE = false;

    private HboManifestFilter() {
    }

    /**
     * Read the manifest stream, drop stitched ad periods, and return a fresh
     * stream of the cleaned document. On any problem, returns the original bytes.
     *
     * @param in the DASH manifest InputStream media3 is about to parse.
     * @return an InputStream for the parser to consume (laundered, or original).
     */
    public static InputStream launderManifest(InputStream in) {
        if (in == null) {
            return null;
        }
        byte[] original;
        try {
            original = readAll(in);
        } catch (Throwable t) {
            // Could not buffer the stream — hand back the original untouched.
            Log.w(TAG, "read failed; passing manifest through", t);
            return in;
        }
        try {
            String mpd = new String(original, StandardCharsets.UTF_8);
            // Only act on documents that actually carry the ad origin; leave the
            // clean fallback manifest and any non-DASH payload untouched.
            if (!mpd.contains(AD_BASEURL_NEEDLE) || !mpd.contains("<Period")) {
                return new ByteArrayInputStream(original);
            }
            StringBuilder out = new StringBuilder(mpd.length());
            int stripped = 0;
            int cursor = 0;
            while (true) {
                int periodStart = mpd.indexOf("<Period", cursor);
                if (periodStart < 0) {
                    out.append(mpd, cursor, mpd.length());
                    break;
                }
                // Locate the end of this period element.
                int tagClose = mpd.indexOf('>', periodStart);
                if (tagClose < 0) {
                    out.append(mpd, cursor, mpd.length());
                    break;
                }
                int periodEnd;
                boolean selfClosing = mpd.charAt(tagClose - 1) == '/';
                if (selfClosing) {
                    periodEnd = tagClose + 1;
                } else {
                    int closeTag = mpd.indexOf("</Period>", tagClose);
                    periodEnd = (closeTag < 0) ? mpd.length() : closeTag + "</Period>".length();
                }
                String period = mpd.substring(periodStart, periodEnd);
                if (period.contains(AD_BASEURL_NEEDLE)) {
                    // Drop this ad period — append everything before it, skip it.
                    out.append(mpd, cursor, periodStart);
                    stripped++;
                } else {
                    out.append(mpd, cursor, periodEnd);
                }
                cursor = periodEnd;
            }
            if (stripped == 0) {
                return new ByteArrayInputStream(original);
            }
            Log.i(TAG, "stripped " + stripped + " stitched ad period(s) from manifest");
            byte[] cleaned = out.toString().getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(cleaned);
        } catch (Throwable t) {
            // Anything unexpected: fail open with the original document.
            Log.w(TAG, "launder failed; passing original manifest", t);
            return new ByteArrayInputStream(original);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        try {
            in.close();
        } catch (Throwable ignored) {
            // best-effort close of the consumed network stream
        }
        return buf.toByteArray();
    }
}
