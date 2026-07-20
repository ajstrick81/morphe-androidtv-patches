package ajstrick81.morphe.extension.primevideo.ads;

/**
 * Host unit test for {@link AdHostFilter} — the pure ad-request policy behind
 * SkipAdsPatch.enforceAdBlock. Depends only on the JDK (java.net.URI), so it
 * runs on a plain JVM with no Volley/Android/Gradle test runner:
 *
 *   cd extensions/extension/src/{main,test}/java   (both roots on the path)
 *   javac -d /tmp/adtest \
 *     main/java/ajstrick81/morphe/extension/primevideo/ads/AdHostFilter.java \
 *     test/java/ajstrick81/morphe/extension/primevideo/ads/AdHostFilterTest.java
 *   java -cp /tmp/adtest ajstrick81.morphe.extension.primevideo.ads.AdHostFilterTest
 *
 * Exits non-zero on any failed assertion.
 */
public class AdHostFilterTest {

    private static int failures = 0;

    private static void blocked(String url, String why) {
        if (AdHostFilter.blockReason(url) == null) {
            System.out.println("FAIL (expected BLOCK): " + why + "  [" + url + "]");
            failures++;
        } else {
            System.out.println("ok:   BLOCK  " + why);
        }
    }

    private static void pass(String url, String why) {
        String r = AdHostFilter.blockReason(url);
        if (r != null) {
            System.out.println("FAIL (expected PASS): " + why + "  [" + url + "] reason=" + r);
            failures++;
        } else {
            System.out.println("ok:   PASS   " + why);
        }
    }

    private static void reasonIs(String url, String expected, String why) {
        String r = AdHostFilter.blockReason(url);
        if (!expected.equals(r)) {
            System.out.println("FAIL (reason): " + why + "  expected=" + expected + " got=" + r);
            failures++;
        } else {
            System.out.println("ok:   REASON " + why);
        }
    }

    public static void main(String[] args) {
        // ── getVideoAds path (host-independent, checked before URI parse) ─────
        blocked("https://atv-ps.amazon.com/cdp/getVideoAds?deviceTypeID=A1&x=1", "getVideoAds on playback host");
        reasonIs("https://atv-ps.amazon.com/cdp/getVideoAds?x=1", "getVideoAds", "getVideoAds reason string");
        blocked("https://anything.example.com/a/cdp/getVideoAds/b", "getVideoAds anywhere in path");
        // …but the dual-use playback host itself must pass when it's NOT getVideoAds
        pass("https://atv-ps.amazon.com/cdp/catalog/GetPlaybackResources?x=1", "playback host non-ad path");
        pass("https://atv-ps.amazon.com/", "playback host root");

        // ── amazon-adsystem.com (equals + subdomain) ─────────────────────────
        blocked("https://amazon-adsystem.com/e/dtb/bid", "amazon-adsystem apex");
        blocked("https://s.amazon-adsystem.com/x", "s.amazon-adsystem subdomain");
        blocked("https://mads.amazon-adsystem.com/", "mads.amazon-adsystem subdomain");
        // case-insensitive host handling
        blocked("https://S.AMAZON-ADSYSTEM.COM/x", "upper-case host lowercased before match");
        // anti-false-positive: not a real subdomain (no dot boundary)
        pass("https://notamazon-adsystem.com/", "lookalike host without dot boundary");
        pass("https://amazon-adsystem.com.evil.example/", "suffix spoof via different apex");

        // ── aiv-delivery.net (equals + subdomain) ────────────────────────────
        blocked("https://aiv-delivery.net/", "aiv-delivery apex");
        blocked("https://ters-na.aiv-delivery.net/seg", "ters-na.aiv-delivery subdomain");

        // ── weblab triggers — exact match only, rest of weblab safe ──────────
        blocked("https://zoar.triggers-v1.prod.mobile.weblab.a2z.com/t", "zoar weblab trigger");
        blocked("https://hercule.triggers-v1.prod.mobile.weblab.a2z.com/t", "hercule weblab trigger");
        pass("https://other.triggers-v1.prod.mobile.weblab.a2z.com/t", "other weblab host (safe harbor)");
        pass("https://api.weblab.a2z.com/", "generic weblab host (safe harbor)");

        // ── threeplr*/nit* api.amazonvideo.com regex ─────────────────────────
        blocked("https://threeplr-eu.api.amazonvideo.com/v", "threeplr-eu ad orchestration");
        blocked("https://threeplr.api.amazonvideo.com/v", "threeplr bare prefix");
        blocked("https://nit12.api.amazonvideo.com/v", "nit12 ad orchestration");
        // safe-harbor api.amazonvideo.com hosts must NOT be caught by the regex
        pass("https://keho.api.amazonvideo.com/v", "safe-harbor keho");
        pass("https://qgmg.api.amazonvideo.com/v", "safe-harbor qgmg");
        pass("https://p7kg.api.amazonvideo.com/v", "safe-harbor p7kg");
        pass("https://abxc3apcastp.api.amazonvideo.com/v", "safe-harbor abxc3apcastp");
        // must be anchored to api.amazonvideo.com, not just any amazonvideo host
        pass("https://threeplr-eu.gamedia.amazonvideo.com/v", "threeplr on non-api subdomain");

        // ── exact single hosts ───────────────────────────────────────────────
        blocked("https://s0s7.api.amazonvideo.com/x", "s0s7 exact host");
        blocked("https://regolith.prime-video.amazon.dev/getAds?format=PAUSE_ADS_STATIC", "regolith pause-ad host");

        // ── general pass-through (content / media CDNs) ──────────────────────
        pass("https://na.gamedia.amazonvideo.com/catalog", "generic content API");
        pass("https://d25xi40x97liuc.cloudfront.net/seg1.ts", "media CDN segment");
        pass("https://images-na.ssl-images-amazon.com/img.jpg", "image host");

        // ── malformed / edge inputs (must not throw, default to pass) ─────────
        pass(null, "null url");
        pass("http://exa mple.com/path", "malformed url (space) — URISyntaxException swallowed");
        pass("/cdp/catalog/relative", "relative url, no host");
        // …but a relative getVideoAds path is still caught (path check precedes parse)
        blocked("/cdp/getVideoAds?x=1", "relative getVideoAds path still blocked");

        System.out.println();
        System.out.println((failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED")
                + " (" + failures + " failure(s))");
        System.exit(failures == 0 ? 0 : 1);
    }
}
