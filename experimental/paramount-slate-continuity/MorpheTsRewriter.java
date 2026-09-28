package com.paramount.morphe;

/** Shifts all PTS/DTS/PCR in an MPEG-TS black-fill segment so it aligns onto the
 *  live timeline position of the real ad segment it replaces. Pure byte math,
 *  no allocations beyond the returned clone. Transcribed from a validated reference. */
public final class MorpheTsRewriter {

    /** Live-slate smoothing entry point (Patch 3, choppiness fix).
     *  The shipped fix redirected each ad-pod segment to the branded "Commercial in
     *  Progress" slate rendition, but that slate is a short shared asset carrying its
     *  OWN base PTS timeline — so each stitched slate segment (and every 0..5 loop wrap)
     *  is a discontinuity on the live edge → ExoPlayer's live adjuster jumps/skips
     *  ("choppy"). Here we instead fetch the slate media out-of-band and PTS-align it
     *  onto the real ad slot's live-timeline position (the ground truth for where the
     *  break sits AND where content resumes), so the slate plays smooth AND recovers.
     *  realBytes = the real ad media the chain already fetched (its live PTS is the target).
     *  On any failure returns realBytes so the player is never starved (never worse than
     *  the raw stream). */
    /** slateBytes = the branded slate media the chain already fetched (always shown, exactly
     *  like the shipped v1.27.0 redirect). realUrl = the ORIGINAL ad-pod URL; we range-fetch
     *  just its head out-of-band to read the real ad's first live-edge PTS and shift the slate
     *  onto that timeline slot (kills per-segment jumps + loop-wrap resets). If the reference
     *  fetch or parse fails we return the RAW slate unchanged — i.e. never worse than v1.27.0. */
    /** In-memory cache of raw slate segment media, keyed by slate URL path (query stripped).
     *  A live break re-serves the SAME slate segments (0..5, identical hash) for every ad pod,
     *  so after the first pod every fetch is a cache hit — this eliminates the repeated
     *  multi-MB synchronous fetch that stalled the player (the residual "freeze"). align()
     *  clones the template internally, so re-aligning a cached buffer never mutates it. */
    private static final java.util.HashMap<String, byte[]> SLATE_CACHE = new java.util.HashMap<String, byte[]>();

    /** Continuous-slate counter. DAI delivers a break as several short ad POComponents, each a
     *  separate period whose segment numbering restarts at 0. If we served slate segment N (the
     *  pod-local index) we'd replay slate/0.ts at every pod → the branded card visibly restarts
     *  (video + music) every ~10-15s. Instead we advance a global counter and serve
     *  slate/(counter % SLATE_LEN).ts, so the card's content flows continuously across pod
     *  boundaries (it still PTS-aligns to each pod's real live-edge timeline, so within-pod
     *  smoothness + clean recovery are unchanged). Only video .ts segments advance the counter. */
    private static final Object SLATE_COUNTER_LOCK = new Object();
    private static int SLATE_COUNTER = 0;
    private static final int SLATE_LEN = 6; // slate rendition exposes segments 0..5 (~30s)

    /** End-to-end lay-down timeline. ROOT CAUSE of the residual jitter/freeze: slate segments
     *  are ~6.006s each (540541 ticks) but the real ad slots are ~5.005s each (450451 ticks).
     *  Aligning every slate segment's FIRST PTS onto the real slot's PTS crammed a 6s segment
     *  into a 5s slot → ~1s overlap per segment → "frame earlier than expected" storm →
     *  decoder reset (~2.6s) every segment = jitter + repeating freeze. Instead we lay the
     *  slate segments end-to-end at their OWN natural duration on a single running output
     *  timeline (OUT_PTS), so consecutive slate segments never overlap. We anchor OUT_PTS to
     *  the live edge only at the START of a break (detected by a >ANCHOR_GAP_MS gap since the
     *  last ad segment — i.e. content was playing in between); within a break OUT_PTS only
     *  advances, so the whole slate is monotonic = smooth. Content recovery is unaffected: the
     *  game segments pass through untouched with their own PTS. */
    private static final Object ANCHOR_LOCK = new Object();
    private static long OUT_PTS = -1;        // next output first-PTS to place a slate segment at
    private static long LAST_SEG_WALL = 0;   // wall-clock ms of the previous ad segment served
    private static final long ANCHOR_GAP_MS = 20000; // >20s since last ad seg ⇒ new break ⇒ re-anchor
    private static final long PTS_MASK = 0x1FFFFFFFFL;
    private static final long SLATE_DUR_FALLBACK = 450451; // ~5.005s if a segment's span can't be read
    private static final long REANCHOR_TICKS = 450000;     // >~5s real-vs-timeline drift ⇒ new pod/break ⇒ re-anchor

