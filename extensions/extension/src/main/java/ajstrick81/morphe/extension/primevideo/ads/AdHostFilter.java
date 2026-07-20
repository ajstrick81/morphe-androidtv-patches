package ajstrick81.morphe.extension.primevideo.ads;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Prime Video ATV — ad request policy (pure).
 *
 * The host/path decision that {@link SkipAdsPatch#enforceAdBlock} enforces,
 * split out from the Volley/Android surface (Request / NoConnectionError / Log)
 * so it depends only on {@link java.net.URI} and can be unit-tested on a plain
 * JVM. enforceAdBlock is now the thin transport wrapper: it pulls the URL off
 * the Volley Request, calls {@link #blockReason}, and throws NoConnectionError
 * when a reason comes back.
 *
 * Allowlist-by-default: only URLs matched here are blocked; everything else
 * passes through. Host inventory derived from an ad-free vs ad-supported session
 * diff (Prime Video filter list v3.6); see enforceAdBlock's Javadoc for the
 * rationale on each entry and why the safe-harbor api.amazonvideo.com hosts
 * (keho/qgmg/p7kg/abxc3apcastp) must NOT collide with the threeplr / nit rules.
 */
public final class AdHostFilter {

    private AdHostFilter() {}

    /**
     * @return a short reason string if {@code url} is an ad request that should
     *         be blocked (the getVideoAds path, or a blocklisted host), or
     *         {@code null} if it should pass through. The reason is used both
     *         for the log line and the {@code ads_blocked: <reason>} cause.
     */
    public static String blockReason(String url) {
        if (url == null) return null;

        // Ad decisioning is a PATH on the dual-use playback host atv-ps.amazon.com
        // (which also serves Widevine licensing + session/playback). Match the
        // path, never the host, and check it before URI parsing so a malformed
        // ad URL is still caught.
        if (url.contains("/cdp/getVideoAds")) return "getVideoAds";

        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null; // malformed — not our concern, let it through
        }
        if (host == null) return null;
        host = host.toLowerCase();

        boolean blocked =
                // Ad exchange / decisioning (covers s. and mads. subdomains)
                host.equals("amazon-adsystem.com")
                || host.endsWith(".amazon-adsystem.com")
                // Ad delivery network — every aiv-delivery.net subdomain.
                || host.equals("aiv-delivery.net")
                || host.endsWith(".aiv-delivery.net")
                // Weblab ad triggers — exact match keeps the rest of
                // *.weblab.a2z.com in the safe harbor.
                || host.equals("zoar.triggers-v1.prod.mobile.weblab.a2z.com")
                || host.equals("hercule.triggers-v1.prod.mobile.weblab.a2z.com")
                // Ad orchestration endpoints on the video API — anchored to the
                // threeplr*/nit* prefixes so they cannot collide with the
                // safe-harbor keho/qgmg/p7kg/abxc3apcastp hosts.
                || host.matches("threeplr[a-z0-9.-]*\\.api\\.amazonvideo\\.com")
                || host.matches("nit[a-z0-9.-]*\\.api\\.amazonvideo\\.com")
                // Fires every ad cycle per on-device DNS log; no-cost safety net.
                || host.equals("s0s7.api.amazonvideo.com")
                // Pause-screen ad endpoint (pauseAdsResolutionUrl).
                || host.equals("regolith.prime-video.amazon.dev");

        return blocked ? host : null;
    }
}
