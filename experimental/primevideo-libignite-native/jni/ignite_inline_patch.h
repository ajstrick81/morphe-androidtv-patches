// ignite_inline_patch.h — PV v16 (engine v16.0.0.x) native mid-roll kill.
//
// libignite moved the ad pipeline native (Rust->WASM "Megablast"). The mid-roll
// ad break is gated by a single boolean `chapteringAdBreak` option parsed for
// each "PlayFromUtcTimeWithOptions". See experimental/primevideo-libignite-native/
// v16-recon/RECON_NOTES.md for the full decompiled chain:
//   FUN_00841374 -> FUN_00dd00c0 (parser) -> FUN_00dcefd6 -> FUN_00dd6d88
//                 -> FUN_00ded1c8 (actuator: flag TRUE keeps break params +0x40/+0x44,
//                    FALSE zeroes them = NO break).
//
// This applies a self-contained 6-byte inline patch to the parser FUN_00dd00c0
// (ELF vaddr 0xDC00C0 on 6.24.7 v7a) forcing chapteringAdBreak := false. No hook
// library (ShadowHook/ByteHook fail on this Onn ROM — see CMakeLists.txt).

#pragma once

namespace pvinline {

// Locate libignite and apply the chapteringAdBreak-false inline patch. Verifies
// the target prologue first; on any mismatch (version drift) it skips and logs,
// so a wrong version degrades gracefully instead of corrupting .text. Returns
// true iff the patch was written. Safe to call once libignite is mapped.
bool apply_adbreak_patch();

}  // namespace pvinline
