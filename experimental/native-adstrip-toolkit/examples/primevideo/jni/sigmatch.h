// sigmatch.h — pure byte-signature matching, shared by sigscan.cpp and the test.
//
// The masked pattern match ('x' = must-match, '?' = wildcard) and the
// unique-match enforcement are the actual signature-scan logic; the rest of
// sigscan.cpp is just /proc/self/maps parsing and logging. Extracted here,
// Android-free, so the host test drives exactly the code that ships.
#pragma once

#include <cstddef>
#include <cstring>

namespace sigscan {

// Does the byte window at `p` satisfy `sig` under `mask`? Positions where
// mask[i]=='x' must equal sig[i]; mask[i]=='?' (or any non-'x') is a wildcard.
// mask is NUL-terminated and defines the comparison length.
inline bool match_at(const unsigned char* p,
                     const unsigned char* sig,
                     const char* mask) {
    for (size_t i = 0; mask[i]; ++i) {
        if (mask[i] == 'x' && p[i] != sig[i]) return false;
    }
    return true;
}

// Scan [base, base+size) for the first window satisfying sig/mask.
// Returns the byte offset of the first match, or -1 if none — or, when
// require_unique is true and 2+ windows match, -1 to signal an ambiguous
// (too-short) signature. The total match count is reported via *out_count
// (may be capped early at 2 when require_unique short-circuits).
inline long scan_range(const unsigned char* base, size_t size,
                       const unsigned char* sig, const char* mask,
                       bool require_unique, int* out_count) {
    const size_t mlen = strlen(mask);
    if (out_count) *out_count = 0;
    if (mlen == 0 || mlen > size) return -1;

    long first = -1;
    int count = 0;
    const size_t last = size - mlen;
    for (size_t i = 0; i <= last; ++i) {
        if (match_at(base + i, sig, mask)) {
            if (first < 0) first = static_cast<long>(i);
            if (++count > 1 && require_unique) {
                if (out_count) *out_count = count;   // == 2
                return -1;                            // ambiguous
            }
        }
    }
    if (out_count) *out_count = count;
    return count ? first : -1;
}

} // namespace sigscan
