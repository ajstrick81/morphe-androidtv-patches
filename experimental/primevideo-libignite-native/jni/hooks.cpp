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

void maybe_strip(const void* src, size_t n) {
    g_calls_total.fetch_add(1, std::memory_order_relaxed);
    if (src == nullptr || n < kMinScanLen || n > kMaxScanLen) return;
    g_calls_in_gate.fetch_add(1, std::memory_order_relaxed);

    uint64_t prev_max = g_max_n.load(std::memory_order_relaxed);
    if (n > prev_max) g_max_n.store(n, std::memory_order_relaxed);

    pvfilter::RemoteStripResult r =
        pvfilter::strip_remote_items(const_cast<char*>(static_cast<const char*>(src)), n);

    if (!r.found_marker) return;
    g_marker_found.fetch_add(1, std::memory_order_relaxed);

    if (!r.complete) {
        // Truncated chunk copy — by design we do NOT touch it (the safety
        // invariant that avoided black screens in the bench).
        g_truncated.fetch_add(1, std::memory_order_relaxed);
        LOGI("marker found but array TRUNCATED (not touched) n=%zu", n);
        return;
    }
    g_complete.fetch_add(1, std::memory_order_relaxed);

    if (r.modified) {
        g_modified.fetch_add(1, std::memory_order_relaxed);
        g_remote_blanked.fetch_add(static_cast<uint64_t>(r.remote_items), std::memory_order_relaxed);
        LOGI("blanked %d/%d Remote item(s) in complete array (n=%zu)",
             r.remote_items, r.total_items, n);
    } else {
        LOGI("marker found, array complete, 0 Remote items (n=%zu total=%d)", n, r.total_items);
    }
}

void* proxy_memcpy(void* dst, const void* src, size_t n) {
    g_n_memcpy.fetch_add(1, std::memory_order_relaxed);
    maybe_strip(src, n);
    return g_real_memcpy(dst, src, n);
}
void* proxy_memmove(void* dst, const void* src, size_t n) {
    g_n_memmove.fetch_add(1, std::memory_order_relaxed);
    maybe_strip(src, n);
    return g_real_memmove(dst, src, n);
}
// __memcpy_chk(dst, src, count, dst_len): first three args match memcpy.
void* proxy_memcpy_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memcpy_chk.fetch_add(1, std::memory_order_relaxed);
    maybe_strip(src, n);
    return g_real_memcpy_chk(dst, src, n, dst_len);
}
void* proxy_memmove_chk(void* dst, const void* src, size_t n, size_t dst_len) {
    g_n_memmove_chk.fetch_add(1, std::memory_order_relaxed);
    maybe_strip(src, n);
    return g_real_memmove_chk(dst, src, n, dst_len);
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
        LOGI("[hb] malloc=%llu | cpy=%llu mov=%llu cpy_chk=%llu mov_chk=%llu | "
             "total=%llu in_gate=%llu max_n=%llu marker=%llu complete=%llu "
             "trunc=%llu modified=%llu blanked=%llu",
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
    LOGI("JNI_OnLoad: pvhook loaded (GOT import hook on libignite memcpy/memmove)");
    install_hooks();
    return JNI_VERSION_1_6;
}
