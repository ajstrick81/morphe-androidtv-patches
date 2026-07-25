// hooks.cpp — JNI_OnLoad bootstrap for the Prime Video in-process ad strip.
//
// Strategy (see got_hook.h for the full rationale): hook libignite's *import
// slots* (PLT/GOT) for memcpy / memmove / __memcpy_chk / __memmove_chk. Each
// proxy runs pvfilter::strip_remote_items() on the SOURCE buffer (before the
// real copy proceeds) then calls through to the real libc function. This
// mirrors the verified Frida bench (cmod-strip2.js mutates `src` in onEnter
// before the wrapped call runs) — see remote_strip.h for the strip logic and
// its safety invariant (never touch a truncated array).
//
// Why GOT and not an inline libc hook: libignite reaches memcpy through an
// IFUNC that resolves to __memcpy_a55 on this Cortex-A55. Hooking a libc body
// means (a) guessing which of ~7 implementations the resolver picked — last
// session we watched memmove_a15, which the ad buffer never touches, so
// blanked=0 — and (b) rewriting code every thread runs, a plausible cause of
// the playback-start SIGSEGV. A GOT slot swap has neither problem.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

#include "got_hook.h"
#include "remote_strip.h"
#include "manifest_diag.h"

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr const char* kIgnite = "libignite.so";

// DRY-RUN CONTROL: build with -DPV_DRY_RUN=1 to make the strip parse + count
// exactly as normal but perform NO writes. Used to test whether our in-place
// edits (not our presence) cause the intermittent PRS "bad content encoding"
// (CURL 61) failure — hypothesis: we blank inside zlib's inflate window and
// break the gzip CRC. If dry-run eliminates the failure, the writes are the
// cause.
#ifndef PV_DRY_RUN
#define PV_DRY_RUN 0
#endif
constexpr bool kApplyWrites = (PV_DRY_RUN == 0);

// Same size gate as the verified bench's CModule scan: cheap enough to run on
// every copy, wide enough to cover real intraTitlePlaylist buffers (~40-68KB
// observed) with headroom.
constexpr size_t kMinScanLen = 512;
constexpr size_t kMaxScanLen = 262144;

std::atomic<uint64_t> g_calls_total{0};
std::atomic<uint64_t> g_calls_in_gate{0};
std::atomic<uint64_t> g_marker_found{0};
std::atomic<uint64_t> g_complete{0};
std::atomic<uint64_t> g_truncated{0};
std::atomic<uint64_t> g_trunc_remotes{0};  // complete Remote items seen inside truncated chunks
std::atomic<uint64_t> g_modified{0};
std::atomic<uint64_t> g_remote_blanked{0};
std::atomic<uint64_t> g_max_n{0};

// Per-entry-point counters — tells us which copy door carries the PRS buffer.
std::atomic<uint64_t> g_n_memcpy{0};
std::atomic<uint64_t> g_n_memmove{0};
std::atomic<uint64_t> g_n_memcpy_chk{0};
std::atomic<uint64_t> g_n_memmove_chk{0};
// Control: libignite's malloc import. If this floods while the copy counters
// tick, the GOT mechanism is proven to fire on real traffic.
std::atomic<uint64_t> g_malloc_calls{0};

// Saved originals (the real libc functions, resolved as libignite's slot held
// them). Proxies call through these — never a re-hooked path, so no recursion.
using memcpy_fn      = void* (*)(void*, const void*, size_t);
using memchk_fn      = void* (*)(void*, const void*, size_t, size_t);
using malloc_fn      = void* (*)(size_t);
memcpy_fn g_real_memcpy   = nullptr;
memcpy_fn g_real_memmove  = nullptr;
memchk_fn g_real_memcpy_chk  = nullptr;
memchk_fn g_real_memmove_chk = nullptr;
malloc_fn g_real_malloc   = nullptr;

// Log the caller (the code that invoked memcpy) as libignite+offset, so we can
// tell zlib's decompression copies apart from the app's final-buffer copy.
void log_caller(const char* what, const void* caller, size_t n, int remotes) {
    Dl_info di;
    if (caller && dladdr(caller, &di) && di.dli_fbase) {
        LOGI("CALLER %s: libignite+0x%zx n=%zu remotes=%d",
             what, (size_t)((uintptr_t)caller - (uintptr_t)di.dli_fbase), n, remotes);
    } else {
        LOGI("CALLER %s: %p (no dladdr) n=%zu remotes=%d", what, caller, n, remotes);
    }
}

// A copy of exactly a power-of-two size >= 4096 is a zlib/libcurl DECOMPRESSION
// CHUNK (CHUNK=16384, 4096, etc.), not the app's assembled PRS body. Editing
// those mid-decompression corrupts the gzip stream -> CURL 61 -> "Something went
// wrong" (verified on-device 2026-07-24: every corrupting copy was n=4096/16384;
// every safe/effective one was an odd-sized assembled body). Real JSON bodies
// are effectively never an exact power of two, so skipping these loses nothing
// but the corruption.
inline bool is_decompress_chunk(size_t n) {
    return n >= 4096 && (n & (n - 1)) == 0;
}