    /** Deterministic ad timeline: realPts = POD_BASE + N*SEG_TICKS (N = trailing segment index in
     *  the ad URL). We fetch the real ad head only ONCE per pod (to anchor POD_BASE exactly) and
     *  compute every other segment's target — eliminating the synchronous per-segment network fetch
     *  that piled up (3 rapid fetches) and stalled each pod boundary. */
    private static final long SEG_TICKS = 450451;          // ~5.005s per ad segment (90kHz ticks)
    private static String LAST_POD_KEY = "";
    private static long POD_BASE = -1;

    // EXPERIMENT (manifest-continuity): monotonic slate timeline across the WHOLE break, using the
    // existing OUT_PTS/LAST_SEG_WALL/ANCHOR_GAP_MS fields — advance OUT_PTS by SEG_TICKS every
    // segment (ignoring per-pod realPts resets) so the break is ONE continuous timeline, paired with
    // stripManifest() removing EXT-X-DISCONTINUITY so the player accepts it (like the smooth
    // "event has ended" content stream). Re-anchor only at break start (>ANCHOR_GAP_MS wall gap).

    /** realUrl = the ORIGINAL ad-pod segment URL (its first video PTS is the live edge at break
     *  start); slateUrl = the branded-slate rendition URL. We fetch the slate MEDIA out-of-band
     *  (the chain only sees the 302 redirect, whose body is empty — the v1.29.2/slate6 no-op bug),
     *  cache it, remap the trailing index so the card advances (doesn't restart per pod), then
     *  place it on the running OUT_PTS timeline. Returns null on slate-fetch failure so the caller
     *  keeps the raw 302 (never worse than the shipped redirect). */
    public static byte[] alignSlateToLive(String realUrl, String slateUrl) {
        try {
            // Continuous-slate: remap a video .ts segment's trailing index to a globally-advancing
            // one so the card's content flows instead of restarting at each ad pod.
            {
                int qcut = slateUrl.indexOf('?');
                String path = qcut >= 0 ? slateUrl.substring(0, qcut) : slateUrl;
                String query = qcut >= 0 ? slateUrl.substring(qcut) : "";
                if (path.endsWith(".ts")) {
                    int slash = path.lastIndexOf('/');
                    if (slash >= 0) {
                        int g;
                        synchronized (SLATE_COUNTER_LOCK) { g = SLATE_COUNTER++; }
                        int idx = g % SLATE_LEN;
                        slateUrl = path.substring(0, slash + 1) + idx + ".ts" + query;
                    }
                }
            }
            String key = slateUrl;
            int q = key.indexOf('?');
            if (q > 0) key = key.substring(0, q);
            byte[] slateMedia;
            synchronized (SLATE_CACHE) { slateMedia = SLATE_CACHE.get(key); }
            if (slateMedia == null) {
                slateMedia = httpGetRange(slateUrl, 16000000); // follow 302 -> FULL slate media (some segs >4MB; 4MB cap truncated them → decoder stall)
                if (slateMedia != null && slateMedia.length > 0) {
                    synchronized (SLATE_CACHE) {
                        if (SLATE_CACHE.size() > 64) SLATE_CACHE.clear();
                        SLATE_CACHE.put(key, slateMedia);
                    }
                }
            }
            if (slateMedia == null || slateMedia.length == 0) return null;

            // Video OR audio slate segment: read its first PES PTS so we can place either onto the
            // ad slot (aligning the audio too keeps A/V in sync — previously audio was passed raw).
            long slateFirst = firstAnyPts(slateMedia);
            if (slateFirst < 0) {
                android.util.Log.i("MorpheLiveAd", "SLATEALIGN2 raw-slate (no slate PTS) len=" + slateMedia.length);
                return slateMedia;
            }
            // EXPERIMENT (manifest-continuity): lay the slate on a MONOTONIC break-wide timeline —
            // advance OUT_PTS by SEG_TICKS (real-time rate) every segment, ignoring the per-pod
            // realPts resets. Re-anchor to the real live edge only at break start (>ANCHOR_GAP_MS
            // wall gap) so the hand-back to content stays close. Paired with stripManifest() removing
            // EXT-X-DISCONTINUITY so the player treats the whole break as one continuous period.
            long target;
            long now = System.currentTimeMillis();
            synchronized (ANCHOR_LOCK) {
                boolean reanchor = (OUT_PTS < 0) || (now - LAST_SEG_WALL > ANCHOR_GAP_MS);
                if (reanchor) {
                    byte[] realHead = httpGetRange(realUrl, 131072);
                    long realPts = (realHead == null) ? -1 : firstAnyPts(realHead);
                    if (realPts < 0) return slateMedia;
                    OUT_PTS = realPts;
                }
                LAST_SEG_WALL = now;
                target = OUT_PTS;
                OUT_PTS = (OUT_PTS + SEG_TICKS) & PTS_MASK;
            }
            byte[] out = shiftToPts(slateMedia, slateFirst, target);
            android.util.Log.i("MorpheLiveAd", "SLATEALIGN2 slateLen=" + slateMedia.length
                + " target=" + target + " outLen=" + out.length);
            return out;
        } catch (Throwable t) {
            android.util.Log.i("MorpheLiveAd", "SLATEALIGN2 error " + t);
            return null;
        }
    }

