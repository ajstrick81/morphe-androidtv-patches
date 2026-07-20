#include "sigscan.h"

#include <android/log.h>
#include <cstdio>
#include <cstring>

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

namespace sigscan {

Module find_module(const char* soname_substr) {
    Module m;
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) {
        LOGW("find_module: cannot open /proc/self/maps");
        return m;
    }
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, soname_substr)) continue;

        uintptr_t start = 0, end = 0;
        char perms[8] = {0};
        // format: start-end perms offset dev inode pathname
        if (sscanf(line, "%zx-%zx %7s", &start, &end, perms) != 3) continue;

        if (m.base == 0 || start < m.base) m.base = start;

        // First executable segment is our scan window.
        if (m.text == 0 && perms[2] == 'x') {
            m.text = start;
            m.text_size = end - start;
        }
    }
    fclose(f);

    if (m.valid()) {
        LOGI("find_module: %s base=%p text=%p size=%zu",
             soname_substr, (void*)m.base, (void*)m.text, m.text_size);
    } else {
        LOGW("find_module: %s not fully resolved (base=%p text=%p)",
             soname_substr, (void*)m.base, (void*)m.text);
    }
    return m;
}

static bool match_at(const unsigned char* p,
                     const unsigned char* sig,
                     const char* mask) {
    for (size_t i = 0; mask[i]; ++i) {
        if (mask[i] == 'x' && p[i] != sig[i]) return false;
    }
    return true;
}

uintptr_t scan(const Module& m,
               const unsigned char* sig,
               const char* mask,
               bool require_unique) {
    if (!m.valid()) return 0;
    const size_t mlen = strlen(mask);
    if (mlen == 0 || mlen > m.text_size) return 0;

    const unsigned char* base = reinterpret_cast<const unsigned char*>(m.text);
    const size_t last = m.text_size - mlen;

    uintptr_t hit = 0;
    int count = 0;
    for (size_t i = 0; i <= last; ++i) {
        if (match_at(base + i, sig, mask)) {
            if (hit == 0) hit = m.text + i;
            if (++count > 1 && require_unique) {
                LOGW("scan: signature is NOT unique (>=2 matches) — lengthen it");
                return 0;
            }
        }
    }
    return hit;
}

uintptr_t resolve(const Module& m,
                  const unsigned char* sig,
                  const char* mask,
                  unsigned long fallback_offset,
                  const char* label) {
    uintptr_t via_sig = scan(m, sig, mask, /*require_unique=*/true);
    if (via_sig) {
        // Cross-check against the fallback offset when one is provided.
        if (fallback_offset) {
            uintptr_t via_off = m.base + fallback_offset;
            if (via_off != via_sig) {
                LOGW("resolve[%s]: signature(%p) != fallback(%p) — trusting signature",
                     label, (void*)via_sig, (void*)via_off);
            }
        }
        LOGI("resolve[%s]: %p (via signature)", label, (void*)via_sig);
        return via_sig;
    }
    if (fallback_offset) {
        uintptr_t via_off = m.base + fallback_offset;
        LOGW("resolve[%s]: signature miss — using fallback offset %p", label, (void*)via_off);
        return via_off;
    }
    LOGW("resolve[%s]: unresolved (no signature match, no fallback)", label);
    return 0;
}

} // namespace sigscan