std::atomic<uint64_t> g_skipped_chunk{0};

// ── EXPERIMENTAL: source-side pre-copy blank for TV-show getVideoAds ads ──────
// TV prerolls leak because the app reads the PRE_ROLL getVideoAds Remote from the
// copy SOURCE (or an eager read) BEFORE our post-copy DESTINATION blank lands
// (verified on-device 2026-07-24). Fix: blank the Remote in the SOURCE *before*
// the real copy. MOVIE-SAFE BY CONSTRUCTION: gated on the buffer containing a
// "getVideoAds" URL, which only TV EXPL ad-marker playlists have — movie PRS
// buffers never match, so their proven dst-only path is untouched (no CURL-61
// regression). pow-2 decompression chunks are still skipped.
#ifndef PV_SRC_BLANK_GVA
#define PV_SRC_BLANK_GVA 0
#endif
#if PV_SRC_BLANK_GVA
std::atomic<uint64_t> g_src_blanked{0};
std::atomic<uint64_t> g_src_gva_buffers{0};
void maybe_strip_src(const void* vsrc, size_t n) {
    if (vsrc == nullptr || n < 512 || n > 262144) return;
    if ((n >= 4096 && (n & (n - 1)) == 0)) return;  // skip zlib decompression chunk
    const char* src = static_cast<const char*>(vsrc);
    size_t mp;
    if (!pvfilter::find_intra_title_playlist(src, n, &mp)) return;      // cheap memchr gate
    if (pvfilter::find_bytes(src, n, "getVideoAds") == static_cast<size_t>(-1)) return;  // TV-only
    g_src_gva_buffers.fetch_add(1, std::memory_order_relaxed);
    // NOTE: blank_truncated MUST stay false. Tried true (2026-07-24) to catch the
    // early-read race — it blanked the SOURCE of truncated PRS copies and corrupted
    // the still-decompressing gzip stream → CURLE_BAD_CONTENT_ENCODING (61) on the
    // PRS host (verified on-device), which also refetched the PRS and RE-showed the
    // ad. Complete-copy-only source-blank is the safe ceiling here (~intermittent).
    pvfilter::RemoteStripResult r =
        pvfilter::strip_remote_items(const_cast<char*>(src), n, /*blank_truncated=*/false,
                                     /*apply=*/kApplyWrites);
    int blanked = r.modified ? r.remote_items : 0;
    blanked += r.trunc_remote_blanked;
    if (blanked > 0) {
        g_src_blanked.fetch_add(static_cast<uint64_t>(blanked), std::memory_order_relaxed);
        LOGI("SRC-BLANK: blanked %d Remote(s) in getVideoAds SOURCE (n=%zu, complete=%d) pre-copy",
             blanked, n, r.complete ? 1 : 0);
    }
}
#else
inline void maybe_strip_src(const void*, size_t) {}
#endif

