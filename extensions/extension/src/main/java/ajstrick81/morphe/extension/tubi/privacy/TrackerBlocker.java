package ajstrick81.morphe.extension.tubi.privacy;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Tubi — "Block analytics &amp; tracking" (opt-in) runtime blocklist.
 *
 * One host list, enforced at the three network paths Tubi's analytics use
 * (mapped from the 10.36.5000 Android TV build; SDK class names are not
 * obfuscated, so the same paths exist in 10.28.5000):
 *
 *   openConnection(URL)      — every URL.openConnection() call site in the app
 *                              is rewritten to this. Adjust, Branch, Nielsen,
 *                              Mux, Conviva and Firebase measurement send
 *                              through HttpURLConnection.
 *   install(Builder)         — called at the top of
 *                              OkHttpClient.<init>(Builder), so every OkHttp
 *                              client (built or default-constructed) gets
 *                              {@link BlockInterceptor}. NPAW/Youbora and
 *                              parts of Braze use OkHttp.
 *   blockWebRequest(request) — WebViewClient.shouldInterceptRequest of the
 *                              ott-androidtv.tubitv.com SPA, which posts Tubi's
 *                              own analytics events (advertiser_id, device_id,
 *                              postal_code / lat-long fields) from JS.
 *
 * A blocked request fails the same way an offline network does (IOException,
 * or an empty 204 in the WebView). The SDKs are built to tolerate that; they
 * queue or drop the event and playback is unaffected.
 *
 * Deliberately NOT blocked: crash/performance reporting (Sentry, Crashlytics,
 * Firebase Performance), Statsig (feature flags the app depends on), OneTrust
 * (consent), Facebook's graph API (sign-in), ads/playback/DRM hosts.
 *
 * Everything is decided on-device; nothing is sent anywhere by this class.
 */
@SuppressWarnings("unused")
public final class TrackerBlocker {
    private static final String TAG = "MORPHE-TUBI-PRIVACY";

    /** Blocked if the host equals one of these or is a subdomain of one. */
    private static final String[] BLOCKED_DOMAINS = {
            // Attribution
            "adjust.com", "adjust.io", "adjust.world", "adjust.net.in",
            "branch.io",
            // Marketing / engagement
            "braze.com", "braze.eu", "appboy.com",
            // Audience and video measurement
            "conviva.com",
            "imrworldwide.com", "nielsen.com",
            "npaw.com", "youbora.com", "gnsnpaw.com",
            "litix.io", // Mux Data
            // Google Analytics / Firebase Analytics upload
            "app-measurement.com", "google-analytics.com",
    };

    /** Tubi's first-party analytics: analytics-ingestion*.tubi.io / *.tubitv.com. */
    private static final String FIRST_PARTY_PREFIX = "analytics-ingestion";
    private static final String[] FIRST_PARTY_DOMAINS = {"tubi.io", "tubitv.com"};

    private static final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

    private TrackerBlocker() {
    }

    public static boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase();
        for (String domain : BLOCKED_DOMAINS) {
            if (matches(h, domain)) {
                return true;
            }
        }
        if (h.startsWith(FIRST_PARTY_PREFIX)) {
            for (String domain : FIRST_PARTY_DOMAINS) {
                if (matches(h, domain)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static void logBlocked(String layer, String host) {
        if (logged.add(layer + ":" + host)) {
            Log.i(TAG, "blocked " + layer + " -> " + host);
        }
    }

    /** Replaces URL.openConnection() at every app call site. */
    public static URLConnection openConnection(URL url) throws IOException {
        String host = url.getHost();
        if (isBlocked(host)) {
            logBlocked("urlconnection", host);
            throw new IOException("Blocked by Morphe privacy patch: " + host);
        }
        return url.openConnection();
    }

    /** Called with the Builder at the start of OkHttpClient.<init>(Builder). */
    public static void install(OkHttpClient.Builder builder) {
        try {
            for (Interceptor interceptor : builder.interceptors()) {
                if (interceptor instanceof BlockInterceptor) {
                    return; // newBuilder() copies of a client we already covered
                }
            }
            builder.interceptors().add(0, new BlockInterceptor());
        } catch (Throwable t) {
            Log.w(TAG, "install failed: " + t);
        }
    }

    /** Returns an empty response for blocked WebView requests, or null to pass through. */
    public static WebResourceResponse blockWebRequest(WebResourceRequest request) {
        try {
            if (request == null || request.getUrl() == null) {
                return null;
            }
            String host = request.getUrl().getHost();
            if (!isBlocked(host)) {
                return null;
            }
            logBlocked("webview", host);
            return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class BlockInterceptor implements Interceptor {
        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            String host = request.url().host();
            if (isBlocked(host)) {
                logBlocked("okhttp", host);
                throw new IOException("Blocked by Morphe privacy patch: " + host);
            }
            return chain.proceed(request);
        }
    }
}
