package ajstrick81.morphe.extension.twitchatv.ads;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Twitch Android TV (Starshot / laserarray WebView) — HLS ad-segment scrubber.
 *
 * The TV player is hls.js in a WebView; live ads are server-stitched (SSAI) into
 * the "video-weaver" media playlist served from {@code *.playlist.ttvnw.net}. That
 * fetch does NOT go through the service worker ({@code fromServiceWorker=false},
 * verified on-device), so a native {@link WebViewClient#shouldInterceptRequest}
 * override sees it regardless of any JS fetch/XHR timing — the timing-independent
 * seam the vaft JS injection could not reliably win.
 *
 * On-device capture of a real ad break showed the exact structure:
 * <pre>
 *   #EXT-X-DATERANGE:...CLASS="twitch-stitched-ad"...ROLL-TYPE="PREROLL"
 *   #EXT-X-DISCONTINUITY
 *   #EXTINF:2.002,DCM|259975342        &lt;- ad segment (title = "DCM|&lt;creative&gt;")
 *   https://&lt;hash&gt;.j.cloudfront.hls.ttvnw.net/v1/segment/...ts
 *   ...
 * </pre>
 * Content segments are {@code #EXTINF:2.000,live}. So the discriminator is the
 * EXTINF title: {@code live} = content (keep), anything else = ad (drop). We drop
 * ad segments plus their per-segment tags and the ad-only dateranges
 * (twitch-stitched-ad / twitch-ad-quartile / twitch-trigger / non-live
 * twitch-stream-source), while preserving the playlist headers. During a pre/mid
 * roll the whole current window is ad, so the scrubbed playlist has no segments;
 * hls.js keeps polling this live playlist and resumes when real content segments
 * return — i.e. the ad video is never played (at worst a brief wait instead).
 *
 * The playlist token URL needs no auth (a plain GET works — verified), so we
 * re-fetch it ourselves, scrub, and return it. Fails open: any error returns the
 * original client's response so playback is never broken by this hook.
 */
public final class TwitchAtvWebViewHelper {

    private static final String TAG = "MORPHE-TW-ATV-WV";

    /** Only the video-weaver media playlists carry stitched ads. */
    private static boolean isWeaverPlaylist(String url) {
        return url != null && url.contains(".playlist.ttvnw.net/") && url.contains("/playlist/");
    }

    public static WebViewClient wrapClient(final WebViewClient original) {
        Log.d(TAG, "wrapClient — Twitch ATV HLS scrubber active");
        return new WrappedClient(original);
    }

    private static final class WrappedClient extends WebViewClient {
        private final WebViewClient original;

        WrappedClient(WebViewClient original) {
            this.original = original;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                if (isWeaverPlaylist(url)) {
                    WebResourceResponse scrubbed = scrubPlaylist(url);
                    if (scrubbed != null) return scrubbed;
                }
            } catch (Throwable t) {
                Log.e(TAG, "shouldInterceptRequest error", t);
            }
            return original.shouldInterceptRequest(view, request);
        }

        // --- delegate everything else to the app's real client ---------------
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            original.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            original.onPageFinished(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return original.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            original.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            original.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                       android.net.http.SslError error) {
            original.onReceivedSslError(view, handler, error);
        }
    }

    private static WebResourceResponse scrubPlaylist(String url) throws Exception {
        String body = httpGet(url);
        if (body == null || !body.contains("twitch-stitched-ad")) {
            // No ad this poll — return content unchanged (still serve it so the
            // WebView doesn't double-fetch), but only if we actually got it.
            if (body == null) return null;
            return m3u8Response(body);
        }
        String scrubbed = stripAdSegments(body);
        Log.i(TAG, "scrubbed stitched-ad from weaver playlist");
        return m3u8Response(scrubbed);
    }

    /**
     * Removes ad segments (EXTINF title != "live") plus their per-segment tags,
     * and the ad-only dateranges, while preserving the playlist headers.
     */
    static String stripAdSegments(String playlist) {
        String[] lines = playlist.split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length());
        List<String> pending = new ArrayList<>();
        boolean seenSegment = false;

        for (String line : lines) {
            String t = line.trim();
            boolean isSegmentTag = t.startsWith("#EXTINF")
                    || t.startsWith("#EXT-X-DISCONTINUITY")
                    || t.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                    || t.startsWith("#EXT-X-BYTERANGE")
                    || t.startsWith("#EXT-X-KEY")
                    || (t.startsWith("#EXT-X-DATERANGE") && isAdDaterange(t));

            if (!seenSegment && !t.startsWith("#EXTINF")) {
                // Header region: keep headers, drop ad-only dateranges, buffer the
                // per-segment tags that belong to the upcoming first segment.
                if (t.startsWith("#EXT-X-DATERANGE") && isAdDaterange(t)) continue;
                if (t.startsWith("#EXT-X-DISCONTINUITY") || t.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                    pending.add(line);
                } else {
                    out.append(line).append('\n');
                }
                continue;
            }
            seenSegment = true;

            if (t.isEmpty() || t.startsWith("#")) {
                // Per-segment tag: drop ad-only dateranges outright, else buffer.
                if (t.startsWith("#EXT-X-DATERANGE") && isAdDaterange(t)) continue;
                pending.add(line);
                continue;
            }

            // A URI line — decide the whole pending block by its EXTINF title.
            if (pendingIsAd(pending)) {
                pending.clear(); // drop ad segment + its tags
            } else {
                for (String p : pending) out.append(p).append('\n');
                out.append(line).append('\n');
                pending.clear();
            }
        }
        for (String p : pending) out.append(p).append('\n');
        return out.toString();
    }

    private static boolean isAdDaterange(String line) {
        if (line.contains("CLASS=\"twitch-stitched-ad\"")) return true;
        if (line.contains("CLASS=\"twitch-ad-quartile\"")) return true;
        if (line.contains("CLASS=\"twitch-trigger\"")) return true;
        // twitch-stream-source is content when it points at "live"
        if (line.contains("CLASS=\"twitch-stream-source\"")) {
            return !line.contains("STREAM-SOURCE=\"live\"");
        }
        return false;
    }

    private static boolean pendingIsAd(List<String> pending) {
        for (String p : pending) {
            String t = p.trim();
            if (t.startsWith("#EXTINF")) {
                int comma = t.indexOf(',');
                String title = comma >= 0 ? t.substring(comma + 1).trim() : "";
                return !title.equals("live");
            }
        }
        return false; // no EXTINF in the block (e.g. trailing tags) — keep
    }

    private static WebResourceResponse m3u8Response(String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "*");
        headers.put("Cache-Control", "no-cache");
        InputStream data = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new WebResourceResponse(
                "application/vnd.apple.mpegurl", "utf-8", 200, "OK", headers, data);
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "httpGet failed: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