// ── EXPERIMENTAL: neutralize the getVideoAds request URL (TV-show prerolls) ───
// The ad-break plan is fetched via GET /cdp/getVideoAds?...&adMarkerId=PRE_ROLL.
// That URL transits our hook as PLAINTEXT (not gzip → no CURL-61 risk), and the
// earliest buffer is pre-`nerid` (the signature token), so rewriting the marker
// before nerid is computed keeps the request internally consistent → server
// should return an empty ad pod. Rewrite adMarkerId=PRE_ROLL -> =DISABLED (same
// length) in every request-URL buffer, source-side pre-copy. MOVIE-SAFE: movies
// never call getVideoAds. Deterministic — no parse race (unlike the PRS strip).
#ifndef PV_NEUTER_GVA_URL
#define PV_NEUTER_GVA_URL 0
#endif
#if PV_NEUTER_GVA_URL
std::atomic<uint64_t> g_gva_neutered{0};
void maybe_neuter_gva_url(const void* vsrc, size_t n) {
    if (vsrc == nullptr || n < 16 || n > 8192) return;
    char* src = const_cast<char*>(static_cast<const char*>(vsrc));
    // Request-URL buffers only (query form); never the gzipped PRS body.
    if (pvfilter::find_bytes(src, n, "getVideoAds?") == static_cast<size_t>(-1)) return;
    if (pvfilter::find_bytes(src, n, "intraTitlePlaylist") != static_cast<size_t>(-1)) return;
    // Named roll markers → same-length "DISABLED*" (an unknown-but-clean marker →
    // server returns an empty ad pod, proven graceful for PRE_ROLL). "adMarkerId="
    // is 11 chars; the value starts at +11. vlen = value length (also repl length).
    struct Marker { const char* key; size_t vlen; const char* repl; };
    static const Marker kMarkers[] = {
        {"adMarkerId=PRE_ROLL",  8, "DISABLED"},   // preroll  (proven)
        {"adMarkerId=MID_ROLL",  8, "DISABLED"},   // midroll
        {"adMarkerId=POST_ROLL", 9, "DISABLED0"},  // postroll
    };
    int count = 0;
    for (const Marker& m : kMarkers) {
        const size_t klen = 11 + m.vlen;           // "adMarkerId=" + value
        size_t off = 0;
        for (;;) {
            size_t rel = pvfilter::find_bytes(src + off, n - off, m.key);
            if (rel == static_cast<size_t>(-1)) break;
            size_t pos = off + rel;
            memcpy(src + pos + 11, m.repl, m.vlen);
            ++count; off = pos + klen;
        }
    }
    // Mid/post-roll markers are per-break UUIDs (e.g. adMarkerId=569d6493-a089-...),
    // NOT named. Rewrite any 36-char UUID adMarkerId value to the NIL UUID
    // (00000000-0000-0000-0000-000000000000) — format-preserving (hyphens at
    // 8/13/18/23) so the server treats it as an unknown-but-valid break → empty pod,
    // rather than a malformed value → error. Also neutralizes nonLinearAds nextUpAd.
    int uuids = 0;
    {
        const char amk[] = "adMarkerId=";
        size_t off = 0;
        for (;;) {
            size_t rel = pvfilter::find_bytes(src + off, n - off, amk);
            if (rel == static_cast<size_t>(-1)) break;
            size_t vpos = off + rel + 11;              // value start (after "adMarkerId=")
            size_t vlen = 0;
            while (vpos + vlen < n) {
                char c = src[vpos + vlen];
                if (c == '&' || c == '"' || c == '\\' || c == ' ' || c == '\0') break;
                ++vlen;
            }
            if (vlen == 36 && src[vpos+8]=='-' && src[vpos+13]=='-' &&
                src[vpos+18]=='-' && src[vpos+23]=='-') {
                for (size_t i = 0; i < 36; ++i) if (src[vpos+i] != '-') src[vpos+i] = '0';
                ++uuids;
            }
            off = vpos + (vlen ? vlen : 1);
        }
    }
    count += uuids;
    if (count > 0) {
        g_gva_neutered.fetch_add(static_cast<uint64_t>(count), std::memory_order_relaxed);
        LOGI("NEUTER-GVA: rewrote %d marker(s) (%d UUID->nil) (n=%zu) pre-copy", count, uuids, n);
    }
}
#else
inline void maybe_neuter_gva_url(const void*, size_t) {}
#endif

// ── READ-ONLY DASH-manifest diagnostic (build with -DPV_MANIFEST_DIAG=1) ──────
// Confirms whether TV-show ad breaks are physically embedded in the .mpd as
// <Period id="Adbreak*"/"Card_*"> elements (SSAI), and reports the buffer SIZES
// manifest bytes flow through — the feasibility question, since the strip gate
// only sees copies <=256KB. NEVER writes. Compiled out entirely when the flag
// is 0, so the shipping strip build is byte-for-byte unchanged.
#ifndef PV_MANIFEST_DIAG
#define PV_MANIFEST_DIAG 0
#endif

#if PV_MANIFEST_DIAG
constexpr size_t kManifestDiagMax = 16u * 1024 * 1024;  // 16MB cap for the diag scan
std::atomic<uint64_t> g_diag_manifest_seen{0};
std::atomic<uint64_t> g_diag_bigxml_logged{0};
std::atomic<bool> g_diag_dumped_root{false};
std::atomic<bool> g_diag_dumped_ad{false};

