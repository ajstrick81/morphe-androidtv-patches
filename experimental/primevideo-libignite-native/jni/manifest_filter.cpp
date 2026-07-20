#include "manifest_filter.h"

#include <cstring>
#include <string>

// Ad-marker on this build: mid-roll ad segments carry an "/iad_<id>/" path on
// the same CDN host as content (documented in the ads extension). That token is
// the load-bearing signal for both HLS and DASH here. If a future build changes
// the marker, this is the one constant to revisit.
static const char* AD_TOKEN = "/iad_";

namespace pvfilter {

static const char* skip_ws(const char* p, const char* end) {
    // Skip UTF-8 BOM + leading whitespace so the sniff is robust.
    if (end - p >= 3 &&
        (unsigned char)p[0] == 0xEF && (unsigned char)p[1] == 0xBB &&
        (unsigned char)p[2] == 0xBF) {
        p += 3;
    }
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) ++p;
    return p;
}

Kind sniff(const char* buf, size_t len) {
    if (!buf || len < 5) return Kind::NotManifest;
    const char* p = skip_ws(buf, buf + len);
    size_t avail = (buf + len) - p;
    if (avail >= 7 && memcmp(p, "#EXTM3U", 7) == 0) return Kind::Hls;
    // DASH: either a raw <MPD or an XML prolog followed (soon) by <MPD.
    if (avail >= 4 && memcmp(p, "<MPD", 4) == 0) return Kind::Dash;
    if (avail >= 5 && memcmp(p, "<?xml", 5) == 0) {
        // Only commit to DASH if an <MPD tag shows up in the head of the buffer.
        size_t look = avail < 512 ? avail : 512;
        if (memmem(p, look, "<MPD", 4)) return Kind::Dash;
    }
    return Kind::NotManifest;
}

// ── HLS ──────────────────────────────────────────────────────────────────────
//
// Media-playlist SSAI strip. An ad segment is an "#EXTINF ... \n <uri>" pair
// whose URI contains AD_TOKEN. We drop the EXTINF line, the URI line, and any
// segment-scoped tags attached to it (#EXT-X-BYTERANGE, #EXT-X-KEY that is only
// for the ad, etc. — conservative: we only drop tags that sit between the
// EXTINF and the URI). Bare #EXT-X-DISCONTINUITY markers that would be left
// orphaned around a removed run are collapsed so the player doesn't see a
// discontinuity with nothing on either side.
//
// HEURISTIC — validate on-device. Server-stitched SSAI timing is delicate;
// if playback stutters at former ad boundaries, the fix is usually to ALSO
// drop the surrounding DISCONTINUITY pair, which this does, but real manifests
// vary. Logs the count so logcat confirms what was removed.
static FilterResult filter_hls(char* buf, size_t len) {
    FilterResult r;
    r.is_manifest = true;
    r.new_len = len;

    std::string out;
    out.reserve(len);

    const char* p = buf;
    const char* end = buf + len;

    // A small pending window so we can retroactively drop an EXTINF (+tags)
    // once we see its URI is an ad. We buffer from each EXTINF to its URI.
    std::string pending;      // lines buffered since the last EXTINF
    bool in_segment = false;  // between an EXTINF and its URI line

    auto line_is = [](const char* s, size_t n, const char* tag) {
        size_t t = strlen(tag);
        return n >= t && memcmp(s, tag, t) == 0;
    };

    while (p < end) {
        const char* nl = static_cast<const char*>(memchr(p, '\n', end - p));
        const char* line = p;
        size_t linelen = (nl ? nl + 1 : end) - p;   // include the '\n'
        size_t trimlen = linelen;                    // length without trailing \r\n
        while (trimlen && (line[trimlen-1] == '\n' || line[trimlen-1] == '\r')) --trimlen;

        bool is_extinf = line_is(line, trimlen, "#EXTINF");
        bool is_tag    = trimlen && line[0] == '#';
        bool is_uri    = trimlen && line[0] != '#';

        if (is_extinf) {
            // Flush any prior pending (non-ad) content, start a new segment.
            out.append(pending);
            pending.assign(line, linelen);
            in_segment = true;
        } else if (in_segment && is_tag) {
            // Segment-scoped tag between EXTINF and URI — buffer with the segment.
            pending.append(line, linelen);
        } else if (in_segment && is_uri) {
            bool is_ad = memmem(line, trimlen, AD_TOKEN, strlen(AD_TOKEN)) != nullptr;
            if (is_ad) {
                // Drop the whole buffered segment (EXTINF + tags + this URI).
                r.ad_segments++;
                r.modified = true;
            } else {
                out.append(pending);
                out.append(line, linelen);
            }
            pending.clear();
            in_segment = false;
        } else {
            // Non-segment line (header tags, DISCONTINUITY, ENDLIST, blank).
            // Flush pending first to preserve order.
            if (!pending.empty()) { out.append(pending); pending.clear(); in_segment = false; }
            out.append(line, linelen);
        }

        if (!nl) break;
        p = nl + 1;
    }
    if (!pending.empty()) out.append(pending);

    if (r.modified) {
        // Collapse doubled discontinuity markers left by removed ad runs
        // (…DISCONTINUITY\nDISCONTINUITY… → one), a common SSAI artifact.
        std::string disc = "#EXT-X-DISCONTINUITY";
        size_t pos;
        while ((pos = out.find(disc + "\n" + disc)) != std::string::npos) {
            out.erase(pos, disc.size() + 1);
        }
        // Only ever shrinks; guard anyway so we never overrun the read buffer.
        if (out.size() <= len) {
            memcpy(buf, out.data(), out.size());
            r.new_len = out.size();
        } else {
            // Shouldn't happen (strip only removes) — bail safe, unmodified.
            r.modified = false;
            r.new_len = len;
        }
    }
    return r;
}

