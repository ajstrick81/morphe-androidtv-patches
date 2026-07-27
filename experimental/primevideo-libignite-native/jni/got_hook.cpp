// got_hook.cpp — see got_hook.h for the rationale.
//
// ELF32 / ARM. Locates a library by name via dl_iterate_phdr, walks its
// PT_DYNAMIC to find the symbol/string tables and the PLT relocation table
// (.rel.plt), matches a JUMP_SLOT reloc by symbol name, then rewrites that GOT
// slot to point at our proxy.

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

// R_ARM_JUMP_SLOT is the relocation type for PLT/GOT entries on ARM32.
constexpr unsigned kRArmJumpSlot = 22;

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
    if (pltrel != DT_REL) {
        // ARM32 uses REL (no addend) for PLT relocs. Guard against surprises.
        LOGE("got: %s PLT reloc type is not DT_REL (%ld)", info->dlpi_name, (long)pltrel);
        return 1;
    }

    const size_t count = pltrelsz / sizeof(ElfW(Rel));
    const auto* rel = reinterpret_cast<const ElfW(Rel)*>(jmprel);
    for (size_t i = 0; i < count; i++) {
        if (ELF32_R_TYPE(rel[i].r_info) != kRArmJumpSlot) continue;
        uint32_t symidx = ELF32_R_SYM(rel[i].r_info);
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