void maybe_diag_manifest(const void* vbuf, size_t n) {
    if (vbuf == nullptr || n < 512 || n > kManifestDiagMax) return;
    const char* buf = static_cast<const char*>(vbuf);

    // (1) Characterize LARGE (>256KB) XML buffers regardless of exact match —
    // catches manifest chunks copied from a non-start offset and reveals the
    // sizes at which manifest bytes move (vs the 256KB strip gate).
    if (n > kMaxScanLen) {
        size_t h = 0;
        while (h < n && (buf[h]==' '||buf[h]=='\t'||buf[h]=='\n'||buf[h]=='\r')) ++h;
        if (h < n && buf[h] == '<') {
            uint64_t c = g_diag_bigxml_logged.fetch_add(1, std::memory_order_relaxed);
            if (c < 40) {
                char head[49]; size_t m = (n - h < 48) ? (n - h) : 48;
                memcpy(head, buf + h, m); head[m] = '\0';
                for (size_t i = 0; i < m; ++i)
                    if (head[i] < 32 || head[i] > 126) head[i] = '.';
                LOGI("[mdiag] BIG XML buffer n=%zu head=\"%s\"", n, head);
            }
        }
    }

    // (2) Full period scan only on buffers that look like a DASH manifest (cheap
    // head check fails fast for JSON/binary, so the hot path pays almost nothing).
    if (!pvfilter::looks_like_dash(buf, n)) return;
    pvfilter::ManifestScan s = pvfilter::scan_manifest(buf, n);
    if (s.period_count == 0 && !s.looks_like_manifest) return;

    uint64_t seen = g_diag_manifest_seen.fetch_add(1, std::memory_order_relaxed);
    LOGI("[mdiag] manifest buffer n=%zu periods=%d ad_periods=%d (seen#%llu)",
         n, s.period_count, s.ad_period_count, (unsigned long long)seen);

    // Log each period's open tag (bounded), for the first few manifests only.
    if (seen < 6) {
        for (int i = 0; i < s.period_count; ++i) {
            size_t ts = s.tag_start[i], te = s.tag_open_end[i];
            size_t taglen = te - ts; if (taglen > 220) taglen = 220;
            char tag[224]; memcpy(tag, buf + ts, taglen); tag[taglen] = '\0';
            for (size_t k = 0; k < taglen; ++k)
                if (tag[k]=='\n'||tag[k]=='\r') tag[k] = ' ';
            LOGI("[mdiag]   P%d %s %.*s", i, s.is_ad[i] ? "AD  " : "MAIN",
                 (int)taglen, tag);
        }
    }

    // One-time: dump the MPD root head (root attributes: profiles/times/type).
    if (s.looks_like_manifest && !g_diag_dumped_root.exchange(true)) {
        size_t h = 0; while (h < n && buf[h] != '<') ++h;
        size_t dl = (n - h < 380) ? (n - h) : 380;
        char b[384]; memcpy(b, buf + h, dl); b[dl] = '\0';
        for (size_t k = 0; k < dl; ++k) if (b[k]=='\n'||b[k]=='\r') b[k] = ' ';
        LOGI("[mdiag] ROOT head: %.*s", (int)dl, b);
    }

    // One-time: dump the first ad period's element body (bounded, in <=900-byte
    // slices under logcat's line cap) so surgery can preserve SegmentTemplate/
    // BaseURL/timeline structure.
    if (s.first_ad_idx >= 0 && !g_diag_dumped_ad.exchange(true)) {
        size_t ts = s.tag_start[s.first_ad_idx];
        size_t avail = n - ts;
        size_t dl = (avail < 1800) ? avail : 1800;
        size_t off = 0; int slice = 0;
        while (off < dl) {
            size_t m = (dl - off < 900) ? (dl - off) : 900;
            char tmp[912]; memcpy(tmp, buf + ts + off, m); tmp[m] = '\0';
            for (size_t k = 0; k < m; ++k) if (tmp[k]=='\n'||tmp[k]=='\r') tmp[k] = ' ';
            LOGI("[mdiag] ADPERIOD[%d] %.*s", slice, (int)m, tmp);
            off += m; ++slice;
        }
    }
}
// ── PRS ad-scheduling FIELD finder (read-only) ───────────────────────────────
// TV-show ads survive the intraTitlePlaylist Remote strip (on-device: an ad
// played with 0 Remote items / blanked=0), so their schedule rides a DIFFERENT
// PRS field. This scans JSON PRS buffers for candidate ad-schedule keys, logs
// the first occurrence of each with context, and one-time full-dumps the first
// few ad-key-bearing buffers so we can read the exact structure to strip.
const char* const kAdKeys[] = {
    "cuepointPlaylist", "nonLinearAds", "adBreak", "PRE_ROLL", "MID_ROLL",
    "adPlan", "adClips", "adManifest", "adMarker", "breakId",
    // ad-PLAN (getVideoAds response) anchors — the structure we most need:
    "breakType", "adNumberWithinBreak", "adClipId", "getVideoAds", "adBreakId",
};
constexpr int kNumAdKeys = sizeof(kAdKeys) / sizeof(kAdKeys[0]);
std::atomic<uint32_t> g_adkey_seen_mask{0};   // bit i set once kAdKeys[i] logged
std::atomic<int> g_pdiag_full_dumps{0};
std::atomic<int> g_prs_body_dumps{0};
size_t g_prs_dumped_sizes[8];
std::atomic<int> g_prs_nsizes{0};
// Ad-PLAN capture (getVideoAds response, incl. raw not just event-escaped):
size_t g_adplan_sizes[16];
std::atomic<int> g_adplan_nsizes{0};
std::atomic<int> g_adplan_dumps{0};

