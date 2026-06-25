package ajstrick81.morphe.extension.peacock.ads;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Random;

/**
 * Peacock ATV — OkHttp ad CDN interceptor (Layer 6).
 *
 * Intercepts known ad CDN hostnames and analytics endpoints at the OkHttp
 * layer before any connection is made. Mirrors the Layer 7 WebView strategy
 * with randomized responses to avoid a detectable uniform blocking pattern
 * in FreeWheel's fraud detection system.
 *
 * Response strategy (matches PeacockWebViewHelper):
 *   - Ad CDN hosts:   75% 503, 25% 204, with occasional 50–200ms delay
 *   - Analytics hosts: 50% 204, 50% 200, with occasional delay
 *
 * Why 503 for ad CDN (not 200):
 *   503 triggers ExoPlayer's error/skip logic rather than the media parser.
 *   A 200 with a 0-byte body causes UnrecognizedInputFormatException.
 *   204 is safe for non-video endpoints (analytics beacons).
 *
 * Safe CDNs — never intercepted:
 *   *-prd-mc.cdn.peacocktv.com  (slate/fallback content)
 *   *-prd-ns.prd.pck.netskrt.net (content delivery)
 *   *-prd-fy.cdn.peacocktv.com  (confirmed via AGH DNS testing to break
 *                                 sports highlights and game replays — see
 *                                 "USE With Caution" section of the AdGuard
 *                                 user rules; the DNS-layer rule for this
 *                                 shard is disabled for the same reason, so
 *                                 it must not be blocked here either)
 *
 * PCAP confirmed: ad segment traffic is primarily Chromium/WebView (Layer 7).
 * This interceptor catches OkHttp-reachable ad traffic — manifest fetches,
 * analytics beacons, and any CDN connections that leak through the connection
 * pool during extended sessions.
 */
@SuppressWarnings("unused")
public class AdBlockInterceptor implements Interceptor {

    private static final Random RANDOM = new Random();

    // Ad CDN shards confirmed via PCAP/AGH log analysis.
    // Suffix matching covers future group IDs (g007, g008, etc.) automatically.
    //
    // "prd-fy" is intentionally NOT included here — AGH DNS testing confirmed
    // blocking that shard breaks sports highlights and game replay playback.
    // See the AdGuard user rules' "USE With Caution" section, where the
    // equivalent DNS rule was disabled for the same reason.
    //
    // All six shard letters from the AdGuard DNS regex
    // (prd-(cf|cc|ak|ph|dc|at)) must be mirrored here — v2.7 only had
    // ak/cf, leaving cc/ph/dc/at unblocked at the OkHttp layer.
    private static final String[] AD_CDN_SUFFIXES = {
        "prd-ak.cdn.peacocktv.com",
        "prd-cf.cdn.peacocktv.com",
        "prd-cc.cdn.peacocktv.com",
        "prd-ph.cdn.peacocktv.com",
        "prd-dc.cdn.peacocktv.com",
        "prd-at.cdn.peacocktv.com",
    };

    // Netskrt CDN — block all shards except -ns (content delivery), mirrors
    // PeacockWebViewHelper's NETSKRT_DOMAIN/NETSKRT_SAFE_SUFFIX logic. Catches
    // ad segments fetched directly by ExoPlayer/OkHttp rather than WebView.
    private static final String NETSKRT_DOMAIN = ".prd.pck.netskrt.net";
    private static final String NETSKRT_SAFE_SUFFIX = "-ns.prd.pck.netskrt.net";

    // Ad creative delivery — serves the actual ad video/image asset, not just
    // a beacon. Treated like AD_CDN_SUFFIXES (503/204 skip response) rather
    // than ANALYTICS_DOMAINS (200/204 accepted-beacon response).
    private static final String[] AD_CREATIVE_DOMAINS = {
        "2mdn.net",
    };

    // Analytics and ad decision endpoints reachable via OkHttp.
    //
    // sas.peacocktv.com (Streaming Ad Service) / vac.peacocktv.com (Video Ad
    // Config) are matched here as flat substrings — the proven form shipped
    // through the 1.4.x line. A structural leftmost-label matcher was tried in
    // v1.5.3 to also catch regional shards (sas.f / sas.a) but was reverted
    // after it regressed core screen loading on v7.5.100 (issue #29).
    private static final String[] ANALYTICS_DOMAINS = {
        "fwmrm.net",
        "video-ads-module.ad-tech.nbcuni.com",
        "mediatailor.",
        "sas.peacocktv.com",
        "vac.peacocktv.com",
        "scorecardresearch.com",
        "conviva.com",
        "imrworldwide.com",
        "omtrdc.net",
        "newrelic.com",
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
        // unblocked at the OkHttp layer:
        "flashtalking.com",
        "researchnow.com",
        "firebaselogging.googleapis.com", // full host only — must not catch play*.googleapis.com
    };

    @Override
    public Response intercept(Chain chain) throws IOException {
        final String host = chain.request().url().host();

        for (final String suffix : AD_CDN_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return randomAdResponse(chain);
            }
        }

        if (host.endsWith(NETSKRT_DOMAIN) && !host.endsWith(NETSKRT_SAFE_SUFFIX)) {
            return randomAdResponse(chain);
        }

        for (final String domain : AD_CREATIVE_DOMAINS) {
            if (host.contains(domain)) {
                return randomAdResponse(chain);
            }
        }

        for (final String domain : ANALYTICS_DOMAINS) {
            if (host.contains(domain)) {
                return randomAnalyticsResponse(chain);
            }
        }

        return chain.proceed(chain.request());
    }

    /**
     * Randomized response for ad video CDN requests.
     * Uses 503 as primary to trigger ExoPlayer's error/skip path.
     * Mixes in 204 and occasional delays to avoid uniform blocking pattern.
     */
    private static Response randomAdResponse(final Chain chain) {
        int roll = RANDOM.nextInt(100);

        if (roll < 15) {
            try { Thread.sleep(50 + RANDOM.nextInt(150)); }
            catch (InterruptedException ignored) {}
        }

        int code = (roll < 25) ? 204 : 503;
        String message = (code == 204) ? "No Content" : "Service Unavailable";

        return new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(ResponseBody.create(
                MediaType.parse("text/plain; charset=utf-8"), ""))
            .build();
    }

    /**
     * Randomized response for analytics/beacon endpoints.
     * Uses 204 as primary — normal server behaviour for accepted beacons.
     * Looks like server-side suppression rather than client-side blocking.
     */
    private static Response randomAnalyticsResponse(final Chain chain) {
        int roll = RANDOM.nextInt(100);

        if (roll < 15) {
            try { Thread.sleep(50 + RANDOM.nextInt(150)); }
            catch (InterruptedException ignored) {}
        }

        int code = (roll < 50) ? 204 : 200;
        String message = (code == 204) ? "No Content" : "OK";

        return new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(ResponseBody.create(
                MediaType.parse("text/plain; charset=utf-8"), ""))
            .build();
    }
}
