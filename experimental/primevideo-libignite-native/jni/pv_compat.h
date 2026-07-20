// pv_compat.h — host-build compatibility shim.
//
// The shipping code targets the Android NDK (bionic), which provides memmem().
// glibc (the cloud Linux host) does too. mingw/MSVC do NOT, so a Windows host
// build of the unit tests needs a fallback. This header is NOT part of the .so
// build (CMake doesn't include it); it's applied only to host test compiles via
//   g++ -include pv_compat.h ...
// so the shipping translation units are byte-for-byte unchanged on-device.
#pragma once

#if !defined(__GLIBC__) && !defined(__BIONIC__)
#include <cstddef>
#include <cstring>
static inline void* memmem(const void* hay, size_t hlen,
                           const void* needle, size_t nlen) {
    if (nlen == 0) return const_cast<void*>(hay);
    if (hlen < nlen) return nullptr;
    const unsigned char* h = static_cast<const unsigned char*>(hay);
    const unsigned char* n = static_cast<const unsigned char*>(needle);
    for (size_t i = 0; i + nlen <= hlen; ++i)
        if (h[i] == n[0] && memcmp(h + i, n, nlen) == 0)
            return const_cast<unsigned char*>(h + i);
    return nullptr;
}
#endif
