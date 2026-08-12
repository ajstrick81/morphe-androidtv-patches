package ajstrick81.morphe.extension.twitchatv.ads;

import android.util.Base64;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * EXTINF title: {@code live} = content, anything else = ad.
 *
 * BLANKING, not stripping. An earlier version removed ad segments outright, but a
 * pre/mid-roll's whole window is ad, so that left an EMPTY live playlist — hls.js
 * never advances the ad timeline, the ad never completes server-side, and Twitch
 * serves it endlessly (a stuck-ad loop, not just a gap). Instead we keep the
 * playlist intact (segments AND the quartile/trigger dateranges, so the ad's
 * beacons fire and it completes) and only rewrite each ad segment's URI to a
 * sentinel we serve a ~2s black+silent TS for ({@link BlankSegment}). The
 * timeline advances (black plays for the ad duration), the ad completes, and
 * content resumes — the ad video is never shown.
 *
 * The playlist token URL needs no auth (a plain GET works — verified), so we
 * re-fetch it ourselves, rewrite, and return it. Fails open: any error returns
 * the original client's response so playback is never broken by this hook.
 */
public final class TwitchAtvWebViewHelper {

    private static final String TAG = "MORPHE-TW-ATV-WV";

    /** Sentinel host that ad segment URIs are rewritten to; we serve black TS for it. */
    private static final String BLANK_HOST = "morphe.invalid";
    private static final String BLANK_SENTINEL = "https://" + BLANK_HOST + "/b.ts?i=";

    private static volatile byte[] blankTs;

    /** 90 kHz ticks per second — MPEG-TS timestamp unit. */
    private static final long TS_HZ = 90000L;

    /** Cache of PTS-shifted blank copies, keyed by offset ticks (bounded). */
    private static final Map<Long, byte[]> shiftCache = new ConcurrentHashMap<>();

    private static byte[] blankTsBytes() {
        byte[] b = blankTs;
        if (b == null) {
            b = Base64.decode(BlankSegment.TS_BASE64, Base64.DEFAULT);
            blankTs = b;
        }
        return b;
    }

    /** Blank TS with all timestamps shifted forward by {@code ticks} (cached). */
    private static byte[] shiftedBlank(long ticks) {
        if (ticks == 0) return blankTsBytes();
        byte[] c = shiftCache.get(ticks);
        if (c != null) return c;
        byte[] s = BlankSegment.shift(blankTsBytes(), ticks);
        if (shiftCache.size() < 512) shiftCache.put(ticks, s);
        return s;
    }

