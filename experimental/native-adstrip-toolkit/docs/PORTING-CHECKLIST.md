# Porting checklist — adapt the scaffold to a new APK

Concrete find-and-replace + decisions to stand up a new target. Work top to
bottom; each step gates the next.

## Placeholders used across the templates

| Placeholder | Meaning | Example (Prime Video) |
|---|---|---|
| `<app>` | lowercase patch package segment | `primevideo` |
| `<App>` / `<APP>` | display / tag casing | `<App>NativeHook` → `PVNativeHook` |
| `<HOOK>` | native lib base name (`lib<HOOK>.so`) | `pvhook` |
| `<app.package.name>` | Android package id | `com.amazon.amazonvideo.livingroom` |
| `<app/Application/subclass>` | smali path of the Application class | `Lcom/amazon/ignition/IgnitionApplication;` |
| `<compat version>` | `Constants.COMPATIBILITY` target | `6.23.23+v15.5.0.70-armv7a` |
| `<TARGET_SONAME>` | the `.so` you hook | `libignite.so` |

## 1. Recon & bench (no code yet)

- [ ] Classify the ad mechanism (METHODOLOGY §0) and rule out DNS/bytecode.
- [ ] Capture a real ad break (movies AND TV) and confirm it's strippable
      (METHODOLOGY §1).
- [ ] Bench-find the seam + delivery shape (FINDING-THE-SEAM). Record: module
      name, seam function, whole-vs-chunked.

## 2. Transform (host code)

- [ ] Copy the closest example transform as a starting point:
      - JSON schedule strip, push seam → `prs_blank.{h,cpp}` (same-length blank).
      - JSON schedule strip, pull seam → `prs_filter.{h,cpp}` +
        `prs_reassembly.h` (shrink + reassemble).
      - HLS/DASH manifest SSAI strip → `manifest_filter.{h,cpp}` +
        `ssl_reassembly.h`.
- [ ] Change the **marker** (the key/token you detect) and the **ad predicate**
      (what makes an element an ad) for your app's format.
- [ ] Write/adapt the host test; cover the edges in TESTING §3.
- [ ] **Mutation-test** it; run under sanitizers. Don't proceed until green
      *and* proven-able-to-fail.

## 3. Offsets

- [ ] Pull the exact `.so`, record SHA-256.
- [ ] Recover the seam entry, build a unique signature + fallback
      (GHIDRA-OFFSETS).
- [ ] Fill `offsets.h` (rename `SEAM1`/`SEAM2`, set `TARGET_SONAME`, SHA).

## 4. Native lib

- [ ] Rename `<HOOK>` in `CMakeLists.txt`; add your transform source(s).
- [ ] Vendor ShadowHook under `jni/hook/`.
- [ ] Fill `hooks.cpp`: resolve module → sigscan → install → call your
      transform. Set the log `TAG`. Handle Thumb bit0 if applicable.
- [ ] Build for the shipping ABI. Output `lib<HOOK>.so`.

## 5. Morphe patches

- [ ] Instantiate the three templates (`BundleNativeHookPatch`,
      `LoadNativeHookPatch`, `Fingerprints`, `NativeHookLoader`), replacing all
      placeholders.
- [ ] **Confirm the Application subclass** in the decoded `AndroidManifest.xml`
      (`<application android:name=…>`) and set it in `Fingerprints` — an onCreate
      fingerprint on the wrong class silently won't resolve.
- [ ] Check the built `lib<HOOK>.so` into
      `patches/src/main/resources/native/<abi>/`.
- [ ] Add an R8 `-keep` for `NativeHookLoader.load()`.
- [ ] Register both patches; gate on `Constants.COMPATIBILITY`.

## 6. On-device verify

- [ ] logcat `<APP>NativeHook`: seam resolved, N items stripped on playback.
- [ ] Ad gone on screen; no ad GETs after strip.
- [ ] Breadth-test movies + TV; re-verify after an app update.