void dump_slices(const char* tag, const char* buf, size_t off, size_t dl) {
    size_t done = 0; int slice = 0;
    while (done < dl) {
        size_t m = (dl - done < 900) ? (dl - done) : 900;
        char tmp[912]; memcpy(tmp, buf + off + done, m); tmp[m] = '\0';
        for (size_t k = 0; k < m; ++k) {
            char c = tmp[k];
            if (c == '\n' || c == '\r' || c == '\t') tmp[k] = ' ';
            else if (c < 32 || c > 126) tmp[k] = '.';
        }
        LOGI("[pdiag] %s[%d] %.*s", tag, slice, (int)m, tmp);
        done += m; ++slice;
    }
}

// LEAK HUNT: enumerate EVERY getVideoAds URL in a buffer (pristine, pre-strip)
// and name the carrier field (nearest preceding array key) + its adMarkerId, so
// we can tell whether the ad URL rides a field we DON'T strip (e.g. nonLinearAds)
// vs only intraTitlePlaylist (→ the leak is source/timing). Dedup by size.
size_t g_gva_sizes[16];
std::atomic<int> g_gva_nsizes{0};
size_t g_gvaurl_sizes[16];
std::atomic<int> g_gvaurl_nsizes{0};
std::atomic<int> g_gvaurl_dumps{0};
void diag_hunt_getvideoads(const char* buf, size_t n) {
    if (pvfilter::find_bytes(buf, n, "getVideoAds") == static_cast<size_t>(-1)) return;
    int ns = g_gva_nsizes.load(std::memory_order_relaxed);
    for (int i = 0; i < ns; ++i) if (g_gva_sizes[i] == n) return;
    if (ns >= 16) return;
    g_gva_sizes[ns] = n; g_gva_nsizes.store(ns + 1, std::memory_order_relaxed);

    LOGI("[gva] buffer n=%zu — enumerating getVideoAds carriers:", n);
    size_t off = 0; int idx = 0;
    while (idx < 24) {
        size_t rel = pvfilter::find_bytes(buf + off, n - off, "getVideoAds");
        if (rel == static_cast<size_t>(-1)) break;
        size_t pos = off + rel;
        // Nearest preceding array key = the carrier field.
        size_t itp = pvfilter::rfind_bytes(buf, pos, "intraTitlePlaylist");
        size_t nla = pvfilter::rfind_bytes(buf, pos, "nonLinearAds");
        size_t cue = pvfilter::rfind_bytes(buf, pos, "cuepointPlaylist");
        const char* field = "?"; size_t best = 0; bool have = false;
        if (itp != (size_t)-1) { field = "intraTitlePlaylist"; best = itp; have = true; }
        if (nla != (size_t)-1 && (!have || nla > best)) { field = "nonLinearAds"; best = nla; have = true; }
        if (cue != (size_t)-1 && (!have || cue > best)) { field = "cuepointPlaylist"; best = cue; have = true; }
        // adMarkerId value just after the URL.
        char mk[24] = "?";
        size_t mp = pvfilter::find_bytes(buf + pos, (n - pos < 300 ? n - pos : 300), "adMarkerId=");
        if (mp != (size_t)-1) {
            size_t s = pos + mp + 11, j = 0;
            while (s + j < n && j < 23 && buf[s+j] != '&' && buf[s+j] != '"' && buf[s+j] != '\\') { mk[j] = buf[s+j]; ++j; }
            mk[j] = '\0';
        }
        LOGI("[gva]   #%d @%zu field=%s (key@%zu, dist=%zu) adMarkerId=%s",
             idx, pos, field, best, have ? pos - best : 0, mk);
        off = pos + 11; ++idx;
    }
    LOGI("[gva] buffer n=%zu — %d getVideoAds occurrence(s)", n, idx);
}

