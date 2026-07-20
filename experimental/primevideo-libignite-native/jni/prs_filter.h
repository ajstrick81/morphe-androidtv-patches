// prs_filter.h — in-process strip of Prime Video's PRS playback-resources JSON.
//
// This is the in-process port of the PROVEN off-device MITM transform (the
// experimental/primevideo-atv-mitm-rig strip addon), which removed ads on movies
// AND TV shows on-device by editing the response of
//   POST /playback/prs/GetVodPlaybackResources
// The response is gzip'd JSON; the plaintext body appears at the `inflate` hook
// (SSL_read still has it gzip'd + HTTP/2-framed), so this runs from the same
// pvfilter::filter() entry the manifest strip uses — on the inflate output.
//
// Transform (identical to the rig): in the JSON body,
//   * drop every intraTitlePlaylist item whose "type" == "Remote" (= an ad
//     manifest the client would stitch in), and
//   * empty "nonLinearAds".
// No Remote items -> the client schedules no ad breaks -> ads gone, content
// stream untouched. Unlike the proxy, we edit the already-decompressed bytes, so
// there is NO re-gzip: we only ever shrink the buffer and hand the shorter,
// still-valid JSON to the native parser.
//
// Pure, allocation-inside-only (builds a filtered copy then memcpy's back), no
// Android/zlib dependency — host-unit-testable exactly like manifest_filter.
#pragma once

#include "manifest_filter.h"   // FilterResult
#include <cstddef>

namespace pvfilter {

// Cheap check: does this buffer look like the PRS JSON we strip? (starts with
// '{' after whitespace and carries the intraTitlePlaylist key). Lets filter()
// skip the work on the overwhelming majority of non-PRS reads.
bool looks_like_prs(const char* buf, size_t len);

// Strip ad entries from a PRS JSON body IN PLACE (only ever shrinks). If the
// buffer isn't PRS JSON, returns is_prs=false and leaves it untouched
// (new_len == len). `buf` must be writable.
FilterResult filter_prs(char* buf, size_t len);

} // namespace pvfilter
