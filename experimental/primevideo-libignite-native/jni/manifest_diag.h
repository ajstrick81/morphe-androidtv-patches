// manifest_diag.h — READ-ONLY diagnostic scanner for DASH .mpd buffers.
//
// Purpose: on-device confirmation that TV-show ad breaks are physically embedded
// in the DASH manifest as multi-period <Period id="Adbreak*"/"Card_*"> elements
// (the SSAI mechanism, distinct from the client-stitched PRS "Remote" items that
// remote_strip.cpp handles). This module NEVER writes to the buffer — it only
// locates <Period ...> open tags so hooks.cpp can log their structure and the
// sizes at which manifest bytes flow through libignite's memcpy imports. That
// size data decides whether an eventual in-process manifest surgery is even
// reachable through the existing GOT hook (which gates copies at <=256KB).
//
// Pure, no allocation, no Android dependency — host-unit-testable exactly like
// remote_strip.h.
#pragma once

#include <cstddef>

namespace pvfilter {

struct ManifestScan {
    bool looks_like_manifest = false;  // DASH/XML signature near the buffer start
    int period_count = 0;              // number of <Period ...> open tags found
    int ad_period_count = 0;           // of those, how many are Adbreak*/Card_*
    int first_ad_idx = -1;             // index into the arrays of the first ad period, or -1

    static constexpr int kMaxPeriods = 128;
    size_t tag_start[kMaxPeriods];     // offset of '<' in "<Period"
    size_t tag_open_end[kMaxPeriods];  // offset just past the '>' closing the open tag
    bool is_ad[kMaxPeriods];           // id contains "Adbreak" or "Card"
};

// Case-sensitive byte search: returns the offset of the first occurrence of
// `needle` in [buf, buf+len), or SIZE_MAX if absent. Used by the PRS ad-field
// diagnostic in hooks.cpp to locate ad-scheduling keys.
size_t find_bytes(const char* buf, size_t len, const char* needle);

// Last occurrence of `needle` strictly before offset `limit` in buf, or SIZE_MAX.
// Used to identify which JSON array key most-recently precedes an ad URL.
size_t rfind_bytes(const char* buf, size_t limit, const char* needle);

// Cheap O(1)-ish pre-check: do the first bytes look like a DASH manifest
// (leading "<?xml"/"<MPD", or the MPEG-DASH namespace within the head)?
// Used to avoid the full period scan on non-manifest copies.
bool looks_like_dash(const char* buf, size_t len);

// Read-only scan enumerating every "<Period ...>" open tag in [buf, buf+len).
// Bounded by kMaxPeriods. Sets looks_like_manifest via looks_like_dash().
// Classifies a period as ad when its open tag's id= attribute contains
// "Adbreak" or "Card" (the WASM PeriodTailor names seen in logcat).
ManifestScan scan_manifest(const char* buf, size_t len);

} // namespace pvfilter