void maybe_diag_prs(const void* vbuf, size_t n) {
    if (vbuf == nullptr || n < 8 || n > kMaxScanLen) return;
    const char* buf = static_cast<const char*>(vbuf);

    // OUTBOUND REQUEST-URL capture (runs BEFORE the JSON gate — the request URL is a
    // plain string starting with '/'). Target the QUERY form "getVideoAds?" so we
    // catch the real HTTP request (has ?version=&adMarkerId=PRE_ROLL&failover=...),
    // not the "[/cdp/getVideoAds] Using DCTR provider" LOG labels. Full content,
    // deduped by size, so we can design a precise same-length in-place edit.
    if (n < 4096 && pvfilter::find_bytes(buf, n, "getVideoAds?") != static_cast<size_t>(-1) &&
        pvfilter::find_bytes(buf, n, "intraTitlePlaylist") == static_cast<size_t>(-1)) {
        int ns = g_gvaurl_nsizes.load(std::memory_order_relaxed);
        bool seen = false;
        for (int i = 0; i < ns; ++i) if (g_gvaurl_sizes[i] == n) { seen = true; break; }
        if (!seen && ns < 16 && g_gvaurl_dumps.fetch_add(1, std::memory_order_relaxed) < 12) {
            g_gvaurl_sizes[ns] = n; g_gvaurl_nsizes.store(ns + 1, std::memory_order_relaxed);
            LOGI("[gvaurl] REQUEST-URL buffer n=%zu BEGIN", n);
            dump_slices("URL", buf, 0, n);
            LOGI("[gvaurl] REQUEST-URL buffer n=%zu END", n);
        }
    }

    // Cheap JSON gate: first non-ws must be '{' or '['.
    size_t h = 0; while (h < n && (buf[h]==' '||buf[h]=='\t'||buf[h]=='\n'||buf[h]=='\r')) ++h;
    if (h >= n || (buf[h] != '{' && buf[h] != '[')) return;

    diag_hunt_getvideoads(buf, n);  // pristine leak-hunt (pre-strip)

    uint32_t mask = g_adkey_seen_mask.load(std::memory_order_relaxed);
    bool buffer_has_new_key = false;
    for (int i = 0; i < kNumAdKeys; ++i) {
        size_t pos = pvfilter::find_bytes(buf, n, kAdKeys[i]);
        if (pos == static_cast<size_t>(-1)) continue;
        if (mask & (1u << i)) continue;  // already logged this key once
        g_adkey_seen_mask.fetch_or(1u << i, std::memory_order_relaxed);
        buffer_has_new_key = true;
        size_t start = (pos > 160) ? pos - 160 : 0;
        size_t wlen = n - start; if (wlen > 360) wlen = 360;
        char w[364]; memcpy(w, buf + start, wlen); w[wlen] = '\0';
        for (size_t k = 0; k < wlen; ++k) {
            char c = w[k];
            if (c == '\n' || c == '\r' || c == '\t') w[k] = ' ';
            else if (c < 32 || c > 126) w[k] = '.';
        }
        LOGI("[pdiag] AD-KEY \"%s\" @%zu (n=%zu): %.*s", kAdKeys[i], pos, n, (int)wlen, w);
    }

    // Ad-PLAN capture: dump EVERY compact buffer containing "breakType" (the
    // getVideoAds ad-break plan), independent of the one-shot key mask, so we
    // catch the RAW response — not just the escaped copy inside a TimelineChange
    // event. Dedup by size; tag whether it's an event wrapper (messageName).
    if (n < 40000 && pvfilter::find_bytes(buf, n, "breakType") != static_cast<size_t>(-1)) {
        int ns = g_adplan_nsizes.load(std::memory_order_relaxed);
        bool seen = false;
        for (int i = 0; i < ns; ++i) if (g_adplan_sizes[i] == n) { seen = true; break; }
        if (!seen && ns < 16 && g_adplan_dumps.load(std::memory_order_relaxed) < 8) {
            g_adplan_sizes[ns] = n; g_adplan_nsizes.store(ns + 1, std::memory_order_relaxed);
            g_adplan_dumps.fetch_add(1, std::memory_order_relaxed);
            bool is_evt = pvfilter::find_bytes(buf, n, "messageName") != static_cast<size_t>(-1);
            LOGI("[pdiag] ADPLAN body n=%zu is_event=%d BEGIN", n, is_evt ? 1 : 0);
            dump_slices("PLAN", buf, 0, n);
            LOGI("[pdiag] ADPLAN body END n=%zu", n);
        }
    }

    // Full dump of ad-key buffers, but ONLY the compact ones (< 24KB) so the big
    // weblab/config blobs don't starve the cap before the getVideoAds ad-plan
    // response (~16KB) shows up. Cap raised to 12.
    if (buffer_has_new_key && n < 24000 &&
        g_pdiag_full_dumps.fetch_add(1, std::memory_order_relaxed) < 12) {
        LOGI("[pdiag] FULL BODY (ad-key buffer) n=%zu BEGIN", n);
        dump_slices("BODY", buf, 0, n);
        LOGI("[pdiag] FULL BODY END n=%zu", n);
    }
}

// Dump a COMPLETE intraTitlePlaylist PRS body once per distinct size (cap 4) —
// the canonical response that also carries the sibling ad-schedule fields.
void diag_dump_prs_complete(const char* buf, size_t n) {
    int ns = g_prs_nsizes.load(std::memory_order_relaxed);
    for (int i = 0; i < ns; ++i) if (g_prs_dumped_sizes[i] == n) return;
    if (ns >= 8 || g_prs_body_dumps.load(std::memory_order_relaxed) >= 4) return;
    g_prs_dumped_sizes[ns] = n; g_prs_nsizes.store(ns + 1, std::memory_order_relaxed);
    g_prs_body_dumps.fetch_add(1, std::memory_order_relaxed);
    LOGI("[pdiag] PRS-COMPLETE body n=%zu BEGIN", n);
    dump_slices("PRS", buf, 0, n);
    LOGI("[pdiag] PRS-COMPLETE body END n=%zu", n);
}
#else
inline void maybe_diag_manifest(const void*, size_t) {}
inline void maybe_diag_prs(const void*, size_t) {}
#endif
// ─────────────────────────────────────────────────────────────────────────────

