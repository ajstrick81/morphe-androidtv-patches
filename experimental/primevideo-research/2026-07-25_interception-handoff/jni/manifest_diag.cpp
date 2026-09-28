#include "manifest_diag.h"

#include <cstring>

namespace pvfilter {

namespace {

bool is_ws(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

// case-sensitive substring search within [s, s+len).
const char* find_sub(const char* s, size_t len, const char* needle, size_t nlen) {
    if (nlen == 0 || len < nlen) return nullptr;
    const char* last = s + len - nlen;
    for (const char* p = s; p <= last; ++p) {
        if (p[0] == needle[0] && memcmp(p, needle, nlen) == 0) return p;
    }
    return nullptr;
}

} // namespace

size_t find_bytes(const char* buf, size_t len, const char* needle) {
    if (!buf || !needle) return static_cast<size_t>(-1);
    size_t nlen = 0; while (needle[nlen]) ++nlen;
    const char* p = find_sub(buf, len, needle, nlen);
    return p ? static_cast<size_t>(p - buf) : static_cast<size_t>(-1);
}

size_t rfind_bytes(const char* buf, size_t limit, const char* needle) {
    if (!buf || !needle) return static_cast<size_t>(-1);
    size_t nlen = 0; while (needle[nlen]) ++nlen;
    if (nlen == 0 || limit < nlen) return static_cast<size_t>(-1);
    for (size_t i = limit - nlen + 1; i-- > 0; ) {
        if (buf[i] == needle[0] && memcmp(buf + i, needle, nlen) == 0) return i;
        if (i == 0) break;
    }
    return static_cast<size_t>(-1);
}

bool looks_like_dash(const char* buf, size_t len) {
    if (!buf || len < 5) return false;
    size_t head = len < 256 ? len : 256;
    size_t i = 0;
    while (i < head && is_ws(buf[i])) ++i;  // skip leading whitespace/BOM-ish ws
    // Direct start signatures.
    if (i + 5 <= len && memcmp(buf + i, "<?xml", 5) == 0) return true;
    if (i + 4 <= len && memcmp(buf + i, "<MPD", 4) == 0) return true;
    // Or the MPEG-DASH namespace anywhere in the head (covers mid-chunk starts).
    if (find_sub(buf, head, "urn:mpeg:dash", 13) != nullptr) return true;
    if (find_sub(buf, head, "<MPD", 4) != nullptr) return true;
    return false;
}

ManifestScan scan_manifest(const char* buf, size_t len) {
    ManifestScan out;
    if (!buf || len < 8) return out;
    out.looks_like_manifest = looks_like_dash(buf, len);

    // Enumerate "<Period" open tags. Jump between '<' candidates with memchr,
    // then confirm the element name is exactly "Period" (next char is ws or '>').
    const char* const end = buf + len;
    const char* p = buf;
    while (p < end && out.period_count < ManifestScan::kMaxPeriods) {
        const char* lt = static_cast<const char*>(
            memchr(p, '<', static_cast<size_t>(end - p)));
        if (lt == nullptr) break;
        // Need "<Period" (7 chars) plus a following delimiter.
        if (end - lt >= 8 && memcmp(lt, "<Period", 7) == 0 &&
            (is_ws(lt[7]) || lt[7] == '>')) {
            // Find the '>' that closes this open tag (attribute values here don't
            // contain '>', which holds for these SSAI manifests).
            const char* gt = static_cast<const char*>(
                memchr(lt, '>', static_cast<size_t>(end - lt)));
            if (gt == nullptr) break;  // open tag truncated in this buffer
            int idx = out.period_count;
            out.tag_start[idx] = static_cast<size_t>(lt - buf);
            out.tag_open_end[idx] = static_cast<size_t>(gt - buf) + 1;

            // Classify: does the open tag's text contain an ad id?
            size_t taglen = static_cast<size_t>(gt - lt) + 1;
            bool ad = find_sub(lt, taglen, "Adbreak", 7) != nullptr ||
                      find_sub(lt, taglen, "Card", 4) != nullptr;
            out.is_ad[idx] = ad;
            if (ad) {
                ++out.ad_period_count;
                if (out.first_ad_idx < 0) out.first_ad_idx = idx;
            }
            ++out.period_count;
            p = gt + 1;
        } else {
            p = lt + 1;
        }
    }
    return out;
}

} // namespace pvfilter
