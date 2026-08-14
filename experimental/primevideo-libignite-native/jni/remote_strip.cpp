#include "remote_strip.h"

#include <cstring>
#include <string.h>   // memmem (bionic)

// ISSUE #14 RESTART FIX toggle. When 1, Remote ad items that resolve via
// getVideoAds are LEFT IN PLACE (not blanked) so the app resolves them and the
// geometry-safe PATH 2 (maybe_empty_regolith) empties the resolved response —
// the app's designed clean empty-break path — instead of PATH 1 structurally
// removing them, which leaves PeriodTailor a ~ad-length gap it fills with
// non-existent media -> content resets to start (see hooks.cpp / memory notes).
#ifndef PV_SKIP_GVA_REMOTES
#define PV_SKIP_GVA_REMOTES 0
#endif

namespace pvfilter {

namespace {

constexpr char kMarker[] = "\"intraTitlePlaylist\":[";
constexpr size_t kMarkerLen = sizeof(kMarker) - 1; // exclude trailing NUL
constexpr size_t kTrunc = static_cast<size_t>(-1);
constexpr size_t kMaxElems = 128; // real PRS playlists are a handful of items; bail safe if exceeded

bool is_ws(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

size_t skip_ws(const char* s, size_t len, size_t i) {
    while (i < len && is_ws(s[i])) ++i;
    return i;
}

// Returns the index just past the value starting at s[i], or kTrunc if the
// value's end (closing quote/brace/bracket) is not present within [0,len).
// Direct port of cmod-strip2.js's scanVal.
size_t scan_val(const char* s, size_t len, size_t i) {
    if (i >= len) return kTrunc;
    char c = s[i];

    if (c == '"') {
        ++i;
        while (i < len) {
            if (s[i] == '\\') { i += 2; continue; }
            if (s[i] == '"') return i + 1;
            ++i;
        }
        return kTrunc;
    }
    if (c == '{' || c == '[') {
        char open = c, close = (c == '{') ? '}' : ']';
        int depth = 0;
        while (i < len) {
            char d = s[i];
            if (d == '"') {
                size_t j = scan_val(s, len, i);
                if (j == kTrunc) return kTrunc;
                i = j;
                continue;
            }
            if (d == open) { ++depth; ++i; }
            else if (d == close) { --depth; ++i; if (depth == 0) return i; }
            else ++i;
        }
        return kTrunc;
    }
    // number / true / false / null: run to the next structural delimiter.
    // Running off the end of the buffer without hitting one is truncation.
    while (i < len && s[i] != ',' && s[i] != '}' && s[i] != ']' && !is_ws(s[i])) ++i;
    if (i >= len) return kTrunc;
    return i;
}

// Anywhere-in-range check for "type":"Remote" (mirrors the JS regex
// /"type"\s*:\s*"Remote"/ applied to the element's substring: keep scanning
// past a literal "type" occurrence that isn't actually followed by the key's
// colon+value, since it could be a coincidental substring elsewhere).
bool is_remote_type(const char* s, size_t start, size_t end) {
    constexpr char key[] = "\"type\"";
    constexpr size_t keylen = sizeof(key) - 1;
    constexpr char val[] = "\"Remote\"";
    constexpr size_t vallen = sizeof(val) - 1;
    if (end < start + keylen) return false;
    for (size_t p = start; p + keylen <= end; ++p) {
        if (memcmp(s + p, key, keylen) != 0) continue;
        size_t q = skip_ws(s, end, p + keylen);
        if (q < end && s[q] == ':') {
            q = skip_ws(s, end, q + 1);
            if (q + vallen <= end && memcmp(s + q, val, vallen) == 0) return true;
        }
    }
    return false;
}

struct Elem {
    size_t start = 0, end = 0;
    bool is_remote = false;
    bool has_comma = false;
    size_t comma_pos = 0;
};

struct ParseResult {
    size_t rb = 0;
    size_t elem_count = 0;
    Elem elems[kMaxElems];
};

// Parses the array starting at s[lb] ('['). Returns false (untouched-safe) if
// the array is truncated, malformed, or has more than kMaxElems elements.
// Direct port of cmod-strip2.js's parseComplete.
bool parse_complete(const char* s, size_t len, size_t lb, ParseResult& out) {
    if (lb >= len || s[lb] != '[') return false;
    size_t i = skip_ws(s, len, lb + 1);
    out.elem_count = 0;
    while (i < len) {
        if (s[i] == ']') { out.rb = i; return true; }
        if (out.elem_count >= kMaxElems) return false;

        size_t es = i;
        size_t ee = scan_val(s, len, i);
        if (ee == kTrunc) return false;

        Elem& el = out.elems[out.elem_count];
        el.start = es;
        el.end = ee;
        el.is_remote = is_remote_type(s, es, ee);
        el.has_comma = false;
        ++out.elem_count;

        i = skip_ws(s, len, ee);
        if (i < len && s[i] == ',') {
            el.has_comma = true;
            el.comma_pos = i;
            i = skip_ws(s, len, i + 1);
        } else if (i < len && s[i] == ']') {
            out.rb = i;
            return true;
        } else {
            return false; // unexpected char, or ran off the end -> unsafe
        }
    }
    return false; // ran off the end without ']' -> truncated
}

} // namespace

bool find_intra_title_playlist(const char* buf, size_t len, size_t* out_marker_pos) {
    if (!buf || len < kMarkerLen) return false;

    // HOT PATH. Once the real (IFUNC-resolved) memcpy is hooked this runs on
    // every copy in the size gate, so a memcmp() at every byte offset is far
    // too slow (ANR risk on 256KB buffers). Mirror the verified Frida bench's
    // CModule find_marker instead: use memchr to jump between candidate '"'
    // bytes, then a 3-byte anchor test ("in" + 'T' at +6) before paying for
    // the full compare. Semantics are identical to the naive scan.
    const char* const last = buf + len - kMarkerLen;  // last valid start offset
    const char* p = buf;
    while (p <= last) {
        const char* q = static_cast<const char*>(
            memchr(p, '"', static_cast<size_t>(last - p) + 1));
        if (q == nullptr) return false;
        // Safe: q <= last, and kMarkerLen (22) > 6, so q[1..6] are in bounds.
        if (q[1] == 'i' && q[2] == 'n' && q[6] == 'T' &&
            memcmp(q, kMarker, kMarkerLen) == 0) {
            if (out_marker_pos) *out_marker_pos = static_cast<size_t>(q - buf);
            return true;
        }
        p = q + 1;
    }
    return false;
}

// Blank a single (fully-closed) element in place with spaces, taking an adjacent
// comma with it so the surrounding array stays structurally valid. Same length
// in, same length out. `prev_has_comma` is whether the preceding element carried
// a trailing comma (used only for the last-element-in-a-complete-array case).
void blank_elem(char* buf, const Elem& el, bool prev_has_comma, size_t prev_comma_pos) {
    size_t rs, re;
    if (el.has_comma) {
        rs = el.start;
        re = el.comma_pos + 1;
    } else if (prev_has_comma) {
        rs = prev_comma_pos;
        re = el.end;
    } else {
        rs = el.start;
        re = el.end;
    }
    if (re > rs) memset(buf + rs, ' ', re - rs);
}

RemoteStripResult strip_remote_items(char* buf, size_t len, bool blank_truncated_complete,
                                     bool apply) {
    RemoteStripResult result;

    size_t marker_pos;
    if (!find_intra_title_playlist(buf, len, &marker_pos)) return result;
    result.found_marker = true;

    size_t lb = marker_pos + kMarkerLen - 1; // marker ends in ":[" -> last char is '['
    ParseResult pr;
    if (!parse_complete(buf, len, lb, pr)) {
        // Truncated/malformed: the trailing element was cut mid-body and is NEVER
        // touched. The elements parse_complete recorded before it bailed are all
        // fully-closed. Count complete Remotes among them (diagnostic), and — if
        // enabled — blank them: they are whole {...} objects each followed by a
        // comma, so a same-length space-fill keeps the JSON structurally valid.
        for (size_t k = 0; k < pr.elem_count; ++k) {
            if (!pr.elems[k].is_remote) continue;
            ++result.trunc_complete_remotes;
            if (blank_truncated_complete) {
                // In a truncated array every recorded element is comma-followed,
                // so blank_elem takes the element + its trailing comma.
                if (apply) blank_elem(buf, pr.elems[k], false, 0);
                ++result.trunc_remote_blanked;
            }
        }
        result.trunc_complete_items = static_cast<int>(pr.elem_count);
        if (result.trunc_remote_blanked > 0) result.trunc_modified = true;
        return result; // complete stays false
    }

    result.complete = true;
    result.total_items = static_cast<int>(pr.elem_count);

    int remote_count = 0;
    for (size_t k = 0; k < pr.elem_count; ++k) {
        if (pr.elems[k].is_remote) ++remote_count;
    }
    if (remote_count == 0) return result;

    int blanked_count = 0;
    for (size_t k = 0; k < pr.elem_count; ++k) {
        const Elem& el = pr.elems[k];
        if (!el.is_remote) continue;
#if PV_SKIP_GVA_REMOTES
        // getVideoAds-resolved Remote: leave in place for PATH 2 to empty the
        // resolved response (geometry-safe). Removing it here desyncs PeriodTailor.
        if (memmem(buf + el.start, el.end - el.start, "getVideoAds", 11) != nullptr) {
            ++result.gva_skipped;
            continue;
        }
#endif
        bool prev_comma = (k > 0 && pr.elems[k - 1].has_comma);
        size_t prev_pos = (k > 0) ? pr.elems[k - 1].comma_pos : 0;
        if (apply) blank_elem(buf, el, prev_comma, prev_pos);
        ++blanked_count;
    }

    if (blanked_count > 0) {
        result.modified = true;
        result.remote_items = blanked_count;
    }
    return result;
}

} // namespace pvfilter
