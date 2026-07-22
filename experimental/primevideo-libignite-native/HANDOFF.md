# Session handoff → local PC project (Prime Video native interception)

**Read this first.** It orchestrates the PC-side work; the design is in
[`README.md`](./README.md) and the Ghidra worksheet is in
[`OFFSETS.md`](./OFFSETS.md).

- **Branch:** `claude/primevideo-interception-handoff-pxhsn0`
- **Session tip commit:** `7314766` (11 commits, base `962d40e` = release v1.13.0)
- **Get it on the PC:**
  ```
  git fetch origin claude/primevideo-interception-handoff-pxhsn0
  git checkout claude/primevideo-interception-handoff-pxhsn0
  ```

---

## What this session produced

Two independent workstreams. Neither changes what a build produces *yet* — the
native module is not wired into the build, and the extension changes are
behavior-preserving refactors.

### 1. Native in-process ad-strip scaffold (`experimental/primevideo-libignite-native/`)
The "endgame" successor to the MITM rig: strip `/iad_` mid-roll ad segments
inside the Prime Video process by hooking `SSL_read` / `inflate` in
`libignite.so`. Complete except the two Ghidra-recovered offsets. Includes:
- `jni/` — hooks bootstrap, runtime signature scanner, HLS/DASH manifest filter,
  post-inflate `z_stream` bookkeeping, chunked `SSL_read` reassembler.
- `patch/` — Morphe patches: bundle the `.so` into `lib/`, load it at startup.
- `extension/NativeHookLoader.java` — the 5-line loader (belongs in the existing
  extension module once promoted).
- **103 host assertions**, all green (see "Re-verify" below).

### 2. Extension refactors + tests (`extensions/extension/.../primevideo/ads/`)
Made the shipping ad-block logic testable by extracting two pure helpers; both
refactors are behavior-preserving (see commits `f8e3338`, `7314766`):
- `AdHostFilter.java` — the `enforceAdBlock` host/path policy (was entangled with
  Volley). **34 host assertions.**
- `AdGroupStripper.java` — the shared strip loop behind `skipAllMedia3AdGroups`
  / `skipAllExo2AdGroups` (also deduplicates the two). **17 host assertions.**

---

## Re-verify on the PC (all green here; reproduce to confirm the clone is intact)

**Native filter/reassembly/sigscan/sniff/inflate — 103 assertions:**
```
cd experimental/primevideo-libignite-native/jni
# NB: manifest_filter.cpp calls pvfilter::filter_prs (in prs_filter.cpp) and the
# prs test calls pvfilter::filter (in manifest_filter.cpp) — both .cpp are needed
# to link, so every native test binary lists both lib sources.
g++ -std=c++17 -D_GNU_SOURCE -Wall test_manifest_filter.cpp manifest_filter.cpp prs_filter.cpp -lz -o /tmp/pvtest_manifest && /tmp/pvtest_manifest | tail -1
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_filter.cpp      prs_filter.cpp manifest_filter.cpp -lz -o /tmp/pvtest_prs      && /tmp/pvtest_prs      | tail -1
# expect both: ALL TESTS PASSED (0 failure(s))
```

**Memcpy-seam blanker (the productized transform, PRODUCTIZATION.md §5) — 75 checks:**
```
cd experimental/primevideo-libignite-native/jni
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_blank.cpp prs_blank.cpp -o /tmp/pvtest_blank && /tmp/pvtest_blank | tail -1
# expect: ALL TESTS PASSED (75 check(s), 0 failure(s))
```

**PRS whole-body reassembler (the TV-show fix — reproduces the MITM rig, §6.1) — 28 checks:**
```
cd experimental/primevideo-libignite-native/jni
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_reassembly.cpp prs_filter.cpp manifest_filter.cpp prs_blank.cpp -lz -o /tmp/pvtest_reasm && /tmp/pvtest_reasm | tail -1
# expect: ALL TESTS PASSED (28 check(s), 0 failure(s))
```

**Extension host filter + ad-group stripper — 34 + 17 assertions:**
```
cd extensions/extension/src
mkdir -p /tmp/adtest
javac -d /tmp/adtest \
  main/java/ajstrick81/morphe/extension/primevideo/ads/AdHostFilter.java \
  main/java/ajstrick81/morphe/extension/primevideo/ads/AdGroupStripper.java \
  test/java/ajstrick81/morphe/extension/primevideo/ads/AdHostFilterTest.java \
  test/java/ajstrick81/morphe/extension/primevideo/ads/AdGroupStripperTest.java
java -cp /tmp/adtest ajstrick81.morphe.extension.primevideo.ads.AdHostFilterTest   | tail -1
java -cp /tmp/adtest ajstrick81.morphe.extension.primevideo.ads.AdGroupStripperTest | tail -1
# expect both: ALL TESTS PASSED (0 failure(s))
```

