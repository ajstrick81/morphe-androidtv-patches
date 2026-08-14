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
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

#include "got_hook.h"
#include "remote_strip.h"

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

// Self-stamp oracle counters. Emit a stable, greppable marker whenever the hook
// actually removes ads, plus a periodic summary — so an autotest can validate PV
// ad removal from logcat (PV's MediaSession is unreliable; see
// docs/AUTONOMOUS_APP_TESTING.md). Autotest ORACLE_RE matches "PVKILL"/"PVOBS".
std::atomic<uint64_t> g_pvkill_movie{0};  // Remote ad items blanked (movies, PATH 1)
std::atomic<uint64_t> g_pvkill_tv{0};     // ad entries emptied (TV regolith, PATH 2)

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

// Small self-contained substring search (bionic memmem). Returns the byte offset
// of `needle` in [buf,buf+n), or (size_t)-1 if absent.
inline size_t find_bytes(const char* buf, size_t n, const char* needle) {
    size_t nlen = std::strlen(needle);
    if (nlen == 0 || nlen > n) return static_cast<size_t>(-1);
    const void* p = memmem(buf, n, needle, nlen);
    return p ? static_cast<size_t>(static_cast<const char*>(p) - buf)
             : static_cast<size_t>(-1);
}

// NOTE — the JS "count-gate" approach (rewriting resolveWithAdBreaks' `0===t.length`
// guard in the downloaded QuickJS player) was prototyped and REJECTED for shipping:
// that class lives in the gzip'd player bundle, and editing its buffer — source OR
// destination — corrupts the in-flight decompression -> CURLE_BAD_CONTENT_ENCODING
// (61) and a bundle refetch storm at startup (confirmed on-device 2026-07-30). It
// also provided no measurable ad removal beyond PV_EMPTY_REGOLITH below, which does
// the work cleanly on the ad-decision RESPONSE (post-validation) rather than the
// bundle. TV ads are handled entirely by PATH 2.

// ── TV: empty the regolith getVideoAds RESPONSE `playlist` array ──────────────
// The dominant SGAI ad path (AdBreakManager.resolveAndInsert) builds every ad
// period from the resolved regolith response `e.playlist`. Wire format (verified
// on-device, decompressed plaintext, ~4.5KB, single buffer):
//   {"description":{"adDeliverySessionId":"..._PBP_EXPL_...","adMarkerId":"PRE_ROLL"},
//    "playlist":[{…ad…},{…ad…}],"measurement":{…}}
// Blanking the array interior to spaces (same length, matched-bracket, string-aware)
// makes e.playlist.length===0 consistently, so the app runs its OWN designed clean
// empty-break path: no periods built, insertBefore([])/insertAfter([]) no-op,
// sendEmptyAdBreakTrackingEvents, and unblockXpPlaylistIndex(a+0) unblocks the
// CORRECT index -> NO ghost period. dst-side (post-decompress -> no CRC-61); only
// acts on a COMPLETE array (matching ']' present); skips pow2 chunks.
#ifndef PV_EMPTY_REGOLITH
#define PV_EMPTY_REGOLITH 0
#endif
#if PV_EMPTY_REGOLITH
std::atomic<uint64_t> g_rego_emptied{0};
std::atomic<uint64_t> g_rego_seen{0};
// JSON-aware matching-bracket finder: index of the ']'/'}' closing `open`, or
// (size_t)-1 if unclosed within [open,n). String/escape aware.
size_t json_match_bracket(const char* buf, size_t n, size_t open) {
    int depth = 0; bool in_str = false, esc = false;
    for (size_t i = open; i < n; ++i) {
        char c = buf[i];
        if (in_str) {
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') in_str = false;
            continue;
        }
        if (c == '"') { in_str = true; }
        else if (c == '[' || c == '{') { ++depth; }
        else if (c == ']' || c == '}') { if (--depth == 0) return i; }
    }
    return static_cast<size_t>(-1);
}
void maybe_empty_regolith(void* vbuf, size_t n) {
    if (vbuf == nullptr || n < 128 || n > kMaxScanLen) return;
    if (is_decompress_chunk(n)) return;
    char* buf = static_cast<char*>(vbuf);
    size_t pl = find_bytes(buf, n, "\"playlist\":[");
    if (pl == static_cast<size_t>(-1)) return;
    if (find_bytes(buf, n, "adDeliverySessionId") == static_cast<size_t>(-1)) return;
    if (find_bytes(buf, n, "\"measurement\"")      == static_cast<size_t>(-1)) return;
    if (find_bytes(buf, n, "intraTitlePlaylist")   != static_cast<size_t>(-1)) return;  // exclude PRS
    g_rego_seen.fetch_add(1, std::memory_order_relaxed);
    size_t open = pl + 11;                                // index of '[' in "\"playlist\":["
    size_t close = json_match_bracket(buf, n, open);
    if (close == static_cast<size_t>(-1)) return;         // truncated array — leave untouched
    if (close <= open + 1) return;                        // already empty
    int ads = 1; int depth = 0; bool in_str = false, esc = false;
    for (size_t i = open; i < close; ++i) {
        char c = buf[i];
        if (in_str) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') in_str = false; continue; }
        if (c == '"') in_str = true;
        else if (c == '[' || c == '{') ++depth;
        else if (c == ']' || c == '}') --depth;
        else if (c == ',' && depth == 1) ++ads;
    }
    for (size_t i = open + 1; i < close; ++i) buf[i] = ' ';   // same-length empty array
    uint64_t c = g_rego_emptied.fetch_add(1, std::memory_order_relaxed);
    if (c < 40)
        LOGI("[rego] emptied playlist (~%d ad(s), interior %zu bytes blanked) n=%zu",
             ads, close - open - 1, n);
    g_pvkill_tv.fetch_add(static_cast<uint64_t>(ads), std::memory_order_relaxed);
    LOGI("PVKILL path=tv ads=%d n=%zu", ads, n);   // self-stamp (oracle)
}
#else
inline void maybe_empty_regolith(void*, size_t) {}
#endif

