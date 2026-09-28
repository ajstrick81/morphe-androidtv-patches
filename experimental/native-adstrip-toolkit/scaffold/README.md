# scaffold/ — reusable building blocks

What each file is, and how much of it is generic vs. needs your app's specifics.
Fill placeholders per [`../docs/PORTING-CHECKLIST.md`](../docs/PORTING-CHECKLIST.md).

## jni/

| File | Reuse | Notes |
|---|---|---|
| `sigscan.h` / `sigscan.cpp` | **as-is** | Runtime signature scan over a loaded module's `.text` (`/proc/self/maps` + masked matcher). App-agnostic. |
| `sigmatch.h` | **as-is** | Pure masked-signature matcher; shared by sigscan and its host test. |
| `test_harness.h` | **as-is** | The `CHECK` / `TEST_MAIN_END` host-test harness. |
| `offsets.h.template` | fill | The one file you edit after Ghidra. Replace every `<…>`. |
| `CMakeLists.txt` | rename | NDK build; rename `<HOOK>`, add your transform sources, point at ShadowHook. |
| `hooks.cpp` | **not here** | Too seam-specific to template usefully — copy `examples/primevideo/jni/hooks.cpp` and rewrite the two hook bodies for your seam + transform. |

**The transform itself** (filter / blanker / reassembler) is app-specific: copy
the closest reference from `examples/primevideo/jni/` and change the marker + ad
predicate. See the porting checklist for which one to start from.

## patch/ and extension/  (Morphe patch templates)

| File | Reuse |
|---|---|
| `BundleNativeHookPatch.kt.template` | Writes `lib<HOOK>.so` into `lib/<abi>/`; forces `extractNativeLibs="true"`. |
| `LoadNativeHookPatch.kt.template` | Injects `NativeHookLoader.load()` at `Application.onCreate`. |
| `Fingerprints.kt.template` | The `onCreate` fingerprint — **set the real Application class**. |
| `extension/NativeHookLoader.java.template` | The fail-loud `System.loadLibrary` loader. |

These encode the Morphe conventions (resource patch writes a binary; bytecode
patch injects an `invoke-static`; the loader reuses the existing extension
module — no new extension is built).

## tools/  (offset recovery + Frida bench)

| File | Purpose |
|---|---|
| `resolve_xrefs.py`, `find_func_entry.py`, `build_sigs.py` | Pure-Python string-XREF → prologue → unique signature pipeline (Ghidra-free fallback). |
| `find_offsets.py` | Ghidra Jython equivalent for when headless Ghidra works. |
| `README.md` | How the reference offsets were recovered; how to re-derive after an update. |
| `frida/find-copy-seam.js` | CModule `memcpy`/`memmove` hook: find which function copies your marker. |
| `frida/heap-oracle.js` | Prove ads are actually served this session (frequency-cap guard). |
| `frida/driver.py` | Resilient Frida-gadget script loader. |
