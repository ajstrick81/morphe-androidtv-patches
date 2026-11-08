// timeline_action_patch.cpp — see timeline_action_patch.h.
//
// Runtime discovery of Native_StartTimelineAction* host-function implementations
// inside libignite.so, followed by inline patching to suppress ad-break creation.
//
// Strategy:
//   1. Find libignite's load bias via dl_iterate_phdr (same as ignite_inline_patch).
//   2. Walk PT_LOAD segments to locate the writable data sections (.data.rel.ro,
//      .data) where the WAMR NativeSymbol registration table lives.
//   3. Scan those sections for pointers into rodata that reference the
//      "Native_StartTimelineAction*" name strings.
//   4. The NativeSymbol table entry layout is { const char* name; void* func; ... }.
//      The word immediately after the name pointer is the impl function pointer.
//   5. Validate the impl pointer (must point into libignite's .text), read its
//      prologue, then overwrite with a Thumb return stub.
//
// The return stub for WAMR host functions:
//   WAMR NativeSymbol functions have the signature:
//     void fn(wasm_exec_env_t exec_env, ...);  // returns void
//   So a simple `bx lr` (0x4770) suffices — no return value to fake.
//   For functions that return a value, we use `movs r0, #0; bx lr` (4 bytes).

#include "timeline_action_patch.h"

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pvtimeline {
namespace {

constexpr const char* kIgnite = "libignite.so";

// The three StartTimelineAction variants we want to suppress.
// StopTimelineAction and Get/Supports are left alone — stopping a non-existent
// action is harmless, and Get/Supports are queries that don't create breaks.
struct TargetFn {
    const char* name;
    uintptr_t impl;       // resolved at runtime
    bool patched;
};

TargetFn g_targets[] = {
    {"Native_StartTimelineAction",       0, false},
    {"Native_StartTimelineActionStr",    0, false},
    {"Native_StartTimelineActionStrClr", 0, false},
};
constexpr int kNumTargets = sizeof(g_targets) / sizeof(g_targets[0]);

// Thumb return stubs. WAMR host functions are void-returning dispatch handlers;
// the actual return value (if any) is written via the WAMR stack, not the ARM
// return register. So a bare `bx lr` is the safest no-op.
//
// However, if a function uses a standard push {r4-r7,lr} prologue with a
// non-trivial frame, a bare bx-lr skips the pop and corrupts the stack.
// Instead we use a 2-instruction stub:
//   movs r0, #0   (0x2000) — zero return (harmless if void)
//   bx lr          (0x4770) — return
constexpr uint8_t kStubBxLr[4] = {0x00, 0x20, 0x70, 0x47};

// Scan context passed to dl_iterate_phdr callback.
struct ScanCtx {
    uintptr_t bias;
    // libignite's memory range for pointer validation.
    uintptr_t text_lo, text_hi;     // .text range (code pointers must land here)
    uintptr_t rodata_lo, rodata_hi; // .rodata range (name strings live here)
    uintptr_t data_lo, data_hi;     // writable data range (.data.rel.ro + .data)
    bool found;
};

int find_ignite_cb(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* ctx = static_cast<ScanCtx*>(data);
    if (info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, kIgnite) == nullptr) return 0;

    ctx->bias = static_cast<uintptr_t>(info->dlpi_addr);
    ctx->found = true;

    // Walk PT_LOAD segments to find .text (RX), .rodata (R), .data (RW).
    // On ARM32 ELF, typical layout:
    //   LOAD 0: .rodata + .text  (RE/RX)
    //   LOAD 1: .data.rel.ro + .data (RW)
    // We identify by flags: PF_X = executable, PF_W = writable.
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const auto& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD) continue;
        uintptr_t lo = ctx->bias + ph.p_vaddr;
        uintptr_t hi = lo + ph.p_memsz;
        if (ph.p_flags & PF_X) {
            // Executable segment contains .text (and possibly .rodata before it).
            // For our purposes, code pointers and rodata both live here.
            if (ctx->text_lo == 0 || lo < ctx->text_lo) ctx->text_lo = lo;
            if (hi > ctx->text_hi) ctx->text_hi = hi;
            if (ctx->rodata_lo == 0 || lo < ctx->rodata_lo) ctx->rodata_lo = lo;
            if (hi > ctx->rodata_hi) ctx->rodata_hi = hi;
        }
        if (ph.p_flags & PF_W) {
            // Writable segment = .data.rel.ro + .data + .bss
            if (ctx->data_lo == 0 || lo < ctx->data_lo) ctx->data_lo = lo;
            if (hi > ctx->data_hi) ctx->data_hi = hi;
        }
    }
    return 1;  // stop
}