void maybe_strip(const void* src, size_t n, const void* caller) {
    maybe_diag_manifest(src, n);  // read-only; own size gate; no-op unless PV_MANIFEST_DIAG
    maybe_diag_prs(src, n);       // read-only ad-field finder (pristine, pre-strip)
    g_calls_total.fetch_add(1, std::memory_order_relaxed);
    if (src == nullptr || n < kMinScanLen || n > kMaxScanLen) return;
    if (is_decompress_chunk(n)) { g_skipped_chunk.fetch_add(1, std::memory_order_relaxed); return; }
    g_calls_in_gate.fetch_add(1, std::memory_order_relaxed);

    uint64_t prev_max = g_max_n.load(std::memory_order_relaxed);
    if (n > prev_max) g_max_n.store(n, std::memory_order_relaxed);

    pvfilter::RemoteStripResult r =
        pvfilter::strip_remote_items(const_cast<char*>(static_cast<const char*>(src)), n,
                                     /*blank_truncated_complete=*/false,
                                     /*apply=*/kApplyWrites);

    if (!r.found_marker) return;
    g_marker_found.fetch_add(1, std::memory_order_relaxed);

    if (!r.complete) {
        // Truncated chunk copy — by design we do NOT touch it (the safety
        // invariant that avoided black screens in the bench). Diagnostic: if
        // complete Remote items exist before the cut, ads are present in a
        // chunk we skip (truncated-parse-race evidence).
        g_truncated.fetch_add(1, std::memory_order_relaxed);
        if (r.trunc_remote_blanked > 0) {
            g_trunc_remotes.fetch_add(static_cast<uint64_t>(r.trunc_remote_blanked),
                                      std::memory_order_relaxed);
            LOGI("TRUNCATED array: blanked %d/%d COMPLETE Remote item(s) before cut, "
                 "left truncated tail untouched (n=%zu)",
                 r.trunc_remote_blanked, r.trunc_complete_items, n);
            log_caller("TRUNC", caller, n, r.trunc_remote_blanked);
        } else {
            LOGI("marker found but array TRUNCATED (not touched) n=%zu", n);
        }
        return;
    }
    g_complete.fetch_add(1, std::memory_order_relaxed);
#if PV_MANIFEST_DIAG
    diag_dump_prs_complete(static_cast<const char*>(src), n);
#endif

    if (r.modified) {
        g_modified.fetch_add(1, std::memory_order_relaxed);
        g_remote_blanked.fetch_add(static_cast<uint64_t>(r.remote_items), std::memory_order_relaxed);
        LOGI("blanked %d/%d Remote item(s) in complete array (n=%zu)",
             r.remote_items, r.total_items, n);
        log_caller("COMPLETE", caller, n, r.remote_items);
    } else {
        LOGI("marker found, array complete, 0 Remote items (n=%zu total=%d)", n, r.total_items);
    }
}