void maybe_strip(const void* src, size_t n, const void* caller) {
    g_calls_total.fetch_add(1, std::memory_order_relaxed);
    if (src == nullptr || n < kMinScanLen || n > kMaxScanLen) return;
    if (is_decompress_chunk(n)) { g_skipped_chunk.fetch_add(1, std::memory_order_relaxed); return; }
    maybe_empty_regolith(const_cast<void*>(src), n);   // TV: empty regolith ad-decision response (dst-side)
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

    if (r.gva_skipped > 0)
        LOGI("PVSKIP path=movie gvaRemotesLeftForPath2=%d n=%zu", r.gva_skipped, n);

    if (r.modified) {
        g_modified.fetch_add(1, std::memory_order_relaxed);
        g_remote_blanked.fetch_add(static_cast<uint64_t>(r.remote_items), std::memory_order_relaxed);
        LOGI("blanked %d/%d Remote item(s) in complete array (n=%zu)",
             r.remote_items, r.total_items, n);
        g_pvkill_movie.fetch_add(static_cast<uint64_t>(r.remote_items), std::memory_order_relaxed);
        LOGI("PVKILL path=movie blanked=%d/%d n=%zu", r.remote_items, r.total_items, n);  // self-stamp (oracle)
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
    void* ret = g_real_memcpy(dst, src, n);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
void* proxy_memmove(void* dst, const void* src, size_t n) {
    g_n_memmove.fetch_add(1, std::memory_order_relaxed);
    void* ret = g_real_memmove(dst, src, n);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
// __memcpy_chk(dst, src, count, dst_len): first three args match memcpy.
void* proxy_memcpy_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memcpy_chk.fetch_add(1, std::memory_order_relaxed);
    void* ret = g_real_memcpy_chk(dst, src, n, dst_len);
    maybe_strip(dst, n, __builtin_return_address(0));
    return ret;
}
void* proxy_memmove_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memmove_chk.fetch_add(1, std::memory_order_relaxed);
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
        // Self-stamp summary (oracle): total ads removed since load, both paths.
        LOGI("PVOBS movieBlanked=%llu tvEmptied=%llu",
             (unsigned long long)g_pvkill_movie.load(std::memory_order_relaxed),
             (unsigned long long)g_pvkill_tv.load(std::memory_order_relaxed));
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
