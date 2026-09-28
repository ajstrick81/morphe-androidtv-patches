// rego_filter.h — pure, host-unit-testable helpers for the regolith getVideoAds
// ad-decision response (PATH 2). Extracted from hooks.cpp so the same code the
// hook runs is exercised by test_remote_strip.cpp — no drift between test and
// ship. Header-only, no allocation, no Android/zlib deps (the same style as the
// research-era manifest_filter.h / prs_filter.h, now kept in
// experimental/native-adstrip-toolkit/examples/primevideo/jni/).
//
// Two operations, both same-length and string/escape aware:
//   json_match_bracket()       — matched ']'/'}' for an opener, or -1 if the
//                                buffer is truncated before it closes.
//   blank_complete_media_urls()— blank every COMPLETE "urls":[ ... ] media array
//                                to an empty "urls":[] (interior -> spaces). Used
//                                on the TRUNCATED pre-roll buffer, where the top
//                                "playlist" array never closes but the ad's media
//                                URL list closes before the cut. Removing just the
//                                fetchable media starves the ad while the
//                                black-screen invariant holds (a bracket cut
//                                mid-body is never touched — json_match_bracket
//                                returns -1 for it and we stop).
#pragma once

#include <cstddef>
#include <cstring>
#include <string.h>   // memmem (bionic / glibc)

namespace pvfilter {

// String/escape-aware matched-bracket finder: index of the ']'/'}' that closes
// the opener at `open`, or (size_t)-1 if unclosed within [open, n).
inline size_t json_match_bracket(const char* buf, size_t n, size_t open) {
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

// Blank every COMPLETE `"urls":[ ... ]` array whose closing ']' is present in
// [from, n). Interior bytes become ASCII spaces; the brackets stay, yielding a
// valid, same-length empty `"urls":[]`. Stops at the first truncated (unclosed)
// urls array, leaving it and everything after untouched. Returns the count
// blanked. `buf` must be writable; length is never changed.
inline int blank_complete_media_urls(char* buf, size_t n, size_t from) {
    static const char kNeedle[] = "\"urls\":[";
    static const size_t kNeedleLen = sizeof(kNeedle) - 1;
    int blanked = 0;
    size_t p = from;
    while (p < n) {
        const void* hit = memmem(buf + p, n - p, kNeedle, kNeedleLen);
        if (hit == nullptr) break;
        size_t open  = static_cast<size_t>(static_cast<const char*>(hit) - buf) + kNeedleLen - 1; // '['
        size_t close = json_match_bracket(buf, n, open);
        if (close == static_cast<size_t>(-1)) break;   // truncated tail — leave it
        if (close > open + 1)
            for (size_t i = open + 1; i < close; ++i) buf[i] = ' ';
        ++blanked;
        p = close + 1;
    }
    return blanked;
}

} // namespace pvfilter
