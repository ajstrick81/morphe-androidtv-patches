# Prime Video — native ad-hook source (`libpvhook.so`)

> **Not dead code, despite the `experimental/` path.** CI and release compile
> [`jni/`](./jni/) with the NDK (see the "Build libpvhook.so" step in
> `.github/workflows/ci.yml` / `release.yml`) and install the result at
> `patches/src/main/resources/native/armeabi-v7a/libpvhook.so`, which ships in
> every release.

- `jni/hooks.cpp` hooks libignite's memcpy/memmove imports (GOT/PLT).
- `jni/got_hook.*` rewrites those imports.
- `jni/remote_strip.*` strips PRS `Remote` ad items in-process.
- `jni/rego_filter.h` holds header-only helpers for the regolith
  getVideoAds ad-decision response.
- `jni/test_remote_strip.cpp` is a host-side unit test for both
  (`g++ -std=c++17 test_remote_strip.cpp remote_strip.cpp -o t && ./t`).

The Morphe side that bundles and loads the library lives in
`patches/src/main/kotlin/ajstrick81/morphe/patches/primevideo/nativehook/`, with
the Java loader in
`extensions/extension/src/main/java/ajstrick81/morphe/extension/primevideo/nativehook/`.

Earlier interception designs (SSL_read/inflate hooks, sigscan) are kept for
reference in [`../primevideo-research/`](../primevideo-research/) and
[`../native-adstrip-toolkit/`](../native-adstrip-toolkit/).
