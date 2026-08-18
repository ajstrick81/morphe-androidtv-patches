package ajstrick81.morphe.extension.hbomax.ads;

import android.net.Uri;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * HBO Max Android TV — SSAI ad-origin filter (in-app reproduction of the
 * AdGuard Home DNS block).
 *
 * <p>HBO Max serves ad-supported ("ad_light" tier) content as a single
 * multi-period SSAI DASH manifest. The manifest document itself is served
 * from a clean origin (e.g. {@code gcp.prd.media.h264.io}), but every media
 * segment — BOTH the ad periods and the DRM'd content periods — is fetched
 * from the ad/SSAI segment origin {@code {gcp|akm|cf|fly}.<region>-free.prd.media.max.com}
 * (region = amer/emea/…). The ad-break timeline metadata comes from
 * {@code gmss.*.prd.api.discomax.com} and the ad decision from FreeWheel
 * ({@code *.fwmrm.net}). Verified on 7.9.0.61 by pulling the live manifest:
 * a 1:42:00 movie was stretched to 2:02:02 by ~20 min of stitched ads, all
 * from {@code gcp.amer-free.prd.media.max.com}.
 *
 * <p>The AdGuard user-rule lists (both the original and the EMEA fork) kill
 * these ads completely by DNS-blocking those origins with {@code $important}.
 * Because the {@code -free} origin carries the SSAI *content* segments too,
 * blocking it forces HBO's client-side "resiliency-v5.1" CDN-failover layer
 * to abandon the SSAI manifest and re-request a clean, non-stitched manifest
 * — the ad-free stream. This filter reproduces that by throwing an
 * {@link IOException} from media3's {@code DefaultHttpDataSource.open(DataSpec)}
 * whenever the request targets one of those origins, which the ExoPlayer
 * loader surfaces to the same resiliency layer as a load error (equivalent to
 * the DNS failure AdGuard produces).
 *
 * <p>Deliberately NOT blocked: the manifest origin ({@code *.h264.io}) and the
 * QoE telemetry hosts ({@code *.litix.io}, {@code beacons-*.mediamelon.com}) —
 * those are cosmetic and blocking them is unnecessary for ad removal.
 *
 * <p>Host matching is by substring so new regional shards
 * ({@code apac-free}, additional {@code gcp/akm/cf/fly} CDN letters, …) and
 * FreeWheel/GMSS subdomains are covered without a version bump.
 */
@SuppressWarnings("unused")
public final class HboAdOriginFilter {

    // Substrings identifying HBO's SSAI ad / ad-decision / ad-timeline origins.
    // Matched against the request URL host (case-insensitive).
    private static final String[] BLOCKED_HOST_SUBSTRINGS = {
        "-free.prd.media.max.com", // amer-free / emea-free / any <region>-free SSAI segment origin
        "gmss.",                   // gmss.*.prd.api.discomax.com — SSAI ad-break timeline
        "fwmrm.net",               // FreeWheel — ad decision server
        "dnitv.com",               // DNI TV ad host
    };

    // Cached reflected DataSpec.uri field. media3's DataSpec exposes its uri as
    // a public final field, but its class/field names are R8-obfuscated and
    // vary per build, so we resolve it by type (the first Uri-typed field)
    // rather than hardcoding a name, and cache the result.
    private static volatile Field cachedUriField;

    private HboAdOriginFilter() {
    }

    /**
     * Called at the entry of {@code DefaultHttpDataSource.open(DataSpec)} via
     * injected smali. {@code dataSpec} is passed as {@link Object} so the patch
     * never references the obfuscated DataSpec type.
     *
     * @param dataSpec the media3 DataSpec being opened (may be null).
     * @throws IOException if the request targets a blocked ad origin.
     */
    public static void guard(Object dataSpec) throws IOException {
        if (dataSpec == null) {
            return;
        }
        Uri uri = extractUri(dataSpec);
        if (uri == null) {
            return;
        }
        String host = uri.getHost();
        if (host == null) {
            return;
        }
        String lower = host.toLowerCase();
        for (String needle : BLOCKED_HOST_SUBSTRINGS) {
            if (lower.contains(needle)) {
                throw new IOException("HBO Max ad origin blocked: " + host);
            }
        }
    }

    private static Uri extractUri(Object dataSpec) {
        Field field = cachedUriField;
        if (field != null && field.getDeclaringClass().isInstance(dataSpec)) {
            try {
                return (Uri) field.get(dataSpec);
            } catch (Exception ignored) {
                // fall through to rescan
            }
        }
        for (Field candidate : dataSpec.getClass().getFields()) {
            if (Uri.class.isAssignableFrom(candidate.getType())) {
                try {
                    candidate.setAccessible(true);
                    Uri value = (Uri) candidate.get(dataSpec);
                    cachedUriField = candidate;
                    return value;
                } catch (Exception ignored) {
                    // try the next Uri-typed field
                }
            }
        }
        return null;
    }
}
