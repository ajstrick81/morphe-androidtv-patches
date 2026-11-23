// xray_strip.h — remove Prime Video live X-Ray *commercial* rails (Shop + Betting)
// from the liveExploreUpdatesV3 feed, in-place, at the libignite memcpy seam.
//
// WHY: on live sports (e.g. NFL) the side-panel "shopping"/"bet tracking" overlay
// is NOT the SSAI video ad — it is the X-Ray LiveExplore feed. The feed is a
// JSON patch document:
//
//   {"updates":{"items":[ <op>, <op>, ... ], "cdnUpdateConfig":{...}}}
//
// where each <op> is {"op":"update","path":"items/{<mode>}/...","value":{...}}.
// The modeselector (the row of X-Ray tabs) is DATA-DRIVEN from which mode
// components appear, so removing every op whose path references the commercial
// modes {betting} / {shop} drops those tabs AND their content, while the benign
// modes {stats} / {keymoments} (scores, standings, key moments) are untouched.
//
// HOW (mirrors rego_filter.h): find the top-level updates.items array, walk its
// elements with the string/escape-aware json_match_bracket(), and for any element
// whose bytes contain "{betting}" or "{shop}", blank the element interior to
// spaces — leaving the enclosing "{"/"}" so the array stays syntactically valid
// (an empty op object the patch processor ignores). Length is NEVER changed, so
// this cannot trip a Content-Length/decompression mismatch. Truncated buffers
// (array or element unclosed within the buffer) are left untouched, exactly like
// the regolith strip, so a copy that splits an element mid-flight is never
// half-edited.

#pragma once
#include <cstddef>
#include <cstring>
#include "rego_filter.h"   // pvfilter::json_match_bracket

namespace pvfilter {

struct XrayStripResult {
    bool found   = false;   // a commercial-rail op was present
    bool complete = false;  // the updates.items array closed within the buffer
    int  blanked = 0;       // op elements neutralized
};

// Locate the top-level updates.items '[' opener. Returns (size_t)-1 if not found.
inline size_t find_updates_items(const char* buf, size_t n) {
    const void* up = ::memmem(buf, n, "\"updates\"", 9);
    if (up == nullptr) return static_cast<size_t>(-1);
    size_t from = static_cast<size_t>(static_cast<const char*>(up) - buf);
    const void* it = ::memmem(buf + from, n - from, "\"items\"", 7);
    if (it == nullptr) return static_cast<size_t>(-1);
    size_t p = static_cast<size_t>(static_cast<const char*>(it) - buf) + 7;
    while (p < n && buf[p] != '[') {           // skip ':' and whitespace to the '['
        if (buf[p] != ':' && buf[p] != ' ' && buf[p] != '\t' &&
            buf[p] != '\n' && buf[p] != '\r') return static_cast<size_t>(-1);
        ++p;
    }
    return (p < n) ? p : static_cast<size_t>(-1);
}

// buf must be writable; length is never changed. apply=false => dry run (count only).
inline XrayStripResult strip_xray_commercial_rails(char* buf, size_t n, bool apply = true) {
    XrayStripResult r;
    // Cheap signature gate: only the LiveExplore feed carries these path tokens.
    bool has_bet  = ::memmem(buf, n, "{betting}", 9) != nullptr;
    bool has_shop = ::memmem(buf, n, "{shop}", 6) != nullptr;
    if (!has_bet && !has_shop) return r;

    size_t arr = find_updates_items(buf, n);
    if (arr == static_cast<size_t>(-1)) return r;
    size_t arr_close = json_match_bracket(buf, n, arr);   // ']' of updates.items
    if (arr_close == static_cast<size_t>(-1)) return r;   // truncated — leave whole buffer
    r.complete = true;

    size_t p = arr + 1;
    while (p < arr_close) {
        char c = buf[p];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') { ++p; continue; }
        if (c != '{') break;                              // not an object element — stop
        size_t close = json_match_bracket(buf, n, p);     // '}' of this op object
        if (close == static_cast<size_t>(-1) || close > arr_close) break;  // truncated element
        size_t len = close - p + 1;
        if (::memmem(buf + p, len, "{betting}", 9) != nullptr ||
            ::memmem(buf + p, len, "{shop}", 6) != nullptr) {
            r.found = true;
            if (apply) { for (size_t i = p + 1; i < close; ++i) buf[i] = ' '; }
            ++r.blanked;
        }
        p = close + 1;
    }
    return r;
}

} // namespace pvfilter