// IMPORTANT: we strip the DESTINATION *after* the real copy, never the source
// before it. The PRS body reaches us through libcurl's gzip decompression; the
// decompressed bytes are the buffer libcurl computes the gzip CRC32 over.
// Editing the SOURCE of a copy can be editing that CRC-checked buffer mid-
// stream -> CURLE_BAD_CONTENT_ENCODING (61) -> "Something went wrong" (verified
// on-device 2026-07-24, clean A/B: writes-to-src ON = intermittent CURL 61,
// dry-run = 0 errors). Editing the DESTINATION leaves the source pristine for
// the CRC while still blanking the copy the app goes on to parse.
void* proxy_memcpy(void* dst, const void* src, size_t n) {
    g_n_memcpy.fetch_add(1, std::memory_order_relaxed);
    maybe_strip_src(src, n);  // TV getVideoAds: blank source pre-copy (movie-safe gate)
    maybe_neuter_gva_url(src, n);
    void* ret = g_real_memcpy(dst, src, n);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
void* proxy_memmove(void* dst, const void* src, size_t n) {
    g_n_memmove.fetch_add(1, std::memory_order_relaxed);
    maybe_strip_src(src, n);
    maybe_neuter_gva_url(src, n);
    void* ret = g_real_memmove(dst, src, n);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
// __memcpy_chk(dst, src, count, dst_len): first three args match memcpy.
void* proxy_memcpy_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memcpy_chk.fetch_add(1, std::memory_order_relaxed);
    maybe_strip_src(src, n);
    maybe_neuter_gva_url(src, n);
    void* ret = g_real_memcpy_chk(dst, src, n, dst_len);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
void* proxy_memmove_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memmove_chk.fetch_add(1, std::memory_order_relaxed);
    maybe_strip_src(src, n);
    maybe_neuter_gva_url(src, n);
    void* ret = g_real_memmove_chk(dst, src, n, dst_len);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
void* proxy_malloc(size_t n) {
    g_malloc_calls.fetch_add(1, std::memory_order_relaxed);
    return g_real_malloc(n);
}

// Try to install all copy-import hooks once. Returns the number of the four
// copy entry points successfully hooked (0 if libignite isn't mapped yet).
int try_install_once() {
    int ok = 0;
    if (g_real_memcpy == nullptr)
        ok += pvgot::hook_import(kIgnite, "memcpy", (void*)proxy_memcpy, (void**)&g_real_memcpy) ? 1 : 0;
    else ok++;
    if (g_real_memmove == nullptr)
        ok += pvgot::hook_import(kIgnite, "memmove", (void*)proxy_memmove, (void**)&g_real_memmove) ? 1 : 0;
    else ok++;
    if (g_real_memcpy_chk == nullptr)
        ok += pvgot::hook_import(kIgnite, "__memcpy_chk", (void*)proxy_memcpy_chk, (void**)&g_real_memcpy_chk) ? 1 : 0;
    else ok++;
    if (g_real_memmove_chk == nullptr)
        ok += pvgot::hook_import(kIgnite, "__memmove_chk", (void*)proxy_memmove_chk, (void**)&g_real_memmove_chk) ? 1 : 0;
    else ok++;
    return ok;
}

// Worker: libignite.so is NOT mapped at Application.onCreate (it loads with the
// media/player subsystem, well before any PRS ad fetch). Poll until it appears,
// install the GOT hooks once, then fall through to the heartbeat loop. Polling
// dl_iterate_phdr twice a second is negligible and stops as soon as we hook.
void* worker_thread(void*) {
    constexpr int kMaxAttempts = 2400;  // ~20 min at 500ms; generous safety cap
    bool installed = false;
    for (int i = 0; i < kMaxAttempts && !installed; i++) {
        int ok = try_install_once();
        if (ok > 0) {
            installed = true;
            // Best-effort control probe once libignite is present.
            pvgot::hook_import(kIgnite, "malloc", (void*)proxy_malloc, (void**)&g_real_malloc);
            LOGI("worker: libignite present, %d/4 copy imports hooked after %d attempt(s)", ok, i + 1);
            break;
        }
        usleep(500 * 1000);
    }
    if (!installed) {
        LOGE("worker: libignite never appeared within cap — strip will not run");
        return nullptr;
    }

    for (;;) {
        sleep(5);
        LOGI("[hb] skipchunk=%llu malloc=%llu | cpy=%llu mov=%llu cpy_chk=%llu mov_chk=%llu | "
             "total=%llu in_gate=%llu max_n=%llu marker=%llu complete=%llu "
             "trunc=%llu trunc_rem=%llu modified=%llu blanked=%llu",
             (unsigned long long)g_skipped_chunk.load(std::memory_order_relaxed),
             (unsigned long long)g_malloc_calls.load(std::memory_order_relaxed),
             (unsigned long long)g_n_memcpy.load(std::memory_order_relaxed),
             (unsigned long long)g_n_memmove.load(std::memory_order_relaxed),
             (unsigned long long)g_n_memcpy_chk.load(std::memory_order_relaxed),
             (unsigned long long)g_n_memmove_chk.load(std::memory_order_relaxed),
             (unsigned long long)g_calls_total.load(std::memory_order_relaxed),
             (unsigned long long)g_calls_in_gate.load(std::memory_order_relaxed),
             (unsigned long long)g_max_n.load(std::memory_order_relaxed),
             (unsigned long long)g_marker_found.load(std::memory_order_relaxed),
             (unsigned long long)g_complete.load(std::memory_order_relaxed),
             (unsigned long long)g_truncated.load(std::memory_order_relaxed),
             (unsigned long long)g_trunc_remotes.load(std::memory_order_relaxed),
             (unsigned long long)g_modified.load(std::memory_order_relaxed),
             (unsigned long long)g_remote_blanked.load(std::memory_order_relaxed));
    }
    return nullptr;
}

void install_hooks() {
    // libignite isn't mapped yet at onCreate, so defer to a worker that polls
    // for it, installs the GOT hooks, then heartbeats.
    pthread_t tid;
    pthread_create(&tid, nullptr, worker_thread, nullptr);
    pthread_detach(tid);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("JNI_OnLoad: pvhook loaded (GOT import hook on libignite memcpy/memmove) — writes=%s",
         kApplyWrites ? "ON" : "DRY-RUN(no writes)");
    install_hooks();
    return JNI_VERSION_1_6;
}