    /** Parses the {@code o=<ticks>} PTS offset from a blank sentinel URL. */
    private static long parseOffset(String url) {
        int k = url.indexOf("o=");
        if (k < 0) return 0;
        long v = 0;
        for (int e = k + 2; e < url.length(); e++) {
            char ch = url.charAt(e);
            if (ch < '0' || ch > '9') break;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    /** Only the video-weaver media playlists carry stitched ads. */
    private static boolean isWeaverPlaylist(String url) {
        return url != null && url.contains(".playlist.ttvnw.net/") && url.contains("/playlist/");
    }

    public static WebViewClient wrapClient(final WebViewClient original) {
        Log.d(TAG, "wrapClient — Twitch ATV HLS scrubber active");
        return new WrappedClient(original);
    }

    /**
     * Injects a small, idempotent stall-recovery monitor into the player page.
     * Substituting black segments for ads can leave hls.js briefly buffering; if
     * it stalls long enough it fatal-errors ("Oh bummer") instead of resuming.
     * This watches the &lt;video&gt; and, only when it is genuinely stuck (time not
     * advancing while not paused), nudges it (seek toward the buffered edge +
     * play) so it recovers before hitting the fatal buffer-stall — the same idea
     * as TwitchAdSolutions' PlayerBufferingFix, kept minimal and player-agnostic.
     * No-op during normal playback (time advances → never nudges).
     */
    private static void injectPlaybackFix(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(PLAYBACK_FIX_JS, null);
        } catch (Throwable t) {
            Log.e(TAG, "playback-fix injection failed", t);
        }
    }

    private static final String PLAYBACK_FIX_JS =
        "(function(){try{"
        + "if(window.__morphePlaybackFix)return;window.__morphePlaybackFix=true;"
        + "var last=-1,same=0;"
        + "setInterval(function(){try{"
        + "var v=document.querySelector('video');"
        + "if(!v||v.paused||v.ended||v.seeking){same=0;return;}"
        + "var t=v.currentTime;"
        + "if(Math.abs(t-last)<0.02){"
        + "  same++;"
        + "  if(same>=2){"
        + "    try{var b=v.buffered;if(b&&b.length){var e=b.end(b.length-1);"
        + "      if(e>t+0.1){v.currentTime=Math.min(e-0.05,t+0.5);}else{v.currentTime=t+0.05;}"
        + "    }else{v.currentTime=t+0.05;}}catch(e){}"
        + "    try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}"
        + "    same=0;"
        + "  }"
        + "}else{same=0;}"
        + "last=t;"
        + "}catch(e){}},700);"
        + "}catch(e){}})();";

    private static final class WrappedClient extends WebViewClient {
        private final WebViewClient original;

        WrappedClient(WebViewClient original) {
            this.original = original;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                if (url.contains(BLANK_HOST)) {
                    return blankTsResponse(url);
                }
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
            injectPlaybackFix(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            original.onPageFinished(view, url);
            injectPlaybackFix(view);
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
        if (body == null) return null; // couldn't fetch — let the WebView load it
        if (!body.contains("twitch-stitched-ad")) {
            // No ad this poll — serve the content unchanged (single fetch).
            return m3u8Response(body);
        }
        String rewritten = blankAdSegments(body);
        Log.i(TAG, "blanked stitched-ad segments in weaver playlist");
        return m3u8Response(rewritten);
    }

    /**
     * Keeps the playlist intact (headers, dateranges, per-segment tags) but
     * rewrites each ad segment's URI (the URI following an #EXTINF whose title is
     * not "live") to a per-segment sentinel we serve a black TS for. This keeps
     * the timeline advancing so the ad completes server-side and content resumes.
     */
    static String blankAdSegments(String playlist) {
        String[] lines = playlist.split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length());
        boolean prevExtinfIsAd = false;
        double curDur = 0;      // duration (s) of the pending #EXTINF
        long adTicks = 0;       // cumulative PTS offset (90 kHz) since the ad run began

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-DISCONTINUITY")) {
                // Break boundary — Twitch stitches the pod as one continuous
                // timeline after this tag, so restart the blank timeline here.
                adTicks = 0;
                out.append(line).append('\n');
            } else if (t.startsWith("#EXTINF")) {
                int colon = t.indexOf(':');
                int comma = t.indexOf(',');
                String title = comma >= 0 ? t.substring(comma + 1).trim() : "";
                prevExtinfIsAd = !title.equals("live");
                curDur = 0;
                if (colon >= 0 && comma > colon) {
                    try {
                        curDur = Double.parseDouble(t.substring(colon + 1, comma).trim());
                    } catch (NumberFormatException ignored) { }
                }
                out.append(line).append('\n');
            } else if (!t.isEmpty() && !t.startsWith("#")) {
                // URI line.
                if (prevExtinfIsAd) {
                    // Per-segment sentinel: hash keeps distinct segments distinct;
                    // o=<ticks> is the segment's start on the pod timeline, so each
                    // served blank is PTS-shifted to continue from the previous one
                    // (monotonic → hls.js plays the pod through without a resume stall).
                    out.append(BLANK_SENTINEL)
                       .append(Integer.toHexString(t.hashCode()))
                       .append("&o=").append(adTicks)
                       .append('\n');
                    adTicks += Math.round(curDur * TS_HZ);
                } else {
                    out.append(line).append('\n');
                    adTicks = 0; // content segment ends the ad run
                }
                prevExtinfIsAd = false;
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static WebResourceResponse blankTsResponse(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "*");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(
                "video/mp2t", null, 200, "OK", headers,
                new ByteArrayInputStream(shiftedBlank(parseOffset(url))));
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
