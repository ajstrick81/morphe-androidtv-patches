// hooks.cpp — bootstrap + inline hooks for the in-process manifest strip.
//
// Flow:
//   JNI_OnLoad -> resolve libignite module -> signature-scan SSL_read/inflate
//   -> install Dobby inline hooks -> each hook runs pvfilter::filter() on the
//      plaintext it just produced, shrinking out any /iad_ ad segments before
//      the native HLS/DASH parser consumes the buffer.
//
// Dobby is the inline-hook engine (github.com/jmpews/Dobby). Vendor it under
// jni/dobby/ and point CMakeLists.txt at it. If you prefer xHook/And64/ShadowHook,
// only install_hook() below changes.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>

#include "offsets.h"
#include "sigscan.h"
#include "manifest_filter.h"
#include "inflate_filter.h"

// Dobby API (from the vendored header). Declared here so this file documents
// its exact dependency surface even before the header is in the include path.
extern "C" int  DobbyHook(void* address, void* replace_func, void** origin_func);

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── SSL_read hook ────────────────────────────────────────────────────────────
// int SSL_read(SSL* ssl, void* buf, int num);
using ssl_read_t = int (*)(void*, void*, int);
static ssl_read_t real_SSL_read = nullptr;

static int hook_SSL_read(void* ssl, void* buf, int num) {
    int n = real_SSL_read(ssl, buf, num);
    if (n <= 0) return n;

    // NOTE: one SSL_read == one TLS record, not necessarily one whole manifest.
    // For manifests that fit in a single read (the common case here) this is
    // sufficient. For chunked delivery, buffer per-SSL* until a full body is
    // seen before filtering — left as a documented extension point.
    pvfilter::FilterResult r = pvfilter::filter(static_cast<char*>(buf),
                                                static_cast<size_t>(n));
    if (r.modified) {
        LOGI("SSL_read: stripped %d ad segment(s) / %d ad period(s); %d -> %zu bytes",
             r.ad_segments, r.ad_periods, n, r.new_len);
        return static_cast<int>(r.new_len);   // caller sees the shortened body
    }
    return n;
}

// ── inflate hook (optional) ──────────────────────────────────────────────────
// int inflate(z_streamp strm, int flush);  — output lands in strm->next_out.
// The strip + z_stream rewind bookkeeping lives in inflate_filter.h's
// apply_after_inflate(), shared with the host unit test.
using inflate_t = int (*)(void*, int);
static inflate_t real_inflate = nullptr;

static int hook_inflate(void* strm, int flush) {
    pvfilter::ZStreamHead* z = static_cast<pvfilter::ZStreamHead*>(strm);
    unsigned char* out_before = z ? z->next_out : nullptr;

    int ret = real_inflate(strm, flush);

    pvfilter::FilterResult r = pvfilter::apply_after_inflate(z, out_before);
    if (r.modified) {
        LOGI("inflate: stripped %d seg / %d period; produced-body now %zu bytes",
             r.ad_segments, r.ad_periods, r.new_len);
    }
    return ret;
}

// ── install ──────────────────────────────────────────────────────────────────
static bool install_hook(const sigscan::Module& m,
                         const unsigned char* sig, const char* mask,
                         unsigned long fallback, const char* label,
                         void* replace, void** origin) {
    if (SIG_IS_UNSET(mask)) {
        LOGE("install[%s]: signature UNSET in offsets.h — run the Ghidra pass first", label);
        return false;
    }
    uintptr_t addr = sigscan::resolve(m, sig, mask, fallback, label);
    if (!addr) { LOGE("install[%s]: unresolved — not hooking", label); return false; }

    // ARMv7 Thumb note: if Ghidra shows this function as Thumb, the callable
    // address has bit0 set. sigscan finds instruction bytes at the aligned
    // address; Dobby detects Thumb from the address, so OR in the thumb bit
    // for Thumb targets. Uncomment per your Ghidra finding:
    // addr |= 1;

    int rc = DobbyHook(reinterpret_cast<void*>(addr), replace, origin);
    if (rc != 0) { LOGE("install[%s]: DobbyHook failed rc=%d", label, rc); return false; }
    LOGI("install[%s]: hooked @ %p", label, (void*)addr);
    return true;
}

static void install_all() {
    sigscan::Module m = sigscan::find_module(LIBIGNITE_SONAME);
    if (!m.valid()) {
        LOGE("install_all: %s not mapped yet — is loadLibrary too early?", LIBIGNITE_SONAME);
        return;
    }
    LOGI("install_all: target app %s, lib sha %s", LIBIGNITE_APP_VERSION, LIBIGNITE_SHA256);

#if ENABLE_SSL_READ_HOOK
    install_hook(m, SSL_READ_SIG, SSL_READ_MASK, SSL_READ_FALLBACK_OFFSET,
                 "SSL_read", (void*)hook_SSL_read, (void**)&real_SSL_read);
#endif
#if ENABLE_INFLATE_HOOK
    install_hook(m, INFLATE_SIG, INFLATE_MASK, INFLATE_FALLBACK_OFFSET,
                 "inflate", (void*)hook_inflate, (void**)&real_inflate);
#endif

    // Phase B (future): also hook the request side (libcurl curl_easy_setopt /
    // the DOWNLOADER request builder) to drop segment GETs whose URL contains
    // "/iad_", covering any markerless inline SSAI the manifest strip misses.
}

// ── entry ────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    LOGI("JNI_OnLoad: pvhook loaded, installing native hooks");
    install_all();
    return JNI_VERSION_1_6;
}