    /** Video PES presentation span (lastPTS - firstPTS) in 90kHz ticks, or -1 if unreadable. */
    static long segSpanTicks(byte[] d) {
        long first = -1, last = -1;
        int n = d.length / 188;
        for (int i = 0; i < n; i++) {
            int b = i * 188;
            if ((d[b] & 0xff) != 0x47) continue;
            boolean pstart = (d[b + 1] & 0x40) != 0;
            int afc = (d[b + 3] >> 4) & 3;
            int off = 4;
            if (afc == 2 || afc == 3) { int al = d[b + 4] & 0xff; off = 5 + al; }
            if ((afc == 1 || afc == 3) && pstart && off + 9 <= 188
                && (d[b + off] & 0xff) == 0 && (d[b + off + 1] & 0xff) == 0 && (d[b + off + 2] & 0xff) == 1) {
                int sid = d[b + off + 3] & 0xff;
                if (sid >= 0xE0 && sid <= 0xEF) {
                    int pd = (d[b + off + 7] >> 6) & 3;
                    if ((pd & 2) != 0) { long p = rd33(d, b + off + 9); if (first < 0) first = p; last = p; }
                }
            }
        }
        return (first >= 0 && last >= first) ? (last - first) : -1;
    }

    /** EXPERIMENT: remove #EXT-X-DISCONTINUITY tags from an HLS media playlist so the player treats
     *  the ad pods + content as ONE continuous period (no per-pod decoder flush). No-op (returns the
     *  original bytes) when there's nothing to strip or on any error. */
    public static byte[] stripManifest(byte[] body) {
        try {
            if (body == null || body.length == 0) return body;
            String s = new String(body, "UTF-8");
            if (s.indexOf("#EXT-X-DISCONTINUITY") < 0) return body;
            int removed = 0;
            StringBuilder out = new StringBuilder(s.length());
            int i = 0, n = s.length();
            while (i < n) {
                int nl = s.indexOf('\n', i);
                if (nl < 0) nl = n;
                String line = s.substring(i, nl);
                if (!line.trim().equals("#EXT-X-DISCONTINUITY")) {
                    out.append(line);
                    if (nl < n) out.append('\n');
                } else {
                    removed++;
                }
                i = nl + 1;
            }
            android.util.Log.i("MorpheLiveAd", "STRIPMANIFEST removed=" + removed + " len=" + body.length);
            return out.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return body;
        }
    }

    /** Clone template and shift its whole PES/PCR timeline so its first video PTS == target. */
    static byte[] shiftToPts(byte[] template, long templateFirstPts, long target) {
        long delta = (target - templateFirstPts) & PTS_MASK;
        byte[] p = template.clone();
        shiftAll(p, delta);
        return p;
    }

