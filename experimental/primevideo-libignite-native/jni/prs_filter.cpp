#include "prs_filter.h"

#include <cctype>
#include <cstring>
#include <string>
#include <vector>

namespace pvfilter {

// Portable substring search over a non-NUL-terminated buffer (avoids GNU memmem
// so the host unit test builds under mingw as well as the NDK).
static const char* find_bytes(const char* hay, size_t hlen,
                              const char* needle, size_t nlen) {
    if (nlen == 0 || hlen < nlen) return nullptr;
    for (size_t i = 0; i + nlen <= hlen; ++i)
        if (memcmp(hay + i, needle, nlen) == 0) return hay + i;
    return nullptr;
}

// ── tiny JSON structural scanner ─────────────────────────────────────────────
// No JSON library in-process. We never re-serialize; we only need to find the
// byte range of values so we can splice ad entries out. String-, escape-, and
// nesting-aware. `i` must index the first char of a value; returns the index
// just past that value.
static size_t scan_value(const std::string& s, size_t i) {
    const size_t n = s.size();
    if (i >= n) return n;
    const char c = s[i];

    if (c == '"') {                       // string
        for (++i; i < n; ++i) {
            if (s[i] == '\\') { ++i; continue; }
            if (s[i] == '"')  return i + 1;
        }
        return n;
    }
    if (c == '{' || c == '[') {           // object / array
        const char open = c, close = (c == '{') ? '}' : ']';
        int depth = 0;
        while (i < n) {
            const char d = s[i];
            if (d == '"') { i = scan_value(s, i); continue; }   // skip strings whole
            if (d == open)  { ++depth; ++i; }
            else if (d == close) { --depth; ++i; if (depth == 0) return i; }
            else ++i;
        }
        return n;
    }
    // number / true / false / null: run to the next structural delimiter.
    while (i < n && s[i] != ',' && s[i] != '}' && s[i] != ']' &&
           !std::isspace((unsigned char)s[i])) ++i;
    return i;
}

static size_t skip_ws(const std::string& s, size_t i) {
    while (i < s.size() && std::isspace((unsigned char)s[i])) ++i;
    return i;
}

// Is this intraTitlePlaylist element an ad (type == "Remote")?
static bool elem_is_remote(const std::string& s, size_t start, size_t end) {
    // Search for the "type" key within the element and compare its value.
    size_t p = start;
    while (true) {
        p = s.find("\"type\"", p);
        if (p == std::string::npos || p >= end) return false;
        size_t q = skip_ws(s, p + 6);
        if (q < end && s[q] == ':') {
            q = skip_ws(s, q + 1);
            if (q + 8 <= end && s.compare(q, 8, "\"Remote\"") == 0) return true;
            return false;   // a "type" that isn't Remote — this element stays
        }
        p += 6;             // "type" as a substring of something else; keep looking
    }
}

// ── PRS detection ────────────────────────────────────────────────────────────
bool looks_like_prs(const char* buf, size_t len) {
    if (!buf || len < 32) return false;
    size_t i = 0;
    // skip UTF-8 BOM + whitespace
    if (len >= 3 && (unsigned char)buf[0] == 0xEF && (unsigned char)buf[1] == 0xBB &&
        (unsigned char)buf[2] == 0xBF) i = 3;
    while (i < len && std::isspace((unsigned char)buf[i])) ++i;
    if (i >= len || buf[i] != '{') return false;       // must be a JSON object
    return find_bytes(buf + i, len - i, "\"intraTitlePlaylist\"", 20) != nullptr;
}

// ── strip ────────────────────────────────────────────────────────────────────
// Rebuild the intraTitlePlaylist array keeping only non-Remote items; empty
// nonLinearAds. Both edits are range replacements on a working copy; we memcpy
// back only if the result shrank (it can't grow — we only delete).
FilterResult filter_prs(char* buf, size_t len) {
    FilterResult r;
    r.new_len = len;
    if (!looks_like_prs(buf, len)) return r;
    r.is_prs = true;

    std::string s(buf, len);

    // 1) intraTitlePlaylist: drop type:"Remote" elements.
    size_t key = s.find("\"intraTitlePlaylist\"");
    if (key != std::string::npos) {
        size_t colon = s.find(':', key + 20);
        size_t lb = (colon == std::string::npos) ? std::string::npos : s.find('[', colon);
        // guard: '[' must be the value (only whitespace between ':' and '[')
        if (lb != std::string::npos && skip_ws(s, colon + 1) == lb) {
            std::string kept;               // "elem,elem,..." (no brackets)
            size_t i = lb + 1;
            int removed = 0;
            while (true) {
                i = skip_ws(s, i);
                if (i >= s.size() || s[i] == ']') break;
                size_t estart = i;
                size_t eend = scan_value(s, i);
                if (elem_is_remote(s, estart, eend)) {
                    ++removed;
                } else {
                    if (!kept.empty()) kept.push_back(',');
                    kept.append(s, estart, eend - estart);
                }
                i = skip_ws(s, eend);
                if (i < s.size() && s[i] == ',') ++i;
            }
            size_t rb = i;                  // index of ']'
            if (removed > 0 && rb != std::string::npos && rb < s.size() && s[rb] == ']') {
                // replace s[lb+1 .. rb) with kept
                s.replace(lb + 1, rb - (lb + 1), kept);
                r.remote_items = removed;
                r.modified = true;
            }
        }
    }

    // 2) nonLinearAds: empty it (only if non-empty; only ever shrinks).
    size_t nk = s.find("\"nonLinearAds\"");
    if (nk != std::string::npos) {
        size_t colon = s.find(':', nk + 14);
        if (colon != std::string::npos) {
            size_t vs = skip_ws(s, colon + 1);
            if (vs < s.size() && (s[vs] == '{' || s[vs] == '[')) {
                size_t ve = scan_value(s, vs);
                const char* empty = (s[vs] == '{') ? "{}" : "[]";
                if (ve - vs > 2) {          // has content -> collapse to empty
                    s.replace(vs, ve - vs, empty);
                    r.modified = true;
                }
            }
        }
    }

    if (r.modified) {
        if (s.size() <= len) {              // must only shrink
            memcpy(buf, s.data(), s.size());
            r.new_len = s.size();
        } else {
            // Defensive: should be impossible (we only delete). Bail unmodified.
            r.modified = false;
            r.remote_items = 0;
            r.new_len = len;
        }
    }
    return r;
}

} // namespace pvfilter
