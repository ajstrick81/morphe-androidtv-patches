# Productization plan — PV ATV in-process ad-strip (PC bench → Morphe patch)

Status (2026-07-21): the strip is **verified working on a PC bench** (`frida/cmod-strip2.js` over
adb + the installed Frida-gadget build). Ads removed, playback healthy, 0 errors across many
movies. It is **not** persistent — the strip logic lives on the PC and must be attached each
session. This doc is the path from that bench result to an installable, no-PC Morphe patch.

See `frida/README.md` for the mechanism and findings. This doc is the transition plan only.

---

## 1. The core shift — where the "work" moves

| Piece | Now (bench) | Must become (patch) |
|---|---|---|
| Hook engine | Frida Gum (`Interceptor.attach`) | inline-hook lib compiled into a `.so` |
| Filter + strip logic | CModule C (`find_marker`) + JS (`parseElems`/`blankRanges`) | all in C, inside the `.so` |
| Loader | `ssl_driver2.py` attaches over adb each session | the APK loads the `.so` itself at startup |

Key leverage: `cmod-strip2.js`'s CModule C (`find_marker`, `scan_arg`, size gate) ports **almost
verbatim**. Only the blanking (`parseElems` + `blankRanges` + same-length space-fill) is still
JavaScript and must be rewritten in C. That is the main net-new code.

## 2. What a Morphe patch must do (three jobs)

1. **Bundle** the compiled `.so` into `lib/armeabi-v7a/` of the APK.
2. **Load** it early (inject `System.loadLibrary` / a `JNI_OnLoad` trigger) so the hook is armed
   before the first PRS fetch.
3. **Hook** `memcpy`/`memmove` from inside the `.so` (`JNI_OnLoad`), running the complete-array-
   only blank.

## 3. Reuse vs. replace in the existing scaffold

**Reuse (plumbing is sound):**
- `extension/NativeHookLoader.java` — the load trigger.
- `patch/BundleNativeHookPatch.kt`, `patch/LoadNativeHookPatch.kt`, `patch/Fingerprints.kt` — the
  Morphe patch that bundles the `.so` + injects the loadLibrary call.
- `jni/CMakeLists.txt`, `jni/sigscan.cpp`/`sigmatch.h` (if we later hook by signature).

**Replace (built around the WRONG seam):**
- `jni/offsets.h` — `inflate@0xd32f7a` / `SSL_read@0xc4fe3c` are DEAD ends. Not our hook target.
- `jni/hooks.cpp`, `jni/inflate_filter.h`, `jni/manifest_filter.*`, `ssl_reassembly.h` — written to
  hook inflate/SSL_read. The new target is **libc `memcpy`/`memmove`** with the complete-array rule.
- `jni/prs_filter.{cpp,h}` — has an in-process PRS strip already (host-tested), but its trigger
  assumed the inflate path. Its transform (drop `type:"Remote"`) is the right idea; re-wire it to
  the memcpy hook + the complete-array/same-length-blank discipline we proved.

## 4. Two tracks

### Track A — Frida-gadget auto-load (fast; stepping stone, do first)
The installed gadget can auto-load a script from a baked-in config at startup — no PC.
- **Do:** bundle `cmod-strip2.js` + a gadget config (`libfrida-gadget.config.so` →
  `type: script`, path to the JS) into the APK; confirm the gadget loads it on cold start and
  re-arms across PV's frequent process respawns.
- **Win:** a real no-PC, multi-day cold-start stability soak with ~zero new code — exactly the
  test to run next. De-risks Track B (if a permanent gadget-loaded strip is stable for days, the
  `.so` will be too).
- **Cost/why not ship it:** ships the Frida gadget (bulky, easily detected, not a clean Morphe
  patch). Stepping stone, not the release.

### Track B — ShadowHook `.so` + Morphe patch (endgame; the release)
- **Hook lib:** `bytedance/android-inline-hook` (ShadowHook). **Dobby is a confirmed dead end** —
  do not retry. Vendor + build for `armeabi-v7a` via NDK 28.2.
- **`.so` behavior** (`JNI_OnLoad`): resolve `memcpy`/`memmove`, inline-hook them; in the hook run
  the ported C: size gate `512..N`, `find_marker` for `intraTitlePlaylist":[`, parse the whole
  array, and **only if it is COMPLETE** (closing `]`, no truncated element) blank each
  `{"type":"Remote",...}`+comma with spaces; then call the original. Skip truncated buffers.
- **Bundle + load:** existing `patch/` Kotlin. **Verify:** logcat tag (e.g. `PVNativeHook`)
  logging "blanked N Remote in complete array" — verify the EFFECT on-device, not just "Applied".
- **Ship:** Morphe CLI patches stock APK → self-sign → install → cold-start verify → branch → PR →
  merge → semantic-release.

**Recommendation:** Track A for the multi-day soak, then Track B for release.

## 5. JS → C porting spec (the one piece of net-new code)

Port from `cmod-strip2.js` (JS) into the `.so` (C), operating on the memcpy `src` before the copy:
- `scanVal` — string/nesting-aware value scanner; returns **truncation sentinel** if a value has
  no closing quote/brace within the buffer (this is what makes "complete only" safe).
- `parseComplete` — parse the array from `[`; return NULL if ANY element is truncated or the array
  never closes with `]`. NULL ⇒ do not touch the buffer.
- `blankRanges` — for each `Remote` element, blank `[start, commaAfter+1]` (or `[prevComma, end]`
  for the last) with 0x20 spaces. Same length ⇒ JSON stays valid, buffer length unchanged.
- Keep the native filter cheap: cheap byte-anchor before full compare; only parse on a marker hit
  (rare). No allocation on the hot path.

## 6. Must-resolve BEFORE release

1. **TV shows** — denser/more breaks, possibly different playlist shape. Dial in on Track A first
   (it's the known gap); do not ship the patch until TV is covered or explicitly scoped out.
2. **Complete-copy assumption** — we rely on the full array arriving in one large copy
   (`libc.so+0x3ea01` / `libignite+0xae44a5`). Content-keyed (`arrayClosed` + Remote), so
   version-robust, but validate across more titles and after an app update.
3. **Permanent-hook perf** — a lifetime memcpy hook vs. a session-long one. Measure playback perf
   over hours; confirm the gate keeps overhead negligible.
4. **Size gate upper bound** — currently `512..262144`. A very long movie/series could exceed
   256KB; raise the bound without reintroducing scan cost on huge video copies.
5. **Idempotence / multi-copy** — confirm blanking the first complete copy reliably reaches the
   parser across titles (bench showed 1–2 complete-copy sites per load; all blanked cleanly).

## 7. Recommended sequence

1. Track A: bake gadget auto-load; run the multi-day cold-start soak (movies).
2. Dial in TV shows (extend/verify the complete-array rule on series playlists).
3. Track B: vendor ShadowHook, port the C, wire the memcpy hook into the scaffold's loader+patch.
4. On-device logcat verify (effect, not "Applied"); breadth test movies + TV.
5. Branch → PR → `gh pr checks --watch` → merge → semantic-release.
