// ignite_inline_patch.cpp — see ignite_inline_patch.h.
//
// ELF32 / ARM (Thumb). Finds libignite's load bias via dl_iterate_phdr, then
// overwrites the entry of the chapteringAdBreak parser (FUN_00dd00c0, ELF vaddr
// 0xDC00C0) with a 6-byte Thumb stub that forces the parsed flag to false:
//
//     movs r1, #0        ; 2100   r1 = 0
//     strb r1, [r0]      ; 7001   *out = 0   (out == r0 on entry)
//     bx   lr            ; 4770   return r0 (out pointer preserved -> ABI intact)
//
// The parser's ABI is repl(out_ptr /*r0*/, opts /*r1*/) -> out_ptr; the single
// caller (FUN_00841374) uses the returned pointer as the flag byte address, so
// preserving r0 is required. The original's internal parse object is entirely
// stack-local, so skipping it has no external side effect. Because false is the
// value a normal (non-ad) play-from-utc already carries, forcing it universally
// strips only the mid-roll insertion and leaves ordinary playback/seek untouched.

#include "ignite_inline_patch.h"

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pvinline {
namespace {

constexpr const char* kIgnite = "libignite.so";

// FUN_00dd00c0 (chapteringAdBreak parser) — ELF vaddr, 6.24.7 v7a (v16.0.0.332).
// (Ghidra IMAGE_BASE is 0x10000, so its 0x00dd00c0 == ELF vaddr 0xdc00c0.)
constexpr uintptr_t kParserVaddr = 0x00dc00c0;

// Prologue guard: the real function starts with `push {r4-r7,lr}; add r7,sp,#12`.
// If this doesn't match at runtime, the offset drifted (new app version) — skip.
constexpr uint8_t kExpectPrologue[6] = {0xf0, 0xb5, 0x03, 0xaf, 0x4d, 0xf8};

// Replacement: movs r1,#0 / strb r1,[r0] / bx lr  (little-endian Thumb).
constexpr uint8_t kStub[6] = {0x00, 0x21, 0x01, 0x70, 0x70, 0x47};

struct Ctx {
    uintptr_t bias;
    bool found;
};

int iter_cb(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* ctx = static_cast<Ctx*>(data);
    if (info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, kIgnite) == nullptr) return 0;
    ctx->bias = static_cast<uintptr_t>(info->dlpi_addr);
    ctx->found = true;
    return 1;  // stop
}

// Make [addr, addr+len) writable+executable, run fn, restore RX, flush icache.
bool patch_bytes(uintptr_t addr, const uint8_t* bytes, size_t len) {
    const uintptr_t pagesize = (uintptr_t)sysconf(_SC_PAGESIZE);
    const uintptr_t first = addr & ~(pagesize - 1);
    const uintptr_t last = (addr + len - 1) & ~(pagesize - 1);
    const size_t span = (last - first) + pagesize;  // 1 or 2 pages
    if (mprotect((void*)first, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("inline: mprotect RWX failed @%p", (void*)addr);
        return false;
    }
    memcpy((void*)addr, bytes, len);
    __builtin___clear_cache((char*)addr, (char*)(addr + len));
    // Restore R+X (best effort; the write is already complete either way).
    mprotect((void*)first, span, PROT_READ | PROT_EXEC);
    return true;
}

}  // namespace

bool apply_adbreak_patch() {
#if !defined(__arm__)
    // ARM32-Thumb ONLY. kParserVaddr and both byte patterns were measured on the
    // armeabi-v7a slice of 6.24.7; on arm64 that offset means nothing. Reading
    // bias+0xdc00c0 there could land outside any mapping and crash the app, and
    // the prologue guard cannot help because the guard itself does the read. So
    // refuse before touching memory. Finding the equivalent arm64 parser offset
    // is separate work; until then a 64-bit install keeps its mid-roll ads.
    LOGW("inline: adbreak patch is armeabi-v7a only — skipped on this ABI");
    return false;
#else
    Ctx ctx{0, false};
    dl_iterate_phdr(iter_cb, &ctx);
    if (!ctx.found) {
        LOGW("inline: libignite not mapped yet — adbreak patch deferred");
        return false;
    }

    const uintptr_t target = ctx.bias + kParserVaddr;

    // Idempotency: if our stub is already there, don't re-patch.
    if (memcmp((const void*)target, kStub, sizeof(kStub)) == 0) {
        LOGI("inline: adbreak patch already applied @%p", (void*)target);
        return true;
    }

    // Version guard — verify the parser prologue before writing.
    if (memcmp((const void*)target, kExpectPrologue, sizeof(kExpectPrologue)) != 0) {
        const uint8_t* g = (const uint8_t*)target;
        LOGE("inline: prologue mismatch @%p (got %02x %02x %02x %02x %02x %02x) — "
             "version drift, skipping adbreak patch", (void*)target,
             g[0], g[1], g[2], g[3], g[4], g[5]);
        return false;
    }

    if (!patch_bytes(target, kStub, sizeof(kStub))) return false;

    LOGI("inline: chapteringAdBreak parser neutralized @%p (bias=%p +0x%x) — mid-roll flag forced false",
         (void*)target, (void*)ctx.bias, (unsigned)kParserVaddr);
    return true;
#endif  // __arm__
}

}  // namespace pvinline
