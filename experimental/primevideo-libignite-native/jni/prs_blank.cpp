#include "prs_blank.h"

#include <cstring>
#include <vector>

// Faithful C port of frida/cmod-strip2.js. Function names mirror the JS
// (scanVal/parseComplete/blankRanges) so the two can be diffed side by side.
// Indices are absolute into the buffer, exactly as in the JS (where `s`
// starts at the memcpy src). We operate on raw bytes rather than the JS
// latin1 projection — strictly more correct, same behaviour on ASCII JSON.

namespace pvblank {
namespace {

// JS used exactly ' \t\n\r' — match that, not locale isspace() (which also
// eats \v\f). Faithfulness matters: the bench proved on this exact grammar.
inline bool is_ws(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

inline std::size_t skip_ws(const char* s, std::size_t i, std::size_t n) {
    while (i < n && is_ws(s[i])) ++i;
    return i;
}

// scanVal: returns the index just past the value starting at `i`, or -1 if the
// value is TRUNCATED (no closing quote/brace within the buffer). The -1
// sentinel is what makes "complete only" safe.
std::ptrdiff_t scan_val(const char* s, std::size_t i, std::size_t n) {
    if (i >= n) return -1;
    const char c = s[i];

    if (c == '"') {                       // string
        for (++i; i < n; ++i) {
            if (s[i] == '\\') { i += 2; continue; }   // skip escaped char
            if (s[i] == '"')  return (std::ptrdiff_t)(i + 1);
        }
        return -1;                        // no closing quote -> truncated
    }
    if (c == '{' || c == '[') {           // object / array
        const char open = c, close = (c == '{') ? '}' : ']';
        int depth = 0;
        while (i < n) {
            const char e = s[i];
            if (e == '"') {               // skip nested strings whole
                std::ptrdiff_t j = scan_val(s, i, n);
                if (j < 0) return -1;
                i = (std::size_t)j;
                continue;
            }
            if (e == open) { ++depth; ++i; }
            else if (e == close) { --depth; ++i; if (depth == 0) return (std::ptrdiff_t)i; }
            else ++i;
        }
        return -1;                        // never closed -> truncated
    }
    // number / true / false / null: run to the next structural delimiter.
    while (i < n && s[i] != ',' && s[i] != '}' && s[i] != ']' && !is_ws(s[i])) ++i;
    return (std::ptrdiff_t)i;
}

// isRemote: mirrors the JS regex /"type"\s*:\s*"Remote"/ over the element span.
// Naive by design — this is the exact predicate the bench validated across many
// titles. A global search: any "type" whose value is "Remote" flags the element.
bool elem_is_remote(const char* s, std::size_t start, std::size_t end) {
    static const char KEY[] = "\"type\"";
    const std::size_t klen = sizeof(KEY) - 1;
    for (std::size_t p = start; p + klen <= end; ++p) {
        if (std::memcmp(s + p, KEY, klen) != 0) continue;
        std::size_t q = skip_ws(s, p + klen, end);
        if (q < end && s[q] == ':') {
            q = skip_ws(s, q + 1, end);
            if (q + 8 <= end && std::memcmp(s + q, "\"Remote\"", 8) == 0) return true;
        }
        // keep scanning — a later "type" in the same element may be the Remote.
    }
    return false;
}

struct Elem {
    std::size_t start;
    std::size_t end;
    bool        is_remote;
    bool        has_comma;    // a ',' follows this element
    std::size_t comma;        // index of that ',' (valid iff has_comma)
};

// parseComplete: parse the whole array from the '[' at lb. Returns true and
// fills `out` only if the array is complete (closing ']' present, every element
// whole). Returns false -> DO NOT TOUCH THE BUFFER.
bool parse_complete(const char* s, std::size_t lb, std::size_t n,
                    std::vector<Elem>& out) {
    if (lb >= n || s[lb] != '[') return false;
    std::size_t i = skip_ws(s, lb + 1, n);
    while (i < n) {
        if (s[i] == ']') return true;                 // clean close
        Elem el;
        el.start = i;
        std::ptrdiff_t ee = scan_val(s, i, n);
        if (ee < 0) return false;                     // truncated element -> unsafe
        el.end = (std::size_t)ee;
        el.is_remote = elem_is_remote(s, el.start, el.end);
        el.has_comma = false;
        el.comma = 0;
        i = skip_ws(s, el.end, n);
        if (i < n && s[i] == ',') {
            el.has_comma = true;
            el.comma = i;
            out.push_back(el);
            i = skip_ws(s, i + 1, n);
        } else if (i < n && s[i] == ']') {
            out.push_back(el);
            return true;                              // last element, clean close
        } else {
            return false;                             // unexpected byte -> unsafe
        }
    }
    return false;                                     // ran off the end -> truncated
}

// Blank removed elements + surplus commas so the surviving elements read back as
// `[S1,S2,...,Sm]`, everything else spaces. Same length in -> JSON stays valid,
// buffer length unchanged.
//
// NOTE — deliberate divergence from cmod-strip2.js's blankRanges (documented in
// PRODUCTIZATION.md): the JS rule ("blank each Remote + its own trailing comma;
// for a trailing Remote blank the *preceding* comma") leaves a trailing comma
// for 2+ consecutive Remote elements at the array tail (`[Main,Remote,Remote]`
// -> `[Main, ]`). The bench never hit that shape (content ends on a Main), so
// both algorithms are byte-identical on every validated shape; this one is also
// correct when ads are last. Rule here: keep exactly the comma AFTER each
// surviving element that still has a survivor after it; blank every other comma.
int blank_ranges(char* buf, const std::vector<Elem>& elems) {
    // Index of the last surviving (non-Remote) element, or -1 if none survive.
    std::ptrdiff_t last_survivor = -1;
    for (std::size_t k = 0; k < elems.size(); ++k)
        if (!elems[k].is_remote) last_survivor = (std::ptrdiff_t)k;

    int blanked = 0;
    for (std::size_t k = 0; k < elems.size(); ++k) {
        const Elem& el = elems[k];
        if (el.is_remote) {                           // drop the ad element itself
            if (el.end > el.start) std::memset(buf + el.start, ' ', el.end - el.start);
            ++blanked;
        }
        // Keep el's trailing comma only if el survives AND a survivor follows it;
        // otherwise it is surplus -> blank it.
        if (el.has_comma) {
            bool keep = !el.is_remote && (std::ptrdiff_t)k < last_survivor;
            if (!keep) buf[el.comma] = ' ';
        }
    }
    return blanked;
}

} // namespace

bool find_marker(const char* buf, std::size_t len, std::size_t* lb_out) {
    static const char M[] = "intraTitlePlaylist";
    const std::size_t mlen = sizeof(M) - 1;           // 18
    // need marker + '"' + ':' + '[' = mlen + 3 bytes
    if (len < mlen + 3) return false;
    for (std::size_t i = 0; i + mlen + 3 <= len; ++i) {
        if (buf[i] != 'i') continue;                  // cheap first-byte anchor
        if (std::memcmp(buf + i, M, mlen) == 0 &&
            buf[i + mlen] == '"' && buf[i + mlen + 1] == ':' &&
            buf[i + mlen + 2] == '[') {
            if (lb_out) *lb_out = i + mlen + 2;        // index of '['
            return true;
        }
    }
    return false;
}

BlankResult blank_prs(char* buf, std::size_t len, bool apply_size_gate) {
    BlankResult r;
    if (!buf) return r;
    if (apply_size_gate && (len < kMinScan || len > kMaxScan)) return r;

    std::size_t lb;
    if (!find_marker(buf, len, &lb)) return r;
    r.marker_found = true;

    std::vector<Elem> elems;
    if (!parse_complete(buf, lb, len, elems)) return r;   // truncated -> untouched
    r.complete = true;
    r.elems = (int)elems.size();

    int n = blank_ranges(buf, elems);
    r.remote_blanked = n;
    r.blanked = (n > 0);
    return r;
}

} // namespace pvblank
