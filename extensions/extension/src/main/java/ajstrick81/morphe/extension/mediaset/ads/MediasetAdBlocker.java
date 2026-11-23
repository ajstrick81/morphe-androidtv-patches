package ajstrick81.morphe.extension.mediaset.ads;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Mediaset Infinity Android TV — ad-call blocker for the app's WebView.
 *
 * The app is a WebView shell around Mediaset's remote React TV app. That web app
 * decides ads per play request and calls FreeWheel (*.fwmrm.net) or Google IMA /
 * Ad Manager (*.doubleclick.net, optionally via SpringServe). Called from the top
 * of the shell's WebViewClient.shouldInterceptRequest: returns an empty response
 * for those ad hosts, or null to let the app handle the request as normal.
 *
 * Why an empty 204 with CORS headers: an empty VMAP/VAST is the ad SDKs' own
 * "no ads for this slot" answer, so the web app moves straight on to the content
 * instead of waiting out its 3 s ad-call timeout. The CORS header keeps the fetch
 * from failing as a network error the app might report or retry.
 *
 * Deliberately NOT blocked: imasdk.googleapis.com (the IMA script itself — the web
 * app expects google.ima to exist) and every Mediaset host (content, config, login).
 */
@SuppressWarnings("unused")
public final class MediasetAdBlocker {
    private static final String TAG = "MediasetAds";

    /** Host suffixes of the ad decision / ad creative servers the web app calls. */
    private static final String[] AD_HOST_SUFFIXES = {
        "fwmrm.net",          // FreeWheel ad decisions (freewheel.serverUrl = 7cf6f.v.fwmrm.net)
        "doubleclick.net",    // IMA VMAP tags (pubads) + pause/welcome display ads (gampad/adx)
        "springserve.com",    // SpringServe wrapper around the IMA tag
        "2mdn.net",           // Google ad creatives
    };

    private MediasetAdBlocker() {
    }

    /**
     * @return an empty response for an ad host, otherwise null (app handles it).
     *         Never throws: it runs on the WebView's network thread.
     */
    public static WebResourceResponse intercept(WebResourceRequest request) {
        try {
            if (request == null) return null;
            Uri url = request.getUrl();
            String host = url == null ? null : url.getHost();
            if (host == null || !isAdHost(host)) return null;

            Log.i(TAG, "blocked ad call: " + host + url.getPath());
            return emptyResponse(request);
        } catch (Throwable t) {
            Log.e(TAG, "intercept failed; letting the request through", t);
            return null;
        }
    }

    static boolean isAdHost(String host) {
        String h = host.toLowerCase();
        for (String suffix : AD_HOST_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static WebResourceResponse emptyResponse(WebResourceRequest request) {
        Map<String, String> headers = new HashMap<>();
        String origin = request.getRequestHeaders() == null
                ? null : request.getRequestHeaders().get("Origin");
        headers.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
        headers.put("Access-Control-Allow-Credentials", "true");
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                headers, new ByteArrayInputStream(new byte[0]));
    }
}
