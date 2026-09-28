// sigscan.h — runtime byte-pattern scan over a loaded module's executable range.
//
// Purpose: re-find SSL_read / inflate on each launch from a Ghidra-derived
// signature, so a minor app update that shifts addresses doesn't hard-break us
// to a stale hardcoded offset. Falls back to a file offset if the scan misses.
#pragma once

#include <cstddef>
#include <cstdint>

namespace sigscan {

// Resolved base + executable extent of a loaded .so, from /proc/self/maps.
struct Module {
    uintptr_t base = 0;   // lowest mapped address of the module
    uintptr_t text = 0;   // start of the first r-xp segment
    size_t    text_size = 0;
    bool valid() const { return base != 0 && text != 0 && text_size != 0; }
};

// Locate a loaded module by soname substring (e.g. "libignite.so").
Module find_module(const char* soname_substr);

// Scan [text, text+text_size) for `sig` under `mask` ('x' must-match,
// '?' wildcard; mask length == sig length, mask NUL-terminated).
// Returns the matched address, or 0 if not found / multiple matches when
// `require_unique` is set.
uintptr_t scan(const Module& m,
               const unsigned char* sig,
               const char* mask,
               bool require_unique = true);

// Convenience: try signature first, then fall back to (base + fallback_offset).
// `label` is used only for logging. Returns 0 if both fail.
uintptr_t resolve(const Module& m,
                  const unsigned char* sig,
                  const char* mask,
                  unsigned long fallback_offset,
                  const char* label);

} // namespace sigscan
