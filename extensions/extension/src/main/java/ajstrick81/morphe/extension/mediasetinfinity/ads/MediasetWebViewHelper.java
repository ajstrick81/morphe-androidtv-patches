package ajstrick81.morphe.extension.mediasetinfinity.ads;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mediaset Infinity Android TV — "Skip ads".
 *
 * The app is a WebView shell around the remote "ctv-lng" web app; the APK holds
 * no ad SDK. The web app decides and schedules ads itself, so the one place the
 * shell sees them is the web app's network traffic, which all passes through
 * the shell's WebViewClient.shouldInterceptRequest. intercept() is called at the
 * top of that method from injected smali: a non-null return is handed back to
 * the WebView in place of the network response; null lets the app carry on.
 *
 * What is answered locally (empty 204):
 *   - FreeWheel ad requests and ad beacons: *.fwmrm.net/ad/...  Mediaset's ad
 *     stack is FreeWheel (public 2018 Mediaset/FreeWheel announcement). Only the
 *     /ad/ paths are answered; the FreeWheel SDK script itself is left alone so
 *     the web app's `tv.freewheel` object still exists and its normal "ad
 *     request failed, play content" path runs.
 *   - Google ad-decision / ad-creative hosts, in case a break is filled that way.
 * Google's IMA SDK script (imasdk.googleapis.com) is deliberately NOT blocked for
 * the same reason as the FreeWheel script.
 *
 * NOT VERIFIED ON A DEVICE. The host list is built from the public ad stack, not
 * from a capture of 8.1.13's traffic. To capture what the web app really calls:
 *   adb shell setprop log.tag.MORPHE-MSI DEBUG     (then restart the app)
 *   adb logcat -s MORPHE-MSI
 * which logs each distinct third-party host once ("host ...") next to the
 * always-on "blocked ..." lines. Nothing is sent anywhere by this class.
 */
@SuppressWarnings("unused")
public final class MediasetWebViewHelper {
    private static final String TAG = "MORPHE-MSI";

    /** Hosts whose every request is an ad decision, ad creative or ad beacon. */
    private static final String[] AD_DOMAINS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "2mdn.net",
    };

    /** FreeWheel: block the ad request/beacon paths only, never the SDK script. */
    private static final String FREEWHEEL_DOMAIN = "fwmrm.net";
    private static final String FREEWHEEL_AD_PATH = "/ad/";

    /** First-party domains, left out of the diagnostic host log. */
    private static final String[] FIRST_PARTY_DOMAINS = {
            "mediaset.it",
            "mediaset.net",
    };

    private static final int MAX_LOGGED_HOSTS = 256;
    private static final Set<String> LOGGED_HOSTS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private MediasetWebViewHelper() {
    }

    /**
     * @return an empty response for an ad request, or null to let the request
     *         through untouched. Never throws: any failure means "don't block".
     */
    public static WebResourceResponse intercept(WebResourceRequest request) {
        try {
            if (request == null) {
                return null;
            }
            Uri uri = request.getUrl();
            if (uri == null) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath();

            if (isAdRequest(host, path)) {
                Log.i(TAG, "blocked " + host + (path == null ? "" : path));
                return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                        Collections.<String, String>emptyMap(),
                        new ByteArrayInputStream(new byte[0]));
            }

            logHostOnce(host);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAdRequest(String host, String path) {
        if (isDomainOrSubdomain(host, FREEWHEEL_DOMAIN)) {
            return path != null && path.startsWith(FREEWHEEL_AD_PATH);
        }
        for (String domain : AD_DOMAINS) {
            if (isDomainOrSubdomain(host, domain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDomainOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** Opt-in diagnostics: one line per distinct third-party host. */
    private static void logHostOnce(String host) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) {
            return;
        }
        for (String domain : FIRST_PARTY_DOMAINS) {
            if (isDomainOrSubdomain(host, domain)) {
                return;
            }
        }
        if (LOGGED_HOSTS.size() < MAX_LOGGED_HOSTS && LOGGED_HOSTS.add(host)) {
            Log.d(TAG, "host " + host);
        }
    }
}