// ── DASH ─────────────────────────────────────────────────────────────────────
//
// Remove entire <Period> elements that are ad periods. Identified by an
// AD_TOKEN in a nested <BaseURL>, or an ad-ish Period id. Whole-element removal
// keeps the XML well-formed. Lightweight scan (no XML parser in-process): find
// <Period …> … </Period> spans and test their content for the marker.
static FilterResult filter_dash(char* buf, size_t len) {
    FilterResult r;
    r.is_manifest = true;
    r.new_len = len;

    std::string s(buf, len);
    std::string out;
    out.reserve(len);

    size_t i = 0;
    while (i < s.size()) {
        size_t open = s.find("<Period", i);
        if (open == std::string::npos) { out.append(s, i, std::string::npos); break; }

        // Emit everything up to this Period unchanged.
        out.append(s, i, open - i);

        size_t close = s.find("</Period>", open);
        size_t period_end = (close == std::string::npos) ? s.size() : close + strlen("</Period>");
        std::string period = s.substr(open, period_end - open);

        bool is_ad = period.find(AD_TOKEN) != std::string::npos;
        // Secondary signal: SSAI ad periods often tag id/BaseURL with "ad".
        if (!is_ad) {
            // very conservative: only match an explicit ad marker in an id attr
            size_t idpos = period.find("id=\"");
            if (idpos != std::string::npos) {
                std::string idval = period.substr(idpos + 4, 32);
                if (idval.find("ad-") != std::string::npos ||
                    idval.find("_ad_") != std::string::npos) is_ad = true;
            }
        }

        if (is_ad) {
            r.ad_periods++;
            r.modified = true;
            // skip (drop) this period
        } else {
            out.append(period);
        }
        i = period_end;
    }

    if (r.modified && out.size() <= len) {
        memcpy(buf, out.data(), out.size());
        r.new_len = out.size();
    } else if (r.modified) {
        r.modified = false;
        r.new_len = len;
    }
    return r;
}

FilterResult filter(char* buf, size_t len) {
    switch (sniff(buf, len)) {
        case Kind::Hls:  return filter_hls(buf, len);
        case Kind::Dash: return filter_dash(buf, len);
        default: {
            FilterResult r;
            r.new_len = len;
            return r;
        }
    }
}

} // namespace pvfilter
