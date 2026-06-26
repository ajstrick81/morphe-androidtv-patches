package ajstrick81.morphe.extension.peacock.ads;

import android.util.Log;
import android.webkit.ClientCertRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Layer 7 — WebView shouldInterceptRequest injection.
 *
 * Randomized response strategy: instead of returning an identical empty 200
 * for every blocked request, we vary the response to mimic natural network
 * variability. This avoids a detectable wall pattern in FreeWheel's fraud
 * detection system, which flags devices that never complete ad views.
 *
 * Response variation:
 *   - 60-75% empty 200 OK (normal load, no content)
 *   - 25-40% 204 No Content (server acknowledged, nothing to send)
 * No artificial delay is added (see randomResponse() below for why).
 *
 * Analytics hosts (scorecardresearch, imrworldwide, omtrdc) are still blocked
 * but with higher 204 probability to look like server-side suppression rather
 * than client-side blocking.
 *
 * Critical: onReceivedClientCertRequest MUST be delegated — Peacock uses
 * mutual TLS with a client certificate (com.peacock.peacocktv.tamper.Loader).
 *
 * Host-matching order: AD_HOSTS (and the ad-config hosts) are always checked
 * BEFORE SAFE_HOSTS. SAFE_HOSTS uses substring matching against broad domains
 * ("nbcuni.com", "peacocktv.com") which would otherwise shadow ad hosts that
 * happen to be subdomains of those same domains, e.g.
 * "video-ads-module.ad-tech.nbcuni.com" contains "nbcuni.com" and would be
 * incorrectly treated as safe if the safe-list were checked first.
 */
public class PeacockWebViewHelper {

    private static final String TAG = "MORPHE-PCK-WV";
    private static final Random RANDOM = new Random();

    private static final String[] SAFE_HOSTS = {
        "peacocktv.com",
        "nbcuni.com",
        "nbcuniversal.com",
    };

    private static final String[] AD_HOSTS = {
        // ── Ad config — Peacock's own ad-decision hosts. Flat substrings (the
        // proven 1.4.x form). A structural leftmost-label matcher was tried in
        // v1.5.3 to also catch regional shards but was reverted after it
        // regressed core screen loading on v7.5.100 (issue #29). These are
        // listed BEFORE the SAFE_HOSTS "peacocktv.com" entry is consulted.
        "sas.peacocktv.com",
        "vac.peacocktv.com",

        // ── FreeWheel CSAI
        "fwmrm.net",
        "video-ads-module.ad-tech.nbcuni.com",

        // ── MediaTailor SSAI — belt-and-suspenders alongside Layers 1/3/4
        "mediatailor.",

        // ── Analytics / measurement
        "scorecardresearch.com",
        "imrworldwide.com",
        "omtrdc.net",
        "conviva.com",
        "doubleverify.com",
        "adsafeprotected.com",
        "innovid.com",
        "agkn.com",
        "placed.com",
        "doubleclick.net",
        "rlcdn.com",
        "nbcuas.com",
        "mparticle.com",
        // Added after AGH log analysis — confirmed contacted but previously
        // unblocked at the WebView layer:
        "flashtalking.com",
        "researchnow.com",
        "firebaselogging.googleapis.com", // full host only — must not catch play*.googleapis.com

        // ── Ad creative delivery — serves the actual ad asset, not a beacon
        "2mdn.net",
    };

    // Netskrt CDN — block all shards except -ns (content delivery)
    private static final String NETSKRT_DOMAIN = ".prd.pck.netskrt.net";
    private static final String NETSKRT_SAFE_SUFFIX = "-ns.prd.pck.netskrt.net";

