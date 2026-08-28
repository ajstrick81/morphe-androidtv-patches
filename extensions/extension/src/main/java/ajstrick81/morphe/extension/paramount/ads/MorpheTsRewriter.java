package ajstrick81.morphe.extension.paramount.ads;

/**
 * Paramount+ LIVE sports "Commercial in Progress" slate smoother (ParamountPatch Patch 3).
 *
 * <p>Live sports ads are Google DAI pod-serving: each ad segment is a 302 redirect at
 * {@code dai.google.com/linear/pods/v1/.../<pod>/<slot>/<adIdx>/<hash>/N.ts|.aac}. The same pod
 * also exposes a branded {@code /<pod>/slate/0/<hash>/N...} rendition (Paramount's "Commercial in
 * Progress" card). The shipped fix rewrote the ad URL to the slate URL and let okhttp follow the
 * redirect — but the slate is a short shared asset carrying its OWN base PTS, so stitching those
 * segments onto the live edge made the card jump/stutter, and later builds either drifted (breaking
 * the hand-back to content) or froze.
 *
 * <p>This class is the smoothing engine, called from {@code AviaNetworkInterceptor.intercept()} via
 * {@link #alignSlateToLive(String, String)}:
 * <ol>
 *   <li>Fetches the slate MEDIA out-of-band (the network interceptor only sees the empty 302), so it
 *       can be PTS-rewritten before delivery. Caches it — a break re-serves the same 0..5 segments.</li>
 *   <li>Advances a global counter so the card's content flows continuously instead of restarting at
 *       each ad pod (video {@code .ts} only).</li>
 *   <li>PTS-aligns each slate segment onto the real ad slot's live-edge PTS. The ad timeline is
 *       deterministic ({@code podBase + N*SEG_TICKS}), so the real head is fetched only once per pod
 *       to anchor {@code podBase}; other segments are computed. This keeps the slate locked to the
 *       manifest's per-period timeline (no 6015/3002 freeze, exact hand-back to content).</li>
 * </ol>
 *
 * <p>On any fetch/parse failure it returns {@code null} (caller keeps the raw 302 — never worse than
 * the shipped redirect) or the unmodified slate. Uses only {@link java.net.HttpURLConnection} and
 * byte math — no okhttp compile-time dependency (the caller owns the okhttp Response construction).
 */
public final class MorpheTsRewriter {

    private MorpheTsRewriter() {}

    private static final long PTS_MASK = 0x1FFFFFFFFL;

    /** Raw slate segment media, keyed by slate URL path (query stripped). A break re-serves the same
     *  0..SLATE_LEN-1 segments for every pod, so after the first fetch each is a cache hit — removing
     *  the repeated multi-MB fetch that stalled the player. shiftToPts clones, so re-use never mutates. */
    private static final java.util.HashMap<String, byte[]> SLATE_CACHE = new java.util.HashMap<String, byte[]>();

    /** Continuous-slate counter: serve slate/(counter % SLATE_LEN).ts so the card advances across ad
     *  pods instead of restarting at 0 every ~10-15s. Video .ts segments only. */
    private static final Object SLATE_COUNTER_LOCK = new Object();
    private static int SLATE_COUNTER = 0;
    private static final int SLATE_LEN = 6; // slate rendition exposes segments 0..5 (~30s)

    /** Deterministic ad timeline: realPts = POD_BASE + N*SEG_TICKS (N = trailing segment index). The
     *  real head is fetched only once per pod to anchor POD_BASE; the rest are computed — eliminating
     *  the per-segment network fetch that piled up (3 rapid fetches) and stalled each pod boundary. */
    private static final long SEG_TICKS = 450451;   // ~5.005s per ad segment (90kHz ticks)
    private static final Object ANCHOR_LOCK = new Object();
    private static String LAST_POD_KEY = "";
    private static long POD_BASE = -1;