// Make [addr, addr+len) writable+executable, write bytes, restore, flush.
bool patch_bytes(uintptr_t addr, const uint8_t* bytes, size_t len) {
    const uintptr_t pagesize = (uintptr_t)sysconf(_SC_PAGESIZE);
    const uintptr_t first = addr & ~(pagesize - 1);
    const uintptr_t last  = (addr + len - 1) & ~(pagesize - 1);
    const size_t span = (last - first) + pagesize;
    if (mprotect((void*)first, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("timeline: mprotect RWX failed @%p (errno=%d)", (void*)addr, errno);
        return false;
    }
    memcpy((void*)addr, bytes, len);
    __builtin___clear_cache((char*)addr, (char*)(addr + len));
    mprotect((void*)first, span, PROT_READ | PROT_EXEC);
    return true;
}

// Scan the writable data section for NativeSymbol table entries.
// A NativeSymbol entry looks like: { const char* name; void(*func)(...); ... }
// We look for aligned word pairs where:
//   word[0] = pointer into rodata that matches our target string
//   word[1] = pointer into .text (the impl function)
void scan_nativesymbol_table(const ScanCtx& ctx) {
    if (ctx.data_lo == 0 || ctx.data_hi == 0) {
        LOGW("timeline: no writable segment found in libignite");
        return;
    }

    const uintptr_t* start = reinterpret_cast<const uintptr_t*>(ctx.data_lo);
    const uintptr_t* end   = reinterpret_cast<const uintptr_t*>(ctx.data_hi - sizeof(uintptr_t) * 2);

    int found = 0;
    for (const uintptr_t* p = start; p < end; ++p) {
        uintptr_t name_ptr = p[0];
        uintptr_t func_ptr = p[1];

        // name_ptr must be in rodata range
        if (name_ptr < ctx.rodata_lo || name_ptr >= ctx.rodata_hi) continue;
        // func_ptr must be in text range (Thumb: bit0 set for call, but stored
        // address may or may not have it — accept both)
        uintptr_t func_clean = func_ptr & ~1u;
        if (func_clean < ctx.text_lo || func_clean >= ctx.text_hi) continue;

        // Safe to read the string (it's in our mapped rodata).
        const char* name = reinterpret_cast<const char*>(name_ptr);

        for (int t = 0; t < kNumTargets; ++t) {
            if (g_targets[t].impl != 0) continue;  // already found
            if (strcmp(name, g_targets[t].name) != 0) continue;

            g_targets[t].impl = func_ptr;
            ++found;
            LOGI("timeline: resolved %s -> impl @%p (data entry @%p)",
                 g_targets[t].name, (void*)func_ptr, (void*)p);
            break;
        }
        if (found >= kNumTargets) break;
    }

    for (int t = 0; t < kNumTargets; ++t) {
        if (g_targets[t].impl == 0)
            LOGW("timeline: could NOT resolve %s (not found in NativeSymbol table)",
                 g_targets[t].name);
    }

    // DIAG: if nothing resolved, scan rodata for the string addresses directly
    // to verify our pointer expectations.
    if (found == 0) {
        for (int t = 0; t < kNumTargets; ++t) {
            const char* needle = g_targets[t].name;
            size_t nlen = strlen(needle);
            for (uintptr_t addr = ctx.rodata_lo; addr + nlen < ctx.rodata_hi; ++addr) {
                if (memcmp((const void*)addr, needle, nlen) == 0 &&
                    ((const char*)addr)[nlen] == '\0') {
                    LOGI("timeline DIAG: string '%s' found at %p (bias+0x%x)",
                         needle, (void*)addr, (unsigned)(addr - ctx.bias));
                    // Now scan data section for any pointer TO this string
                    int refs = 0;
                    for (const uintptr_t* dp = start; dp < end && refs < 3; ++dp) {
                        if (*dp == addr) {
                            uintptr_t next = dp[1];
                            LOGI("timeline DIAG: data ref @%p -> [0]=%p(str) [1]=%p [2]=%p [3]=%p",
                                 (void*)dp, (void*)dp[0], (void*)dp[1],
                                 (void*)dp[2], (void*)dp[3]);
                            ++refs;
                        }
                    }
                    if (refs == 0)
                        LOGW("timeline DIAG: NO data-section pointer to '%s' @%p", needle, (void*)addr);
                    break;
                }
            }
        }
    }
}

}  // namespace