    /**
     * Returns a randomized empty response to avoid a detectable uniform
     * blocking pattern. Varies status code and introduces occasional delays.
     *
     * @param isAnalytics true for pure analytics hosts — higher 204 probability
     *                    to mimic server-side suppression
     */
    private static WebResourceResponse randomResponse(boolean isAnalytics) {
        int roll = RANDOM.nextInt(100);

        // No artificial delay here. shouldInterceptRequest runs on Chromium's
        // own IO thread pool, and blocking it on a memory-constrained device
        // contributed to a sustained GC-pressure climb to OutOfMemoryError
        // within ~2.5 minutes of launch — see AdBlockInterceptor.java's
        // randomAdResponse() for the matching fix and full rationale on the
        // OkHttp side, which is where this was actually root-caused.

        // Vary status code
        int statusCode;
        String reasonPhrase;
        if (isAnalytics) {
            // Analytics: higher probability of 204 — looks like server accepted
            // the beacon but has nothing to return, which is normal behaviour
            statusCode = (roll < 50) ? 204 : 200;
            reasonPhrase = (statusCode == 204) ? "No Content" : "OK";
        } else {
            // Ad video: mix of 200 and 204
            statusCode = (roll < 25) ? 204 : 200;
            reasonPhrase = (statusCode == 204) ? "No Content" : "OK";
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Length", "0");
        headers.put("Connection", "close");

        return new WebResourceResponse(
            "text/plain",
            "utf-8",
            statusCode,
            reasonPhrase,
            headers,
            new ByteArrayInputStream(new byte[0])
        );
    }

    private static boolean isAnalyticsHost(String url) {
        return url.contains("scorecardresearch.com")
            || url.contains("imrworldwide.com")
            || url.contains("omtrdc.net")
            || url.contains("conviva.com")
            || url.contains("doubleverify.com")
            || url.contains("adsafeprotected.com")
            || url.contains("placed.com")
            || url.contains("mparticle.com")
            || url.contains("flashtalking.com")
            || url.contains("researchnow.com")
            || url.contains("firebaselogging.googleapis.com");
    }

    private static boolean shouldBlock(String url) {
        // Ad hosts are checked FIRST. SAFE_HOSTS below uses broad substring
        // matching ("nbcuni.com", "peacocktv.com") that would otherwise
        // shadow ad hosts living under those same parent domains.
        for (String ad : AD_HOSTS) {
            if (url.contains(ad)) return true;
        }

        // Netskrt CDN — block all except -ns content shard
        if (url.contains(NETSKRT_DOMAIN) && !url.contains(NETSKRT_SAFE_SUFFIX)) {
            return true;
        }

        // Safe-list — Peacock's own infrastructure passes through once we've
        // confirmed the URL isn't one of the ad hosts above.
        for (String safe : SAFE_HOSTS) {
            if (url.contains(safe)) return false;
        }

        return false;
    }

    /**
     * Named (non-anonymous) subclass of WebViewClient.
     *
     * A previous version of wrapClient() returned an anonymous
     * `new WebViewClient() {...}` instance directly, which ART rejected with
     * a VerifyError ("[0xC] returning 'Precise Reference: <anon>', but
     * expected ... 'Reference: android.webkit.WebViewClient'") on v7.6.100 —
     * 100% reproducible, fatal on every launch. A follow-up attempt to fix
     * this by adding `(WebViewClient) (Object) wrapped` was eliminated by R8
     * as a redundant cast (wrapped was already statically WebViewClient),
     * regenerating the same unguarded bytecode and shipping the identical
     * crash in v1.5.6. Using a real named class instead of an anonymous one
     * removes the synthetic-class ambiguity that the verifier choked on when
     * this extension's separately-dexed code is merged into the host APK via
     * extendWith(), and there is no optimizable cast for R8 to strip.
     */
    private static final class WrappedClient extends WebViewClient {
        private final WebViewClient original;

        WrappedClient(WebViewClient original) {
            this.original = original;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                if (shouldBlock(url)) {
                    boolean analytics = isAnalyticsHost(url);
                    Log.d(TAG, "BLOCKED [" + (analytics ? "analytics" : "ad") + "]: " + url);
                    return randomResponse(analytics);
                }
                return null;
            } catch (Exception e) {
                Log.e(TAG, "shouldInterceptRequest error: " + e.getMessage());
                return null;
            }
        }

        // ── Critical: mutual TLS client certificate ───────────────────────
        @Override
        public void onReceivedClientCertRequest(WebView view,
                ClientCertRequest certRequest) {
            original.onReceivedClientCertRequest(view, certRequest);
        }

        // ── Delegate all xtvClient overrides ─────────────────────────────
        @Override
        public void onPageStarted(WebView view, String url,
                android.graphics.Bitmap favicon) {
            original.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            original.onPageFinished(view, url);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            original.onLoadResource(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
            original.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedHttpError(WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse) {
            original.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public void onReceivedSslError(WebView view,
                android.webkit.SslErrorHandler handler,
                android.net.http.SslError error) {
            original.onReceivedSslError(view, handler, error);
        }

        @Override
        public boolean onRenderProcessGone(WebView view,
                android.webkit.RenderProcessGoneDetail detail) {
            return original.onRenderProcessGone(view, detail);
        }
    }

    public static WebViewClient wrapClient(final WebViewClient original) {
        Log.d(TAG, "PeacockWebViewHelper.wrapClient() — Layer 7 active (randomized responses)");
        return new WrappedClient(original);
    }
}
