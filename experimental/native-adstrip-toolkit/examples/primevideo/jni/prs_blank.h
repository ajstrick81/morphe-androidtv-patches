// prs_blank — in-place, same-length PRS ad blanker (the memcpy-seam transform).
//
// This is the C port of the *proven* bench script `frida/cmod-strip2.js`
// (PRODUCTIZATION.md §5). It is deliberately DISTINCT from prs_filter.cpp:
//
//   prs_filter (old)   : rebuilds intraTitlePlaylist shorter (buffer SHRINKS).
//                        Correct for an inflate-output rewrite; WRONG for the
//                        memcpy seam, where the copy length is fixed.
//   prs_blank  (this)  : blanks each {"type":"Remote",...}(+comma) with 0x20
//                        spaces IN PLACE. Buffer length never changes, so it is
//                        safe to run on the `src` of a memcpy/memmove hook.
//
// The single hard-won safety rule (see frida/README.md "How it works"):
//   Only ever touch a COMPLETE array. libignite copies the playlist in
//   truncated 4-16KB chunks; blanking a truncated chunk smashes a partial
//   `Main` element -> black screen. So we parse the whole array FIRST and write
//   NOTHING unless the closing ']' is present and every element parsed cleanly.
//
// Portable C++ (no memmem, no JSON lib) so the host unit test builds anywhere.

#ifndef PV_PRS_BLANK_H
#define PV_PRS_BLANK_H

#include <cstddef>

namespace pvblank {

// Hot-path size gate mirrored from cmod-strip2.js's scan_arg (`n < 512 ||
// n > 262144`). Named so PRODUCTIZATION.md §6.4 (raise the upper bound for very
// long titles) is a one-line change with a test.
constexpr std::size_t kMinScan = 512;
constexpr std::size_t kMaxScan = 262144;

struct BlankResult {
    bool marker_found  = false;  // `intraTitlePlaylist":[` present in the buffer
    bool complete      = false;  // array parsed to its closing ']' with no truncation
    bool blanked       = false;  // at least one Remote element was blanked
    int  elems         = 0;      // total elements parsed (valid only when complete)
    int  remote_blanked = 0;     // number of Remote elements blanked
};

// Cheap byte-anchor: does [buf,buf+len) contain the marker `intraTitlePlaylist":[`?
// On hit, *lb_out is set to the index of the '[' (mirrors JS `lb = off+20`).
// Returns false without touching *lb_out on miss.
bool find_marker(const char* buf, std::size_t len, std::size_t* lb_out);

// Run the blanker in place on [buf, buf+len). Never changes len. Writes 0x20
// over each Remote element (and one adjoining comma) ONLY IF the array is
// complete; a truncated/partial buffer is left byte-for-byte untouched.
//
// `apply_size_gate` mirrors the hook's scan_arg gate; pass false in tests that
// want to exercise the transform on small fixtures.
BlankResult blank_prs(char* buf, std::size_t len, bool apply_size_gate = true);

} // namespace pvblank

#endif // PV_PRS_BLANK_H
