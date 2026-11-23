package ajstrick81.morphe.extension.pluto.ads;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pluto TV v17.x (Paramount+/AVIA rebuild) — VOD ad REMOVAL by DASH period surgery
 * at the XML level.
 *
 * <p>v17 replaced the old leanback stack with the Paramount AVIA player; media3 and
 * okhttp are R8-minified, so the leanback object-level {@code DashManifestParser.parse}
 * hook (which references androidx.media3 DashManifest types by name) can no longer be
 * attached, and the okhttp interceptor seam is renamed. Instead, Hook 3 injects a call
 * at the entry of AVIA's own un-renamed
 * {@code AviaDashParser.parse(Uri, InputStream) -> bz3} and swaps the InputStream for a
 * cleaned one via {@link #cleanManifestStream}. We strip the ad {@code <Period>}s from
 * the raw MPD XML, re-base the remaining content periods into a contiguous timeline, and
 * hand the renamed parser the cleaned manifest. Only platform/XML types are touched, so
 * no renamed library type is referenced.
 *
 * <p>Ad discrimination is identical to the leanback model (see {@code PlutoDashManifestProbe}):
 * a period is an AD iff one of its {@code <BaseURL>}s contains {@code _ad/creative}
 * (URL-encoded {@code _ad%2Fcreative}) or {@code _ad_bumper}. Everything else — content,
 * DRM'd or not — is kept, so we never drop content we don't recognize.
 *
 * <p><b>Timeline re-basing (frozen-seekbar fix).</b> Removing an ad {@code <Period>} leaves
 * a hole in the presentation timeline: the content periods after it keep their original
 * (ad-inclusive) {@code start} values, so the player's seekbar clamps at the first removed
 * ad while the media keeps flowing (symptom: scrubber frozen at ~the first ad position,
 * playback continuing). The fix: ad periods are removed WITHOUT advancing the content
 * cursor, and every kept content period's {@code start} is rewritten to run immediately
 * after the previous kept period, with the root {@code mediaPresentationDuration} reduced
 * to the kept total. Segment addressing inside each period is period-relative, so re-basing
 * {@code start} keeps segments addressable.
 *
 * <p>Fail-open on every edge (not a DASH MPD, no ad markers, dynamic/live, nothing kept,
 * implausible over-strip, or any exception) → the original manifest passes through
 * untouched, so playback can never be broken by this hook.
 */
@SuppressWarnings("unused")
public final class PlutoV17ManifestProbe {

    private static final String TAG = "MORPHE-DASH-MF";
    private static final String DASH_IDENTIFIER = "<MPD";

    /** ISO-8601 media duration: {@code PT[nH][nM][nS]}. */
    private static final Pattern PT =
            Pattern.compile("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?");

    // Fail-safe guard (see leanback #144 backstop): real ad load is ~18-30% of runtime, so
    // content stays >=~70%. If we'd keep less than this fraction of the original duration,
    // an unrecognized content shape has probably been mis-dropped -> pass the original through.
    private static final double MIN_KEPT_FRACTION = 0.4;

    // Break-filler backstop (issue #298), see findBreakFiller. Real content is >=~70% of a
    // stitched manifest and is cut from ONE clip; the longest single ad observed is ~60s.
    private static final double MAIN_CLIP_MIN_FRACTION = 0.5;
    private static final double FILLER_MAX_SECONDS = 120.0;

    /**
     * True when the current manifest is a STATIC (on-demand/VOD) MPD. Hook 4 reads this via
     * {@link #shouldSuppressDynamicAdFetch()} to suppress the ID3/CLID3-driven CSAI ad fetch
     * on VOD (where we strip ads) while leaving live/linear (dynamic) untouched. Set per
     * manifest in {@link #cleanManifestStream}.
     */
    public static volatile boolean suppressDynamicAdFetch;

    /** Hook 4 guard: suppress the CLID3 CSAI ad call when a static VOD manifest is in play. */
    public static boolean shouldSuppressDynamicAdFetch() {
        return suppressDynamicAdFetch;
    }

    // =======================================================================
    // Timeline coordinate mapping (issue #300).
    //
    // Stripping ad periods leaves the app running TWO different clocks:
    //
    //   ORIGINAL  — ad-inclusive. What Pluto's own metadata (stitcher session /
    //               episode duration) says, and the coordinate system every
    //               "Continue watching" bookmark is written and read in.
    //   STRIPPED  — content-only. What the player actually plays after Hook 3,
    //               shorter by the total removed ad time.
    //
    // The leanback patch rewrote the parsed DashManifest OBJECT, so media3's
    // timeline and Pluto's seekbar both read the same (stripped) duration and
    // only the restored bookmark needed fixing — one direction, and only as an
    // overshoot guard (PlutoDashManifestProbe.mapResumePosition, issue #147).
    //
    // On v17 Hook 3 swaps the InputStream BEFORE AVIA's parser, so only the
    // player timeline shrinks; Pluto's UI and bookmark API keep using original
    // coordinates. That mismatch is issue #300: the progress bar is drawn
    // against the original duration so it overruns the real end, seeks land at
    // the wrong place, and the position written back to "Continue watching" is
    // in the wrong clock, so resume has nothing usable. Fixing it needs the
    // mapping BOTH ways, which leanback never required.
    //
    // The table below is the per-title translation, captured by the strip that
    // produced the stripped timeline. Only one title plays at a time, so the
    // most-recent successful strip is always the right table; EVERY passthrough
    // and fail-open path clears it, so a title we did not strip can never have a
    // stale mapping applied to it (both mappers become identity).
    // =======================================================================

    private static volatile long[] removedAdStartsMs = new long[0]; // ORIGINAL-timeline starts, ascending
    private static volatile long[] removedAdDursMs = new long[0];   // matching removed durations
    private static volatile long strippedDurationMs = 0;            // content-only total
    private static volatile long originalDurationMs = 0;            // ad-inclusive total

    // Land a remapped resume slightly inside content so it can never land exactly
    // at the end, which would itself end the item and autoplay-advance (the #147
    // failure mode on leanback).
    private static final long RESUME_END_MARGIN_MS = 5_000L;

    /** Clear the mapping table. Called on every passthrough / fail-open / live path. */
    private static void clearTimelineMap() {
        removedAdStartsMs = new long[0];
        removedAdDursMs = new long[0];
        strippedDurationMs = 0;
        originalDurationMs = 0;
    }

    /** True once a successful strip has populated a usable mapping for the playing title. */
    public static boolean hasTimelineMap() {
        return removedAdStartsMs.length > 0 && strippedDurationMs > 0;
    }

    /** The content-only duration of the playing title, or 0 if nothing was stripped. */
    public static long getStrippedDurationMs() {
        return strippedDurationMs;
    }

    /** The ad-inclusive duration the app's own metadata reports, or 0 if unknown. */
    public static long getOriginalDurationMs() {
        return originalDurationMs;
    }

    /**
     * Corrects the content duration the UI scales its seekbar with (issue #300).
     *
     * <p>Called from injected smali at the returns of AVIA's
     * {@code AviaResourceProvider.getContentDuration()} implementations. The DAI provider
     * computes that value as {@code getAbsoluteDuration() - sum(adPodList durations)} —
     * ad time taken off the player's duration. After the strip the player's duration
     * already excludes the ads, while the provider's ad-pod list is still populated from
     * the stitcher metadata, so the same ad time is deducted twice and the bar ends up
     * shorter than the content really is. Playback then runs off the end of the bar.
     *
     * <p>Returns the duration the strip itself measured, which is the authoritative
     * content-only length. Identity when no strip is live (live/linear, a passthrough
     * manifest, any fail-open), so unstripped playback keeps the app's own value.
     *
     * <p>The log line deliberately reports BOTH numbers: what the provider claimed and
     * what the strip measured. The difference is the double-deduction, so a single
     * on-device log states the size of the bug rather than leaving it to be inferred.
     */
    /**
     * Empty the stitcher ad-break list (issue #300). Called from injected smali at the
     * entry of the AdsData constructor, which is the single choke point every instance
     * passes through — the v17 equivalent of the leanback patch emptying
     * {@code StitcherSession.getAdBreaks()}.
     *
     * <p>After the strip there are no ads in the media, so a populated break list is
     * stale: it still draws markers, still drives the ad-state machine, and still reports
     * beacons for breaks that no longer exist. Returns an empty list once a strip is live,
     * and the caller's own list otherwise, so live/linear and passthrough titles keep the
     * app's own data.
     */
    /**
     * Empty {@code StitcherSession.adBreaks} (issue #300). Called from injected smali at
     * the entry of the StitcherSession constructor, for its adBreaks parameter.
     *
     * <p>This is the real leanback Hook 4 target. The leanback patch emptied
     * {@code tv.pluto.library.playercommon.data.StitcherSession.getAdBreaks()}, and that
     * class still exists in v17 — R8 renames it (observed as {@code v1h} in 17.2.0) but
     * its {@code toString()} still spells {@code "StitcherSession(id="}, and the field is
     * still the ad-break list the timebar's position source reads.
     *
     * <p>Earlier builds emptied {@code AdsData.adBreaks} and zeroed
     * {@code ThumbnailTemplate.adBreaksDurationInSec}. Both verifiably took effect on
     * device and neither moved the seekbar, because neither is what the bar is computed
     * from. This one is.
     *
     * <p>The field is declared {@code ArrayList}, not {@code List}, so a fresh
     * {@code ArrayList} is returned rather than {@link Collections#emptyList()} — an
     * immutable {@code List} would be the wrong type for the field and for any consumer
     * that mutates it.
     */
    public static ArrayList<?> neutralizeStitcherAdBreaks(ArrayList<?> original) {
        try {
            int was = original == null ? -1 : original.size();
            if (was > 0) {
                Log.i(TAG, "v17 StitcherSession.adBreaks neutralised (#300): "
                        + was + " break(s) -> 0");
            }
            return new ArrayList<>();
        } catch (Throwable t) {
            Log.e(TAG, "v17 StitcherSession.adBreaks neutralise failed -> original: "
                    + t.getMessage());
            return original;
        }
    }

    public static List<?> neutralizeAdBreaks(List<?> original) {
        try {
            // UNCONDITIONAL — deliberately not gated on hasTimelineMap().
            //
            // These metadata objects are constructed when the stitcher/session response
            // is mapped, which happens BEFORE AviaDashParser.parse runs, so at that
            // moment no strip map exists yet. A map-gated version was measured on
            // 17.2.0 doing nothing useful: the instances the UI had already consumed
            // kept their original values, and only later instances were neutralised
            // (device log: "adBreaksDuration zeroed: was 744 -> 0" many times, while the
            // bar stayed at strippedDuration - 744).
            //
            // The leanback patch had no such condition either: Hook 4 returned
            // emptyList() from StitcherSession.getAdBreaks() on every call, which is
            // why 5.66 never showed this bug. This matches that behaviour.
            int was = original == null ? -1 : original.size();
            if (was > 0) {
                Log.i(TAG, "v17 adBreaks neutralised (#300): " + was + " break(s) -> 0");
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            Log.e(TAG, "v17 adBreaks neutralise failed -> original: " + t.getMessage());
            return original;
        }
    }

    /**
     * Zero the pre-summed ad-break total the UI subtracts from the content duration
     * (issue #300). Called from injected smali at the entry of the ThumbnailTemplate
     * constructor, for its {@code adBreaksDurationInSec} parameter.
     *
     * <p>App code computes the seekbar's length as
     * {@code contentDuration - adBreaksDurationInSec}. After the strip the content
     * duration already excludes the ads, so subtracting the total again shortens the bar
     * by the whole ad load: measured on 17.2.0 as a 29:23 bar on a 42:04 episode, with
     * playback running past the bar's end. Returning 0 makes that subtraction a no-op and
     * the bar truthful. Identity when no strip is live.
     */
    public static long zeroAdBreakDuration(long originalValue) {
        try {
            // UNCONDITIONAL, for the ordering reason documented on neutralizeAdBreaks:
            // the object carrying this total is built before the manifest is parsed, so
            // a map-gated version arrives too late to affect the value the seekbar uses.
            //
            // Trade-off, stated explicitly: if a manifest ever fails open and is passed
            // through UNSTRIPPED, the ads are still in the media but this total reads 0,
            // so the bar will be LONGER than the content by the ad time instead of
            // shorter. That is the better failure of the two — playback ending before
            // the bar's end is untidy, whereas playback running past it drove the early
            // end-of-content transition and the stuck ad overlay with a locked timeline.
            if (originalValue == 0L) return 0L;
            Log.i(TAG, "v17 adBreaksDuration zeroed (#300): was " + originalValue
                    + " -> 0 (strippedDur=" + strippedDurationMs + "ms)");
            return 0L;
        } catch (Throwable t) {
            Log.e(TAG, "v17 adBreaksDuration zero failed -> original: " + t.getMessage());
            return originalValue;
        }
    }

    public static long correctContentDuration(long providerDurationMs) {
        try {
            if (!hasTimelineMap() || strippedDurationMs <= 0) {
                // Logged too: "no map" is a real state worth seeing (live/linear, a
                // passthrough manifest, or this getter running before the manifest was
                // parsed). Previously this path was silent, which made "the hook never
                // injected" look identical to "the hook ran and found nothing wrong".
                Log.i(TAG, "v17 duration passthrough (#300): provider reported "
                        + providerDurationMs + "ms, no strip map live -> unchanged");
                return providerDurationMs;
            }

            Log.i(TAG, "v17 duration fix (#300): provider reported " + providerDurationMs
                    + "ms, strip measured " + strippedDurationMs + "ms (original "
                    + originalDurationMs + "ms) -> using " + strippedDurationMs
                    + " (delta " + (strippedDurationMs - providerDurationMs) + "ms)");
            return strippedDurationMs;
        } catch (Throwable t) {
            Log.e(TAG, "v17 duration fix failed -> provider value: " + t.getMessage());
            return providerDurationMs;
        }
    }

    /**
     * ORIGINAL -> STRIPPED. Converts an ad-inclusive position (a restored "Continue
     * watching" bookmark, or a seek the UI computed against the original duration)
     * into the player's content-only coordinates, by subtracting the ad time removed
     * at or before it.
     *
     * <p>Clamped just inside the content end so a bookmark past the shortened runtime
     * resumes INTO the show rather than seeking past the end (which lands in
     * STATE_ENDED and autoplay-advances). Identity when no mapping is live, so an
     * unstripped title is never disturbed.
     */
    public static long toStrippedMs(long originalMs) {
        try {
            if (originalMs <= 0 || !hasTimelineMap()) return originalMs;

            long[] starts = removedAdStartsMs;
            long[] durs = removedAdDursMs;
            long removedBefore = 0;
            for (int i = 0; i < starts.length && i < durs.length; i++) {
                if (starts[i] + durs[i] <= originalMs) {
                    // Whole ad span is behind the position -> all of it was removed.
                    removedBefore += durs[i];
                } else if (starts[i] < originalMs) {
                    // The position falls INSIDE this removed ad. On the stripped clock
                    // that instant no longer exists, so subtract only the part of the
                    // span before it: the result is the span's start, i.e. exactly where
                    // content resumes. Subtracting the FULL span here (what leanback's
                    // mapResumePosition does) would map the position BEHIND the content
                    // that precedes the ad — a backwards jump of up to one ad pod. That
                    // never showed on leanback because the mapper only ran on bookmarks
                    // past the end; applying it to every position exposes it.
                    removedBefore += originalMs - starts[i];
                    break;
                } else {
                    break; // ascending starts — nothing further is behind the position
                }
            }
            long mapped = originalMs - removedBefore;

            long maxPos = strippedDurationMs - RESUME_END_MARGIN_MS;
            if (maxPos < 0) maxPos = 0;
            if (mapped > maxPos) mapped = maxPos;
            if (mapped < 0) mapped = 0;

            Log.i(TAG, "v17 map O->S (#300): " + originalMs + " - " + removedBefore
                    + " -> " + mapped + " (strippedDur=" + strippedDurationMs + ")");
            return mapped;
        } catch (Throwable t) {
            Log.e(TAG, "v17 map O->S failed -> identity: " + t.getMessage());
            return originalMs;
        }
    }

    /**
     * STRIPPED -> ORIGINAL. Converts a player position back into ad-inclusive
     * coordinates, by adding back the ad time that was removed before it. This is the
     * direction leanback never needed: it is what makes a saved bookmark meaningful to
     * Pluto's own "Continue watching" (which stores original coordinates), and what
     * lets progress be reported against the duration the UI is drawing.
     *
     * <p>A removed span's position in STRIPPED coordinates is its original start minus
     * the ad time removed before it, so the spans are walked in order accumulating the
     * removals that fall at or before the given position. Identity when no mapping is
     * live.
     */
    public static long toOriginalMs(long strippedMs) {
        try {
            if (strippedMs <= 0 || !hasTimelineMap()) return strippedMs;

            long[] starts = removedAdStartsMs;
            long[] durs = removedAdDursMs;
            long removedBefore = 0;
            for (int i = 0; i < starts.length && i < durs.length; i++) {
                // Where this removed span sits on the stripped clock.
                long spanPosStripped = starts[i] - removedBefore;
                if (spanPosStripped <= strippedMs) removedBefore += durs[i];
                else break; // ascending starts
            }
            long mapped = strippedMs + removedBefore;

            // Never report past the title's own advertised length.
            if (originalDurationMs > 0 && mapped > originalDurationMs) mapped = originalDurationMs;

            Log.i(TAG, "v17 map S->O (#300): " + strippedMs + " + " + removedBefore
                    + " -> " + mapped + " (originalDur=" + originalDurationMs + ")");
            return mapped;
        } catch (Throwable t) {
            Log.e(TAG, "v17 map S->O failed -> identity: " + t.getMessage());
            return strippedMs;
        }
    }

    private static String fmtPt(double seconds) {
        return "PT" + String.format(Locale.US, "%.3f", seconds) + "S";
    }

    private static double parsePt(String s) {
        if (s == null) return -1.0;
        Matcher m = PT.matcher(s.trim());
        if (!m.matches()) return -1.0;
        double d = 0.0;
        if (m.group(1) != null) d += Double.parseDouble(m.group(1)) * 3600.0;
        if (m.group(2) != null) d += Double.parseDouble(m.group(2)) * 60.0;
        if (m.group(3) != null) d += Double.parseDouble(m.group(3));
        return d;
    }

    private static boolean isAdUrl(String u) {
        if (u == null) return false;
        // SSAI ad/bumper creatives: /..._ad/creative... (URL-encoded _ad%2Fcreative) or _ad_bumper.
        if (u.contains("_ad%2Fcreative") || u.contains("_ad/creative") || u.contains("_ad_bumper")) {
            return true;
        }
        // Issue #298 (device-captured on "IF (2024)", 17.2.0). Two shapes the tokens above miss:
        //   p(prd%2Fcreative%2F59_ad%2F<id>_ad%2F720p...)   the creative path with the "_ad"
        //       directory AFTER "creative" instead of before it (a 30s spot played through)
        //   p(757_Pluto_TV_OandO%2Fclip%2F<id>_ptv_10169_kids_bumper_english_girl_vo_r3%2F...)
        //       a 5s channel-ident bumper on EACH side of every break ("You're watching ..."),
        //       named "<genre>_bumper" rather than "_ad_bumper"
        // "creative" is matched as a whole path segment and content clips are addressed by
        // hex id + uuid (never by a title slug), so neither token can match a content period.
        if (u.contains("%2Fcreative%2F") || u.contains("%2fcreative%2f") || u.contains("/creative/")
                || u.contains("_bumper")) {
            return true;
        }
        // Click-tracked ad clips (the SECOND VOD ad class on v17): short /mp4/v1/...video_NNN.mp4
        // periods tagged id3(p=clik,id=...). Device-confirmed to carry political/gambling ads that
        // the creative/bumper tokens above miss. Content periods use /clip/ (note: "clip", not
        // "clik") + /dash/ segment addressing, so this token never false-matches content.
        return u.contains("id3(p=clik");
    }

    /**
     * One compact line per period: verdict, original start, duration and a trimmed first
     * BaseURL. Runs once per title load, so it is not hot, and it is what makes a period
     * the classifier does not recognise visible in a user's logcat (issue #298).
     */
    private static void logPeriod(int index, String verdict, double start, double dur, Element period) {
        try {
            NodeList b = period.getElementsByTagName("BaseURL");
            String u = b.getLength() == 0 ? "(none)" : String.valueOf(b.item(0).getTextContent()).trim();
            if (u.length() > 300) u = u.substring(0, 300) + "...";
            Log.i(TAG, "v17 period[" + index + "] " + verdict + " start="
                    + String.format(Locale.US, "%.1f", start) + " dur="
                    + String.format(Locale.US, "%.1f", dur) + "s base=" + u);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The clip a period plays, or {@code null} if its BaseURL has no {@code /clip/<id>/}
     * segment. Every range of one title's content is cut from the same clip
     * ({@code .../clip/<hexId>_<uuid>/1080pDRM/<stamp>/<fromMs>-<toMs>/dash/}), so this is
     * what tells the title's own periods apart from anything spliced in between them.
     * Wrapped URLs carry the path percent-encoded inside {@code p(...)}, so the separators
     * are decoded first.
     */
    static String clipKey(String baseUrl) {
        if (baseUrl == null) return null;
        String u = baseUrl.replace("%2F", "/").replace("%2f", "/");
        int at = u.indexOf("/clip/");
        if (at < 0) return null;
        int from = at + "/clip/".length();
        int to = u.indexOf('/', from);
        String key = to < 0 ? u.substring(from) : u.substring(from, to);
        return key.isEmpty() ? null : key;
    }

    private static String clipKey(Element period) {
        NodeList baseUrls = period.getElementsByTagName("BaseURL");
        for (int i = 0; i < baseUrls.getLength(); i++) {
            String key = clipKey(baseUrls.item(i).getTextContent());
            if (key != null) return key;
        }
        return null;
    }

    /**
     * Structural backstop for ad flavours no token recognises yet (issue #298).
     *
     * <p>The URL tokens in {@link #isAdUrl} are a blacklist, and every new creative path or
     * bumper name Pluto introduces leaks through it until someone reports it (#284, #298).
     * This marks the remaining break filler by what it is NOT: a short period that does not
     * play the title's own clip.
     *
     * <p>Deliberately narrow, because issue #144 was caused by a classifier that dropped
     * content it did not recognise. It only applies when
     * <ul>
     *   <li>one clip accounts for at least {@link #MAIN_CLIP_MIN_FRACTION} of the manifest,
     *       so the title's own clip is unambiguous (otherwise nothing extra is removed), and</li>
     *   <li>the period is no longer than {@link #FILLER_MAX_SECONDS}, so a long period of
     *       any shape is always kept.</li>
     * </ul>
     * A title built from several clips, or one whose content has no {@code /clip/} path,
     * never meets the first condition and is classified by the tokens alone, exactly as
     * before. The {@link #MIN_KEPT_FRACTION} guard still backstops the whole strip.
     *
     * @return per-period flags, {@code true} where the period is break filler
     */
    static boolean[] findBreakFiller(String[] keys, double[] durs, boolean[] tokenAd) {
        int size = keys.length;
        boolean[] filler = new boolean[size];
        double total = 0.0;
        String mainKey = null;
        double mainDur = 0.0;
        for (int i = 0; i < size; i++) {
            total += durs[i];
            if (tokenAd[i] || keys[i] == null) continue;
            double sum = 0.0;
            for (int j = 0; j < size; j++) {
                if (!tokenAd[j] && keys[i].equals(keys[j])) sum += durs[j];
            }
            if (sum > mainDur) {
                mainDur = sum;
                mainKey = keys[i];
            }
        }
        if (mainKey == null || total <= 0.0 || mainDur < MAIN_CLIP_MIN_FRACTION * total) {
            return filler; // no dominant clip -> tokens only
        }
        for (int i = 0; i < size; i++) {
            if (tokenAd[i]) continue;
            if (durs[i] > 0.0 && durs[i] <= FILLER_MAX_SECONDS && !mainKey.equals(keys[i])) {
                filler[i] = true;
            }
        }
        return filler;
    }

    /** A period is an AD iff any of its BaseURLs carries the ad-creative/bumper signature. */
    private static boolean isAdPeriod(Element period) {
        NodeList baseUrls = period.getElementsByTagName("BaseURL");
        for (int i = 0; i < baseUrls.getLength(); i++) {
            if (isAdUrl(baseUrls.item(i).getTextContent())) return true;
        }
        return false;
    }

    /**
     * Remove ID3 emsg {@code <InbandEventStream>} (scheme {@code aomedia.org/emsg/ID3},
     * value {@code 999}) from a kept content period, so no stale ad-beacon ID3 can retrigger
     * the CLID3 CSAI ad path on content. Returns the number removed.
     */
    private static int removeId3Streams(Element period) {
        NodeList list = period.getElementsByTagName("InbandEventStream");
        List<Element> toRemove = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if ("https://aomedia.org/emsg/ID3".equals(e.getAttribute("schemeIdUri"))
                        && "999".equals(e.getAttribute("value"))) {
                    toRemove.add(e);
                }
            }
        }
        for (Element e : toRemove) e.getParentNode().removeChild(e);
        return toRemove.size();
    }

    /**
     * Called from injected smali at the entry of {@code AviaDashParser.parse(Uri, InputStream)}
     * (Hook 3). Reads the manifest stream, strips ad periods, and returns a cleaned stream for
     * the renamed parser; fail-open returns the original bytes (or the original stream).
     */
    public static InputStream cleanManifestStream(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, in.available()));
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            byte[] raw = bos.toByteArray();
            try {
                String mpd = new String(raw, StandardCharsets.UTF_8);
                if (mpd.contains(DASH_IDENTIFIER)) {
                    // static (VOD) -> enable Hook-4 CSAI suppression; dynamic (live) -> leave it on.
                    suppressDynamicAdFetch = !mpd.contains("type=\"dynamic\"");
                }
                String cleaned = stripAdPeriodsXml(mpd);
                if (cleaned != null) {
                    return new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                Log.e(TAG, "v17 strip failed -> original manifest: " + t.getMessage());
            }
            // fail-open: hand back the original (buffered) bytes so the fetch is never consumed/lost.
            return new ByteArrayInputStream(raw);
        } catch (Throwable t) {
            // The strip never ran, so any map still loaded belongs to a previous title —
            // drop it rather than let it be applied to this one (#300).
            clearTimelineMap();
            Log.e(TAG, "v17 read failed -> original stream: " + t.getMessage());
            return in;
        }
    }

    /**
     * Strip ad periods from a DASH MPD and re-base the remaining content periods into a
     * contiguous timeline. Returns the cleaned XML, or {@code null} to pass the original
     * through unchanged (not a DASH MPD, no ad markers, dynamic/live, nothing removed,
     * nothing kept, over-strip guard, or any error).
     */
    static String stripAdPeriodsXml(String mpd) {
        try {
            // Reset the coordinate map up front (issue #300): only a SUCCESSFUL strip
            // below repopulates it, so every `return null` passthrough and every
            // exception path leaves it cleared and both mappers stay identity.
            clearTimelineMap();

            if (mpd == null || mpd.isEmpty() || !mpd.contains(DASH_IDENTIFIER)) return null;
            // Live/linear manifests refresh every few seconds; skip them before the DOM
            // parse. (The parsed-root check below stays as the authoritative guard.)
            if (mpd.contains("type=\"dynamic\"")) return null;
            // No token pre-check any more (issue #298): a manifest whose only ad periods are
            // of a flavour no token recognises still has to reach the structural backstop.

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            if (root == null) return null;

            // LIVE guard: dynamic MPDs are live/linear (moving edge, wall-clock timeline);
            // re-basing period starts there corrupts playback, and live ads are real broadcast
            // time that was never removable. Pass through untouched.
            if ("dynamic".equalsIgnoreCase(root.getAttribute("type"))) {
                Log.i(TAG, "v17 dynamic (live) MPD -> untouched");
                return null;
            }

            double totalDur = parsePt(root.getAttribute("mediaPresentationDuration"));

            // Snapshot the periods (we mutate the tree, so don't iterate the live NodeList).
            NodeList periodNodes = root.getElementsByTagName("Period");
            List<Element> periods = new ArrayList<>();
            for (int i = 0; i < periodNodes.getLength(); i++) {
                Node n = periodNodes.item(i);
                if (n instanceof Element) periods.add((Element) n);
            }
            int size = periods.size();
            if (size == 0) return null;

            // Original (ad-inclusive) start of each period, for duration-by-difference below.
            double[] starts = new double[size];
            for (int i = 0; i < size; i++) starts[i] = parsePt(periods.get(i).getAttribute("start"));

            // Duration of each period: explicit @duration, else next.start - this.start, else
            // mediaPresentationDuration - this.start (for the last). Computed from ORIGINAL
            // starts before any removal, so content durations stay correct across removed ads.
            double[] durs = new double[size];
            int indeterminate = 0;
            for (int i = 0; i < size; i++) {
                double d = parsePt(periods.get(i).getAttribute("duration"));
                if (d < 0.0) {
                    double next = (i + 1 < size) ? starts[i + 1] : totalDur;
                    if (next >= 0.0 && starts[i] >= 0.0) d = next - starts[i];
                }
                // A period whose duration cannot be determined contributes ZERO to the
                // re-based timeline while still playing, so the declared duration would
                // under-report the real content length and the following period would be
                // given an overlapping start. Count and report them: that would show up
                // as content running past the end of the seekbar, so it must not be
                // confused with the ad-time double deduction this patch corrects.
                if (d <= 0.0) indeterminate++;
                durs[i] = d > 0.0 ? d : 0.0;
            }
            if (indeterminate > 0) {
                Log.w(TAG, "v17 strip: " + indeterminate + " of " + size
                        + " periods had no determinable duration (counted as 0) -"
                        + " declared duration may under-report the real content length");
            }

            // Classify every period up front: by URL token first, then the structural
            // backstop for short periods that do not play the title's own clip (#298).
            boolean[] tokenAd = new boolean[size];
            String[] keys = new String[size];
            for (int i = 0; i < size; i++) {
                tokenAd[i] = isAdPeriod(periods.get(i));
                keys[i] = clipKey(periods.get(i));
            }
            boolean[] filler = findBreakFiller(keys, durs, tokenAd);

            double cursor = 0.0; // re-based content timeline
            int kept = 0, removed = 0, id3Removed = 0, fillerRemoved = 0;
            // Removed ad spans in ORIGINAL coordinates, for the #300 timeline map. Collected
            // in period order, so starts stay ascending as both mappers assume.
            List<long[]> removedSpans = new ArrayList<>(); // {originalStartMs, durationMs}
            for (int i = 0; i < size; i++) {
                Element p = periods.get(i);
                boolean adPeriod = tokenAd[i] || filler[i];
                if (filler[i]) fillerRemoved++;
                logPeriod(i, tokenAd[i] ? "AD  " : filler[i] ? "FILL" : "KEEP", starts[i], durs[i], p);
                if (adPeriod) {
                    // AD: remove and DO NOT advance the content cursor. This is the freeze fix —
                    // ad time must never be counted into the kept-content timeline, otherwise the
                    // following content periods start too late and leave a seekbar-clamping gap.
                    // starts[i] is the ORIGINAL (ad-inclusive) start; record it before removal so
                    // positions can be translated between the two clocks (#300).
                    if (starts[i] >= 0.0 && durs[i] > 0.0) {
                        removedSpans.add(new long[] {
                                (long) (starts[i] * 1000.0),
                                (long) (durs[i] * 1000.0),
                        });
                    }
                    p.getParentNode().removeChild(p);
                    removed++;
                    continue;
                }
                // CONTENT: re-base start to run immediately after the previous kept period.
                p.setAttribute("start", fmtPt(cursor));
                cursor += durs[i];
                kept++;
                id3Removed += removeId3Streams(p);
            }

            if (removed == 0) return null; // no ad periods actually matched -> passthrough
            if (kept == 0) {
                Log.w(TAG, "v17 strip: no content periods kept (of " + size + ") -> passthrough");
                return null;
            }
            if (totalDur > 0.0 && cursor < MIN_KEPT_FRACTION * totalDur) {
                Log.w(TAG, "v17 GUARD tripped: kept " + kept + "/" + size + " -> "
                        + cursor + "s of " + totalDur + "s (<40%) -> passthrough");
                return null;
            }

            if (totalDur > 0.0) root.setAttribute("mediaPresentationDuration", fmtPt(cursor));

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            String out = sw.toString();

            // Publish the coordinate map for this title (#300) only now — past every
            // passthrough guard AND past the serialization, so the table can never
            // describe a timeline the caller did not actually receive.
            long[] mapStarts = new long[removedSpans.size()];
            long[] mapDurs = new long[removedSpans.size()];
            for (int i = 0; i < removedSpans.size(); i++) {
                mapStarts[i] = removedSpans.get(i)[0];
                mapDurs[i] = removedSpans.get(i)[1];
            }
            removedAdStartsMs = mapStarts;
            removedAdDursMs = mapDurs;
            strippedDurationMs = (long) (cursor * 1000.0);
            originalDurationMs = totalDur > 0.0 ? (long) (totalDur * 1000.0) : 0L;

            Log.i(TAG, "v17 STRIPPED " + removed + " ad periods (" + fillerRemoved
                    + " by structure), kept " + kept
                    + ", removedId3=" + id3Removed + ", newDur=" + cursor + "s (was " + totalDur + "s)"
                    + ", map=" + mapStarts.length + " spans");
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "v17 strip failed -> original manifest: " + t.getMessage());
            return null;
        }
    }
}