    /** Range GET (bytes=0..n-1) that follows the DAI 302 to the real ad media on googlevideo,
     *  returning only the head — enough to read the first video PES PTS. HttpURLConnection so
     *  there is no okhttp compile-time dependency. */
    static byte[] httpGetRange(String u, int n) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Range", "bytes=0-" + (n - 1));
        java.io.InputStream in = c.getInputStream();
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.min(n, 1 << 20));
            byte[] buf = new byte[16384];
            int r, total = 0;
            while (total < n && (r = in.read(buf)) > 0) { bos.write(buf, 0, r); total += r; }
            return bos.toByteArray();
        } finally {
            in.close();
            c.disconnect();
        }
    }

    /** Return a copy of template shifted so its first video PTS == real segment's first video PTS.
     *  On any parse failure returns template unchanged (safe: identical to old behaviour). */
    public static byte[] align(byte[] template, byte[] real) {
        try {
            logStructure(real);
            long realPts = firstVideoPts(real);
            if (realPts < 0) {
                android.util.Log.i("MorpheLiveAd", "PTSFIX fallback: no real PTS (realLen=" + real.length + ")");
                return template;
            }
            long tmplPts = firstVideoPts(template);
            if (tmplPts < 0) return template;
            long delta = (realPts - tmplPts) & 0x1FFFFFFFFL;
            byte[] p = template.clone();
            shiftAll(p, delta);
            android.util.Log.i("MorpheLiveAd", "PTSFIX real=" + realPts + " tmpl=" + tmplPts
                + " delta=" + delta + " realLen=" + real.length);
            return p;
        } catch (Throwable t) {
            android.util.Log.i("MorpheLiveAd", "PTSFIX error " + t);
            return template;
        }
    }

    /** Diagnostic: log the real ad segment's container structure (muxed vs demuxed, true duration). */
    static void logStructure(byte[] d) {
        try {
            java.util.HashSet<Integer> pids = new java.util.HashSet<Integer>();
            boolean vid = false, aud = false;
            long firstV = -1, lastV = -1;
            int n = d.length / 188;
            for (int i = 0; i < n; i++) {
                int b = i * 188;
                if ((d[b] & 0xff) != 0x47) continue;
                pids.add(((d[b + 1] & 0x1f) << 8) | (d[b + 2] & 0xff));
                boolean ps = (d[b + 1] & 0x40) != 0;
                int afc = (d[b + 3] >> 4) & 3;
                int off = 4;
                if (afc == 2 || afc == 3) { int al = d[b + 4] & 0xff; off = 5 + al; }
                if ((afc == 1 || afc == 3) && ps && off + 9 <= 188
                    && (d[b + off] & 0xff) == 0 && (d[b + off + 1] & 0xff) == 0 && (d[b + off + 2] & 0xff) == 1) {
                    int sid = d[b + off + 3] & 0xff;
                    if (sid >= 0xE0 && sid <= 0xEF) {
                        vid = true;
                        int pd = (d[b + off + 7] >> 6) & 3;
                        if ((pd & 2) != 0) { long p = rd33(d, b + off + 9); if (firstV < 0) firstV = p; lastV = p; }
                    }
                    if (sid >= 0xC0 && sid <= 0xDF) aud = true;
                }
            }
            android.util.Log.i("MorpheLiveAd", "REALSTRUCT len=" + d.length + " pids=" + pids
                + " video=" + vid + " audio=" + aud + " firstV=" + firstV + " lastV=" + lastV
                + " spanS=" + (firstV >= 0 ? (lastV - firstV) / 90000.0 : -1));
        } catch (Throwable t) {}
    }

    /** First PES presentation PTS of a video (0xE0-0xEF) OR audio (0xC0-0xDF) elementary stream,
     *  in file order, or -1. Used so audio-only slate segments can be aligned too. */
    static long firstAnyPts(byte[] d) {
        int n = d.length / 188;
        for (int i = 0; i < n; i++) {
            int b = i * 188;
            if ((d[b] & 0xff) != 0x47) continue;
            boolean pstart = (d[b + 1] & 0x40) != 0;
            int afc = (d[b + 3] >> 4) & 3;
            int off = 4;
            if (afc == 2 || afc == 3) { int aflen = d[b + 4] & 0xff; off = 5 + aflen; }
            if ((afc == 1 || afc == 3) && pstart && off + 9 <= 188
                && (d[b + off] & 0xff) == 0 && (d[b + off + 1] & 0xff) == 0 && (d[b + off + 2] & 0xff) == 1) {
                int sid = d[b + off + 3] & 0xff;
                if ((sid >= 0xE0 && sid <= 0xEF) || (sid >= 0xC0 && sid <= 0xDF)) {
                    int ptsdts = (d[b + off + 7] >> 6) & 3;
                    if ((ptsdts & 2) != 0) return rd33(d, b + off + 9);
                }
            }
        }
        return -1;
    }

    static long firstVideoPts(byte[] d) {
        int n = d.length / 188;
        for (int i = 0; i < n; i++) {
            int b = i * 188;
            if ((d[b] & 0xff) != 0x47) continue;
            boolean pstart = (d[b + 1] & 0x40) != 0;
            int afc = (d[b + 3] >> 4) & 3;
            int off = 4;
            if (afc == 2 || afc == 3) { int aflen = d[b + 4] & 0xff; off = 5 + aflen; }
            if ((afc == 1 || afc == 3) && pstart) {
                if (off + 9 > 188) continue;
                if ((d[b + off] & 0xff) == 0 && (d[b + off + 1] & 0xff) == 0 && (d[b + off + 2] & 0xff) == 1) {
                    int sid = d[b + off + 3] & 0xff;
                    if (sid >= 0xE0 && sid <= 0xEF) {
                        int ptsdts = (d[b + off + 7] >> 6) & 3;
                        if ((ptsdts & 2) != 0) return rd33(d, b + off + 9);
                    }
                }
            }
        }
        return -1;
    }

    static void shiftAll(byte[] d, long delta) {
        int n = d.length / 188;
        for (int i = 0; i < n; i++) {
            int b = i * 188;
            if ((d[b] & 0xff) != 0x47) continue;
            boolean pstart = (d[b + 1] & 0x40) != 0;
            int afc = (d[b + 3] >> 4) & 3;
            int off = 4;
            if (afc == 2 || afc == 3) {
                int aflen = d[b + 4] & 0xff;
                if (aflen > 0 && (d[b + 5] & 0x10) != 0) {
                    long base = rdpcr(d, b + 6);
                    wrpcr(d, b + 6, (base + delta) & 0x1FFFFFFFFL);
                }
                off = 5 + aflen;
            }
            if ((afc == 1 || afc == 3) && pstart) {
                if (off + 9 > 188) continue;
                if ((d[b + off] & 0xff) == 0 && (d[b + off + 1] & 0xff) == 0 && (d[b + off + 2] & 0xff) == 1) {
                    int sid = d[b + off + 3] & 0xff;
                    if (sid == 0xBC || sid == 0xBE || sid == 0xBF || sid == 0xF0
                        || sid == 0xF1 || sid == 0xFF || sid == 0xF2 || sid == 0xF8) continue;
                    int ptsdts = (d[b + off + 7] >> 6) & 3;
                    int hdr = b + off + 9;
                    if ((ptsdts & 2) != 0) {
                        wr33(d, hdr, (rd33(d, hdr) + delta) & 0x1FFFFFFFFL, (d[hdr] >> 4) & 0xf);
                        if (ptsdts == 3)
                            wr33(d, hdr + 5, (rd33(d, hdr + 5) + delta) & 0x1FFFFFFFFL, (d[hdr + 5] >> 4) & 0xf);
                    }
                }
            }
        }
    }

    static long rd33(byte[] p, int o) {
        return (((long)(p[o] >> 1) & 7) << 30)
             | (((long)(p[o + 1] & 0xff)) << 22)
             | (((long)(p[o + 2] >> 1) & 0x7f) << 15)
             | (((long)(p[o + 3] & 0xff)) << 7)
             | ((long)(p[o + 4] >> 1) & 0x7f);
    }
    static void wr33(byte[] p, int o, long v, int prefix) {
        v &= 0x1FFFFFFFFL;
        p[o]     = (byte)((prefix << 4) | (((v >> 30) & 7) << 1) | 1);
        p[o + 1] = (byte)((v >> 22) & 0xff);
        p[o + 2] = (byte)(((( v >> 15) & 0x7f) << 1) | 1);
        p[o + 3] = (byte)((v >> 7) & 0xff);
        p[o + 4] = (byte)(((v & 0x7f) << 1) | 1);
    }
    static long rdpcr(byte[] p, int o) {
        return (((long)(p[o] & 0xff)) << 25)
             | (((long)(p[o + 1] & 0xff)) << 17)
             | (((long)(p[o + 2] & 0xff)) << 9)
             | (((long)(p[o + 3] & 0xff)) << 1)
             | ((long)(p[o + 4] >> 7) & 1);
    }
    static void wrpcr(byte[] p, int o, long base) {
        base &= 0x1FFFFFFFFL;
        p[o]     = (byte)((base >> 25) & 0xff);
        p[o + 1] = (byte)((base >> 17) & 0xff);
        p[o + 2] = (byte)((base >> 9) & 0xff);
        p[o + 3] = (byte)((base >> 1) & 0xff);
        p[o + 4] = (byte)(((base & 1) << 7) | (p[o + 4] & 0x7f));
    }
}
