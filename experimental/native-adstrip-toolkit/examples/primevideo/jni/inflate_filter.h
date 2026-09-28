// inflate_filter.h — post-inflate manifest strip + z_stream bookkeeping.
//
// The inflate() hook produces decompressed bytes into strm->next_out, then must
// (a) filter that freshly-produced region and (b) if anything was stripped,
// rewind the stream's OUTPUT bookkeeping so the caller sees a consistent,
// shortened result. That arithmetic is the only non-trivial part of the hook,
// so it lives here as a pure, header-only helper shared by hooks.cpp (shipped
// path) and the host unit test (verification) — the test exercises exactly the
// code that ships, not a copy of it.
//
// No Android/zlib dependency: we mirror only the head of zlib's z_stream (the
// fields we touch). Field order/types match zlib.h's z_stream_s so a real
// z_streamp reinterpret-casts cleanly.
#pragma once

#include "manifest_filter.h"
#include <cstddef>

namespace pvfilter {

// ABI-compatible prefix of zlib's z_stream (up to total_out). The real struct
// continues past this; we never read beyond total_out.
struct ZStreamHead {
    const unsigned char* next_in;
    unsigned int         avail_in;
    unsigned long        total_in;
    unsigned char*       next_out;
    unsigned int         avail_out;
    unsigned long        total_out;
};

// Call immediately AFTER inflate() returns. `out_before` is strm->next_out as
// captured BEFORE the inflate() call, so [out_before, strm->next_out) is the
// region this inflate() just produced. Filters that region in place; on a strip
// rewinds next_out/avail_out/total_out by the number of bytes removed so the
// stream stays internally consistent for the caller's next read.
//
// Pure apart from editing the output buffer + these three stream fields.
// Returns the FilterResult (is_manifest / modified / counts / new_len).
inline FilterResult apply_after_inflate(ZStreamHead* z, unsigned char* out_before) {
    FilterResult r;
    if (!z || !out_before || z->next_out <= out_before) {
        r.new_len = 0;
        return r;
    }

    size_t produced = static_cast<size_t>(z->next_out - out_before);
    r = filter(reinterpret_cast<char*>(out_before), produced);

    if (r.modified) {
        size_t removed = produced - r.new_len;   // filter only ever shrinks
        z->next_out  -= removed;
        z->avail_out += static_cast<unsigned int>(removed);
        z->total_out -= removed;
    }
    return r;
}

} // namespace pvfilter
