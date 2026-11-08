// xray_dump.h — ONE-SHOT full-buffer capture of the in-engine X-Ray carousel
// structures, for offline schema reverse-engineering. DIAGNOSTIC ONLY: compiled
// in solely under -DPV_DUMP_XRAY=1, never in a shipping build.
//
// WHY: the raw liveExploreUpdatesV3 wire JSON (2-7 KB) does NOT cross libignite's
// memcpy as a contiguous, array-complete blob — it is reassembled/parsed from
// libcurl chunks. What DOES cross reliably are the large (19-228 KB) POST-PARSE
// in-engine buffers carrying `shopCardData` / `bettingcarousel` / `carouselType`.
// To strip Shop/Betting host-independently we must model THAT schema, so we need
// one complete sample of each.
//
// HOW: on the first buffer matching a Shop marker and the first matching a Betting
// marker, base64-encode the whole buffer and emit it to logcat in <=3500-char
// chunks framed by stable markers:
//   PVDUMP shop  BEGIN n=<len> chunks=<k>
//   PVDUMP shop  [i/k] <base64>
//   PVDUMP shop  END
// Each rail type dumps at most once (atomic latch), so the hot path is touched
// for exactly two buffers in the whole session — no sustained logging, no jank.
// Reassemble offline: concatenate the chunk payloads in order and base64-decode.

#pragma once
#include <cstddef>
#include <cstring>
#include <atomic>
#include <android/log.h>

namespace pvdump {

inline void b64_emit(const char* tag, const char* src, size_t n) {
    static const char* T = "PVNativeHook";
    static const char B[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    // Encode to a heap buffer (base64 is 4/3 * n, round up).
    size_t outlen = ((n + 2) / 3) * 4;
    char* out = static_cast<char*>(::malloc(outlen + 1));
    if (out == nullptr) return;
    size_t o = 0;
    for (size_t i = 0; i < n; i += 3) {
        unsigned v = static_cast<unsigned char>(src[i]) << 16;
        if (i + 1 < n) v |= static_cast<unsigned char>(src[i + 1]) << 8;
        if (i + 2 < n) v |= static_cast<unsigned char>(src[i + 2]);
        out[o++] = B[(v >> 18) & 0x3F];
        out[o++] = B[(v >> 12) & 0x3F];
        out[o++] = (i + 1 < n) ? B[(v >> 6) & 0x3F] : '=';
        out[o++] = (i + 2 < n) ? B[v & 0x3F] : '=';
    }
    out[o] = '\0';

    const size_t kChunk = 3500;
    size_t chunks = (o + kChunk - 1) / kChunk;
    __android_log_print(ANDROID_LOG_INFO, T, "PVDUMP %s BEGIN n=%zu b64=%zu chunks=%zu", tag, n, o, chunks);
    for (size_t c = 0; c < chunks; ++c) {
        size_t off = c * kChunk;
        size_t len = (off + kChunk <= o) ? kChunk : (o - off);
        char save = out[off + len]; out[off + len] = '\0';
        __android_log_print(ANDROID_LOG_INFO, T, "PVDUMP %s [%zu/%zu] %s", tag, c + 1, chunks, out + off);
        out[off + len] = save;
    }
    __android_log_print(ANDROID_LOG_INFO, T, "PVDUMP %s END", tag);
    ::free(out);
}

// Call from the memcpy gate. One dump per rail type, ever.
inline void maybe_dump(const char* buf, size_t n) {
    static std::atomic<bool> shop_done{false};
    static std::atomic<bool> bet_done{false};
    if (n < 2048) return;  // in-engine carousels are large; skip small fragments

    if (!shop_done.load(std::memory_order_relaxed) &&
        (::memmem(buf, n, "shopCardData", 12) != nullptr ||
         ::memmem(buf, n, "shopcarousel", 12) != nullptr)) {
        bool expected = false;
        if (shop_done.compare_exchange_strong(expected, true)) b64_emit("shop", buf, n);
    }
    if (!bet_done.load(std::memory_order_relaxed) &&
        (::memmem(buf, n, "bettingcarousel", 15) != nullptr ||
         ::memmem(buf, n, "popularBetOddsTile", 18) != nullptr)) {
        bool expected = false;
        if (bet_done.compare_exchange_strong(expected, true)) b64_emit("bet", buf, n);
    }
}

} // namespace pvdump