---

## PC action plan (ordered cheapest-risk-reduction first)

### Step 0 — Verify the extension still builds (do this first; ~minutes)
The extension refactor was **not** compiled in the cloud container (no Android
SDK / media3 / Guava / Volley there). It's a behavior-preserving extraction, but
verify before trusting it:
```
./gradlew :extensions:extension:build
```
If it fails, the likely culprits are the `AdGroupStripper.AdState` anonymous
classes or the raw-type `ImmutableMap` conversion in `SkipAdsPatch.java` — both
are small and localized.

### Step 1 — Empirical de-risk: is the mid-roll actually manifest-strippable? (cheap, highest-value)
This is the single biggest unknown and needs no Ghidra. On the Onn 4K Plus,
capture a real mid-roll manifest (the MITM rig already gives you plaintext) and
confirm **both**:
1. the ad segments carry the `/iad_` marker the filter keys on, **and**
2. removing those segments/periods yields **clean playback** (SSAI stitch timing
   survives the strip — the filter shrinks the manifest, the player must glide
   over the gap).

You can test (2) offline right now: run a captured manifest through the filter
and diff. If real markers differ from `/iad_`, update `AD_TOKEN` in
`jni/manifest_filter.cpp` and re-run the 103 tests. **If stripping breaks
playback, the whole native approach needs rethinking before any Ghidra effort —
so do this first.**

### Step 2 — Ghidra pass: recover SSL_read / inflate (the gating task)
Follow [`OFFSETS.md`](./OFFSETS.md) against the device's **armeabi-v7a**
`libignite.so`. Fill signatures + fallback offsets + the SHA-256 into
`jni/offsets.h`. Enable `ENABLE_INFLATE_HOOK` only if manifests come back gzip'd.

### Step 3 — Build `libpvhook.so`
Vendor Dobby under `jni/dobby/`, then build per `jni/CMakeLists.txt` (armeabi-v7a
first). Output → `patches/src/main/resources/native/armeabi-v7a/libpvhook.so`.

### Step 4 — Promote scaffold → real patch tree
Follow the **Promotion checklist** in [`README.md`](./README.md): move `patch/`
→ `patches/.../primevideo/nativehook/`, `NativeHookLoader.java` → the extension
module, add the R8 `-keep`, register `bundleNativeHookPatch` +
`loadNativeHookPatch`.

### Step 5 — Build patched APK + verify on-device
Watch logcat tag `PVNativeHook`:
- `resolved SSL_read @ 0x… (via signature)` / `inflate @ 0x…`
- `stripped N ad segment(s) / M period(s)` on playback start
- `DOWNLOADER` tag: no `/iad_` GETs after a strip
- If `could not load libpvhook.so` → the `extractNativeLibs`/alignment issue
  (`BundleNativeHookPatch` forces `extractNativeLibs="true"` to avoid it).

---

## Risk register / open decisions

| Risk | Where it bites | Mitigation status |
|---|---|---|
| Mid-roll not cleanly strippable from manifest | Step 1 | **Unverified — do first.** Filter is tested; the *assumption* isn't. |
| Extension refactor doesn't compile | Step 0 | Behavior-preserving; unverified in-cloud. Quick to confirm/fix. |
| Ghidra signature too short / not unique | Step 2 | `sigscan` enforces uniqueness and logs; `offsets.h` keeps a fallback offset. |
| `.so` alignment → UnsatisfiedLinkError | Step 5 | `extractNativeLibs="true"` forced. |
| One `SSL_read` ≠ whole manifest | Step 5 | `SslReassembler` built + tested; transparent wiring needs HTTP framing (device-side). `inflate` is the cleaner whole-body point. |
| Phase B (markerless inline SSAI) | future | Not built; needs a request-side curl hook. |

## Likelihood summary (from the session's assessment)
- **APK patches & installs:** ~90% (→~99% after Step 0).
- **Suppresses the ads it already did (control-plane/SSAI):** ~85%, behavior preserved.
- **Suppresses mid-roll `/iad_` via the native module:** ~30–50% end-to-end on
  first serious attempt — stacked integration/empirical risks, Step 1 being the
  dominant unknown.

## File inventory
- Design & rationale: `README.md`
- Ghidra worksheet: `OFFSETS.md`
- Native sources + tests: `jni/`
- Morphe patches: `patch/`
- Native loader (Java): `extension/NativeHookLoader.java`
- Refactored shipping logic + tests: `extensions/extension/.../primevideo/ads/`
  (`AdHostFilter`, `AdGroupStripper`, `SkipAdsPatch`, `*Test`)
