package ajstrick81.morphe.extension.peacock.ads;

import android.net.Uri;
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
 *   - 60% empty 200 OK (normal load, no content)
 *   - 25% 204 No Content (server acknowledged, nothing to send)
 *   - 15% random delay 50-200ms then 200 (simulates slow CDN response)
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
        // ── Ad config — Peacock's own ad-decision hosts (sas/vac.peacocktv.com
        // and their regional shards sas.f / sas.a / vac.<region>) are handled by
        // isPeacockAdDecisionHost() rather than listed here, since a flat
        // substring misses the shards and the peacocktv.com safe-list would then
        // allow them through.

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

        // Occasionally add a small delay to mimic slow/busy CDN
        if (roll < 15) {
            try {
                Thread.sleep(50 + RANDOM.nextInt(150)); // 50–200ms
            } catch (InterruptedException ignored) {}
        }

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

    /**
     * Matches Peacock's own ad-decision services across all regional shards:
     * sas.peacocktv.com, sas.&lt;region&gt;.peacocktv.com (Streaming Ad Service),
     * vac.peacocktv.com, vac.&lt;region&gt;.peacocktv.com (Video Ad Config).
     * Matching the leftmost host label catches every shard while leaving content
     * subdomains (play.clients/atom/imageservice.peacocktv.com) untouched.
     */
    private static boolean isPeacockAdDecisionHost(String host) {
        if (host == null || !host.endsWith("peacocktv.com")) return false;
        return host.startsWith("sas.") || host.startsWith("vac.");
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
        // Peacock ad-decision shards (sas/vac.<region>.peacocktv.com) — checked
        // before everything, since the SAFE_HOSTS "peacocktv.com" entry below
        // would otherwise allow them through.
        if (isPeacockAdDecisionHost(Uri.parse(url).getHost())) return true;

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

    public static WebViewClient wrapClient(final WebViewClient original) {
        Log.d(TAG, "PeacockWebViewHelper.wrapClient() — Layer 7 active (randomized responses)");
        return new WebViewClient() {

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

            // ── Critical: mutual TLS client certificate ───────────────────
            @Override
            public void onReceivedClientCertRequest(WebView view,
                    ClientCertRequest certRequest) {
                original.onReceivedClientCertRequest(view, certRequest);
            }

            // ── Delegate all xtvClient overrides ─────────────────────────
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
        };
    }
}
