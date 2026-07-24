// remote_strip.h — the proven in-place, same-length ad-strip, ported from the
// verified Frida bench (frida/cmod-strip2.js: scanVal/parseComplete/blankRanges).
//
// This is intentionally NOT the same algorithm as prs_filter.cpp. prs_filter.cpp
// was written for the (now-dead) inflate/SSL_read seam: it rebuilds a shrunk
// copy and assumes the buffer holds one complete, well-formed body. The memcpy
// seam is different in a way that makes that assumption unsafe: libignite
// copies the intraTitlePlaylist array in TRUNCATED 4-16KB chunks as well as the
// one large complete copy (~40-68KB) we actually want. Blanking a truncated
// chunk mid-element (e.g. inside a giant "Main" URL) corrupted playback in the
// original Frida bench (black screens) before the truncation guard was added.
//
// So this port keeps the bench's core safety invariant: parse the WHOLE array
// from '[' to its closing ']'; if ANY element or the array itself is truncated
// within the buffer, touch NOTHING. Only once the array is confirmed COMPLETE
// do we blank each Remote element (object + its trailing/leading comma) with
// ASCII spaces — same length in, same length out, so the JSON stays
// structurally valid and no length/offset bookkeeping is needed downstream.
//
// Pure, no allocation, no Android/zlib dependency — host-unit-testable exactly
// like manifest_filter.h / prs_filter.h.
#pragma once

#include <cstddef>

namespace pvfilter {

struct RemoteStripResult {
    bool found_marker = false;   // "intraTitlePlaylist":[ was found in the buffer
    bool complete = false;       // the array parsed to a closing ']' with no truncated element
    bool modified = false;       // at least one Remote element was blanked
    int remote_items = 0;        // count of Remote elements blanked
    int total_items = 0;         // count of elements in the array (informational)
};

// Cheap pre-check used to gate the hot path before the full scan: does the
// buffer contain the literal marker `"intraTitlePlaylist":[`? Mirrors
// cmod-strip2.js's CModule find_marker (byte-anchor before any parsing).
bool find_intra_title_playlist(const char* buf, size_t len, size_t* out_marker_pos);

// Scans the buffer for "intraTitlePlaylist":[ and, ONLY if the array is
// completely present (closing ']', no truncated element), blanks every
// {"type":"Remote",...} element (plus its separating comma) in place with
// ASCII spaces. `buf` must be writable; length is never changed.
// If the array is truncated or the marker isn't found, `buf` is left
// untouched and the result reflects why.
RemoteStripResult strip_remote_items(char* buf, size_t len);

} // namespace pvfilter
