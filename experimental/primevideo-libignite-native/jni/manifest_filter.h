// manifest_filter.h — plaintext ad-strip over an HTTP response body.
//
// Pure and side-effect-free apart from the in-place edit: no allocation beyond
// the caller's buffer, no logging dependency (a hook-supplied callback reports
// counts). This is the piece worth unit-testing on the PC against real captured
// manifests before ever putting it on-device.
#pragma once

#include <cstddef>

namespace pvfilter {

// Result of a filter pass.
struct FilterResult {
    bool   is_manifest = false;  // did the buffer look like an HLS/DASH manifest?
    bool   modified    = false;  // did we remove anything?
    size_t new_len     = 0;      // valid byte count after the (in-place) strip
    int    ad_segments = 0;      // HLS ad segments removed
    int    ad_periods  = 0;      // DASH ad periods removed
};

// Inspect `buf[0..len)`. If it's a manifest, strip ad entries IN PLACE
// (only ever shrinks — safe for a fixed-size read buffer) and return the new
// length in `.new_len`. If it's not a manifest, returns is_manifest=false and
// leaves the buffer untouched (new_len == len).
//
// `buf` must be writable. The strip never grows the buffer.
FilterResult filter(char* buf, size_t len);

// Cheap first-bytes classifier; exposed for the hook's fast reject path so it
// can skip filter() entirely on the overwhelming majority of reads that aren't
// manifests. Skips leading whitespace/BOM.
enum class Kind { NotManifest, Hls, Dash };
Kind sniff(const char* buf, size_t len);

} // namespace pvfilter
