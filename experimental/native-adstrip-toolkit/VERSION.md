# Toolkit provenance

- **Name:** Native in-process ad-strip toolkit (Android TV / Morphe)
- **Packaged:** 2026-07-22
- **Extracted from:** the Prime Video native-interception work
  (`experimental/primevideo-libignite-native/`, branch
  `claude/prime-video-continuation-kaplat`).
- **Reference build target:** Prime Video ATV `6.23.23+v15.5.0.70-armv7a`,
  `libignite.so`.

## What's proven vs. pending (in the example)

- **Proven:** the ad-strip transforms — host-tested (103 + 34 + 75 + 28
  assertions), mutation-verified, ASan/UBSan-clean; and the off-device MITM
  result that the `type:"Remote"` PRS strip cleans movies AND TV.
- **Pending (device-side):** recovering the whole-body PRS pull seam in
  libignite (Ghidra), building the `.so` (ShadowHook), and shipping the Morphe
  patch. The scaffold's `hooks.cpp`/`offsets.h` are reviewed-but-unverified
  skeletons until wired against the real library on-device.

## Reproduce the green run

See `examples/primevideo/TOOLKIT-EXAMPLE.md` (build lines) and `docs/TESTING.md`
(sanitizer + mutation discipline).
