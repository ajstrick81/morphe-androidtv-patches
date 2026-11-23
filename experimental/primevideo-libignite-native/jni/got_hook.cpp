// got_hook.cpp — see got_hook.h for the rationale.
//
// Locates a library by name via dl_iterate_phdr, walks its PT_DYNAMIC to find
// the symbol/string tables and the PLT relocation table, matches a JUMP_SLOT
// reloc by symbol name, then rewrites that GOT slot to point at our proxy.
//
// Supports both ABIs Prime Video ships, which differ in how PLT relocs are
// encoded — everything else about the walk is identical:
//
//   armeabi-v7a  ELF32  .rel.plt   Elf32_Rel   DT_REL   R_ARM_JUMP_SLOT     22
//   arm64-v8a    ELF64  .rela.plt  Elf64_Rela  DT_RELA  R_AARCH64_JUMP_SLOT 1026
//
// ElfW() already picks the right width per build, so the only real dispatch is
// the reloc record type (Rel vs Rela), the DT_PLTREL value to expect, and the
// JUMP_SLOT type number. Note DT_RELR / Android packed relocations do not
// affect this: packing applies to .rel(a).dyn, never to the PLT table reached
// through DT_JMPREL.

#include "got_hook.h"

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "PVNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pvgot {
namespace {

// Per-ABI PLT reloc encoding. Keep this the ONLY arch-conditional block here.
#if defined(__aarch64__)
using PltReloc = ElfW(Rela);                   // arm64: RELA, with addend
constexpr unsigned kJumpSlot = 1026;           // R_AARCH64_JUMP_SLOT
constexpr ElfW(Sword) kExpectedPltRel = DT_RELA;
constexpr const char* kAbiName = "arm64-v8a";
#elif defined(__arm__)
using PltReloc = ElfW(Rel);                    // arm32: REL, no addend
constexpr unsigned kJumpSlot = 22;             // R_ARM_JUMP_SLOT
constexpr ElfW(Sword) kExpectedPltRel = DT_REL;
constexpr const char* kAbiName = "armeabi-v7a";
#else
#error "got_hook: unsupported ABI — add its PLT reloc encoding above"
#endif

// ELF32_R_* / ELF64_R_* are width-specific; pick the pair matching ElfW().
#if defined(__LP64__)
#define PV_R_TYPE(x) ELF64_R_TYPE(x)
#define PV_R_SYM(x)  ELF64_R_SYM(x)
#else
#define PV_R_TYPE(x) ELF32_R_TYPE(x)
#define PV_R_SYM(x)  ELF32_R_SYM(x)
#endif

struct FindCtx {
    const char* lib_substr;
    const char* sym;
    void** got_slot;  // out: runtime address of the GOT word to overwrite
};

// Dynamic-table pointers on Android may be stored either as absolute
// (already load-biased by the linker) or as link-time vaddrs. Small values are
// vaddrs (a real mapping base is a high mmap address); bias those. This is the
// same heuristic xhook/bytehook use and it is robust in practice.
inline uintptr_t fix_ptr(ElfW(Addr) load_bias, ElfW(Addr) p) {
    if (p == 0) return 0;
    return (p >= load_bias) ? (uintptr_t)p : (uintptr_t)(load_bias + p);
}

int iter_cb(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* ctx = static_cast<FindCtx*>(data);
    if (info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, ctx->lib_substr) == nullptr) return 0;

    const ElfW(Addr) bias = info->dlpi_addr;

    // Find PT_DYNAMIC.
    const ElfW(Dyn)* dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const ElfW(Dyn)*>(bias + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) {
        LOGE("got: %s has no PT_DYNAMIC", info->dlpi_name);
        return 1;  // stop; this was our lib but it's malformed
    }

    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
    const uint8_t* jmprel = nullptr;
    size_t pltrelsz = 0;
    ElfW(Sword) pltrel = 0;

    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB:  symtab = reinterpret_cast<const ElfW(Sym)*>(fix_ptr(bias, d->d_un.d_ptr)); break;
            case DT_STRTAB:  strtab = reinterpret_cast<const char*>(fix_ptr(bias, d->d_un.d_ptr)); break;
            case DT_JMPREL:  jmprel = reinterpret_cast<const uint8_t*>(fix_ptr(bias, d->d_un.d_ptr)); break;
            case DT_PLTRELSZ: pltrelsz = (size_t)d->d_un.d_val; break;
            case DT_PLTREL:  pltrel = (ElfW(Sword))d->d_un.d_val; break;
            default: break;
        }
    }

    if (symtab == nullptr || strtab == nullptr || jmprel == nullptr || pltrelsz == 0) {
        LOGE("got: %s missing dynamic entries (symtab=%p strtab=%p jmprel=%p sz=%zu)",
             info->dlpi_name, (void*)symtab, (void*)strtab, (void*)jmprel, pltrelsz);
        return 1;
    }
    if (pltrel != kExpectedPltRel) {
        // Each ABI pins one encoding (see kExpectedPltRel). A mismatch means the
        // table is not what PltReloc describes, so bail rather than misparse it.
        LOGE("got: %s PLT reloc type %ld, expected %ld for %s",
             info->dlpi_name, (long)pltrel, (long)kExpectedPltRel, kAbiName);
        return 1;
    }

    const size_t count = pltrelsz / sizeof(PltReloc);
    const auto* rel = reinterpret_cast<const PltReloc*>(jmprel);
    for (size_t i = 0; i < count; i++) {
        if (PV_R_TYPE(rel[i].r_info) != kJumpSlot) continue;
        const uintptr_t symidx = (uintptr_t)PV_R_SYM(rel[i].r_info);
        const char* name = strtab + symtab[symidx].st_name;
        if (strcmp(name, ctx->sym) != 0) continue;
        ctx->got_slot = reinterpret_cast<void**>(bias + rel[i].r_offset);
        return 1;  // found; stop iteration
    }

    LOGW("got: %s has no JUMP_SLOT for '%s'", info->dlpi_name, ctx->sym);
    return 1;  // it's our lib but the symbol isn't imported; stop
}

}  // namespace

bool hook_import(const char* lib_substr, const char* sym, void* proxy, void** out_prev) {
    FindCtx ctx{lib_substr, sym, nullptr};
    dl_iterate_phdr(iter_cb, &ctx);
    if (ctx.got_slot == nullptr) {
        LOGW("got: could not locate GOT slot for %s!%s", lib_substr, sym);
        return false;
    }

    void** slot = ctx.got_slot;

    // Make the GOT page writable (it is read-only under RELRO after startup).
    const uintptr_t pagesize = (uintptr_t)sysconf(_SC_PAGESIZE);
    const uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(pagesize - 1);
    if (mprotect(reinterpret_cast<void*>(page), pagesize, PROT_READ | PROT_WRITE) != 0) {
        LOGE("got: mprotect RW failed for %s!%s slot=%p", lib_substr, sym, (void*)slot);
        return false;
    }

    void* prev = *slot;
    *slot = proxy;
    __sync_synchronize();

    // Leave the page writable: re-applying PROT_READ is unnecessary and would
    // fight the linker's RELRO bookkeeping. A single word swap is complete.
    if (out_prev) *out_prev = prev;
    LOGI("got: hooked %s!%s slot=%p prev=%p -> proxy=%p", lib_substr, sym, (void*)slot, prev, proxy);
    return true;
}

}  // namespace pvgot