int apply_timeline_action_patch() {
    // Idempotency: count already-patched
    int already = 0;
    for (int t = 0; t < kNumTargets; ++t)
        if (g_targets[t].patched) ++already;
    if (already >= kNumTargets) return already;

    // Step 1: find libignite
    ScanCtx ctx{};
    dl_iterate_phdr(find_ignite_cb, &ctx);
    if (!ctx.found) {
        LOGW("timeline: libignite not mapped yet — deferred");
        return 0;
    }

    LOGI("timeline: libignite bias=%p text=[%p,%p) rodata=[%p,%p) data=[%p,%p)",
         (void*)ctx.bias,
         (void*)ctx.text_lo, (void*)ctx.text_hi,
         (void*)ctx.rodata_lo, (void*)ctx.rodata_hi,
         (void*)ctx.data_lo, (void*)ctx.data_hi);

    // Step 2: resolve impl addresses from the NativeSymbol table
    scan_nativesymbol_table(ctx);

    // Step 3: patch each resolved impl
    int patched = 0;
    for (int t = 0; t < kNumTargets; ++t) {
        if (g_targets[t].patched) { ++patched; continue; }
        if (g_targets[t].impl == 0) continue;

        // The impl address may have Thumb bit set; the actual code is at the
        // even address. We patch there.
        uintptr_t code_addr = g_targets[t].impl & ~1u;

        // Read the first 4 bytes for logging (prologue fingerprint).
        const uint8_t* prologue = reinterpret_cast<const uint8_t*>(code_addr);
        LOGI("timeline: %s @%p prologue: %02x %02x %02x %02x",
             g_targets[t].name, (void*)code_addr,
             prologue[0], prologue[1], prologue[2], prologue[3]);

        // Check if already patched (idempotent)
        if (memcmp((const void*)code_addr, kStubBxLr, sizeof(kStubBxLr)) == 0) {
            LOGI("timeline: %s already patched", g_targets[t].name);
            g_targets[t].patched = true;
            ++patched;
            continue;
        }

        if (!patch_bytes(code_addr, kStubBxLr, sizeof(kStubBxLr))) {
            LOGE("timeline: FAILED to patch %s", g_targets[t].name);
            continue;
        }

        g_targets[t].patched = true;
        ++patched;
        LOGI("timeline: %s NEUTRALIZED @%p — timeline action creation suppressed",
             g_targets[t].name, (void*)code_addr);
    }

    LOGI("timeline: patch complete — %d/%d StartTimelineAction variants neutralized",
         patched, kNumTargets);
    return patched;
}

}  // namespace pvtimeline