    /**
     * @param realUrl the ORIGINAL ad-pod segment URL (its live-edge PTS is the alignment target)
     * @param slateUrl the branded-slate rendition URL (already rewritten by the caller)
     * @return the PTS-aligned slate segment bytes to deliver as a 200 response, the raw slate when
     *         the PTS reference is unreadable, or {@code null} to signal the caller to keep the raw 302
     */
    public static byte[] alignSlateToLive(String realUrl, String slateUrl) {
        try {
            // Continuous-slate: remap a video .ts segment's trailing index to a globally-advancing one.
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
                // 16MB cap: some slate segments exceed 4MB; a smaller cap truncated them → decoder stall.
                slateMedia = httpGetRange(slateUrl, 16000000);
                if (slateMedia != null && slateMedia.length > 0) {
                    synchronized (SLATE_CACHE) {
                        if (SLATE_CACHE.size() > 64) SLATE_CACHE.clear();
                        SLATE_CACHE.put(key, slateMedia);
                    }
                }
            }
            if (slateMedia == null || slateMedia.length == 0) return null;

            // Read the slate segment's first PES PTS (video OR audio, so audio segments align too).
            long slateFirst = firstAnyPts(slateMedia);
            if (slateFirst < 0) return slateMedia;

            // Compute the target live-edge PTS from the URL; fetch the real head only once per pod.
            long target;
            {
                String rp = realUrl;
                int rq = rp.indexOf('?');
                if (rq >= 0) rp = rp.substring(0, rq);
                int rslash = rp.lastIndexOf('/');
                int rdot = rp.lastIndexOf('.');
                int segN = -1;
                String podKey = rp;
                if (rslash >= 0 && rdot > rslash) {
                    try { segN = Integer.parseInt(rp.substring(rslash + 1, rdot)); } catch (Throwable ig) {}
                    // podKey excludes the trailing <hash>/<N.ext> so the video and audio renditions of
                    // the SAME ad (different hashes) share one podBase → A/V aligned.
                    int rslash2 = (rslash > 0) ? rp.lastIndexOf('/', rslash - 1) : -1;
                    podKey = (rslash2 >= 0) ? rp.substring(0, rslash2 + 1) : rp.substring(0, rslash + 1);
                }
                synchronized (ANCHOR_LOCK) {
                    if (segN < 0 || !podKey.equals(LAST_POD_KEY) || POD_BASE < 0) {
                        byte[] realHead = httpGetRange(realUrl, 131072); // 128KB head: first PES PTS is early
                        long realPts = (realHead == null) ? -1 : firstAnyPts(realHead);
                        if (realPts < 0) return slateMedia;
                        POD_BASE = (segN >= 0) ? ((realPts - (long) segN * SEG_TICKS) & PTS_MASK) : realPts;
                        LAST_POD_KEY = podKey;
                        target = realPts;
                    } else {
                        target = (POD_BASE + (long) segN * SEG_TICKS) & PTS_MASK;
                    }
                }
            }
            return shiftToPts(slateMedia, slateFirst, target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clone template and shift its whole PES/PCR timeline so its first PES PTS == target. */
    static byte[] shiftToPts(byte[] template, long templateFirstPts, long target) {
        long delta = (target - templateFirstPts) & PTS_MASK;
        byte[] p = template.clone();
        shiftAll(p, delta);
        return p;
    }

    /** Range GET (bytes=0..n-1) that follows the DAI 302 to the media on googlevideo. */
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

    /** First PES PTS of a video (0xE0-0xEF) OR audio (0xC0-0xDF) stream, in file order, or -1. */
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

    /** Shift every PES PTS/DTS and PCR in the segment by delta (mod 33 bits). */
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
                    wrpcr(d, b + 6, (base + delta) & PTS_MASK);
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
                        wr33(d, hdr, (rd33(d, hdr) + delta) & PTS_MASK, (d[hdr] >> 4) & 0xf);
                        if (ptsdts == 3)
                            wr33(d, hdr + 5, (rd33(d, hdr + 5) + delta) & PTS_MASK, (d[hdr + 5] >> 4) & 0xf);
                    }
                }
            }
        }
    }

    static long rd33(byte[] p, int o) {
        return (((long) (p[o] >> 1) & 7) << 30)
             | (((long) (p[o + 1] & 0xff)) << 22)
             | (((long) (p[o + 2] >> 1) & 0x7f) << 15)
             | (((long) (p[o + 3] & 0xff)) << 7)
             | ((long) (p[o + 4] >> 1) & 0x7f);
    }

    static void wr33(byte[] p, int o, long v, int prefix) {
        v &= PTS_MASK;
        p[o]     = (byte) ((prefix << 4) | (((v >> 30) & 7) << 1) | 1);
        p[o + 1] = (byte) ((v >> 22) & 0xff);
        p[o + 2] = (byte) ((((v >> 15) & 0x7f) << 1) | 1);
        p[o + 3] = (byte) ((v >> 7) & 0xff);
        p[o + 4] = (byte) (((v & 0x7f) << 1) | 1);
    }

    static long rdpcr(byte[] p, int o) {
        return (((long) (p[o] & 0xff)) << 25)
             | (((long) (p[o + 1] & 0xff)) << 17)
             | (((long) (p[o + 2] & 0xff)) << 9)
             | (((long) (p[o + 3] & 0xff)) << 1)
             | ((long) (p[o + 4] >> 7) & 1);
    }

    static void wrpcr(byte[] p, int o, long base) {
        base &= PTS_MASK;
        p[o]     = (byte) ((base >> 25) & 0xff);
        p[o + 1] = (byte) ((base >> 17) & 0xff);
        p[o + 2] = (byte) ((base >> 9) & 0xff);
        p[o + 3] = (byte) ((base >> 1) & 0xff);
        p[o + 4] = (byte) (((base & 1) << 7) | (p[o + 4] & 0x7f));
    }
}
