// got_hook.h — minimal, self-contained PLT/GOT import hook (ARM32 / ELF32).
//
// Why this exists instead of ShadowHook or ByteHook:
//   * The ad-data copy inside libignite goes through libc's `memcpy`, which is
//     an STT_GNU_IFUNC — the exported symbol is a resolver, and libignite's
//     GOT caches the CPU-tuned implementation (__memcpy_a55 on this Cortex-A55).
//     Inline-hooking a libc *body* means guessing which of ~7 implementations
//     the resolver picked, and it means rewriting code every thread executes.
//   * ByteHook would be the textbook tool, but on this Onn 4K ROM its mandatory
//     dl-init/fini monitor is wired (arm, API >= 21, Android 14) to
//     ShadowHook's `register_dl_init_callback`, which depends on the linker
//     constructor hook (`sh_linker_init`) that FAILS on this ROM (errno 12 —
//     see shadowhook/VENDORED.md). So ByteHook aborts every hook with
//     INITERR_DLMTR here.
//
// This hooks libignite's *import slot* for a symbol: when libignite calls
// memcpy(), the PLT reads our proxy address from the GOT and jumps there,
// regardless of which libc IFUNC body was chosen. It is:
//   * IFUNC-proof   (we intercept the caller's slot, never a libc body)
//   * version-proof (slot located by *symbol name*, never a hardcoded offset)
//   * device-proof  (no linker-constructor hook, no dl monitor, no third-party)
//   * low-risk      (a single aligned GOT word write; no executable code patched)

#pragma once
#include <cstddef>

namespace pvgot {

// Locate `lib_substr`'s (e.g. "libignite.so") JUMP_SLOT reloc for `sym`
// (e.g. "memcpy"), make the GOT page writable, overwrite the slot with `proxy`,
// and return the previous function pointer through *out_prev.
//
// Returns true on success. On failure *out_prev is left unchanged and a reason
// is logged. Safe to call for symbols the library does not import (returns
// false with a "slot not found" log — used to probe optional controls).
bool hook_import(const char* lib_substr, const char* sym, void* proxy, void** out_prev);

}  // namespace pvgot
