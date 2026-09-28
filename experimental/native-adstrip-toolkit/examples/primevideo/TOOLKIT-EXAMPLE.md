# Prime Video — the worked example

This directory is the complete, proven reference project the toolkit was
extracted from. Everything here builds and its host tests pass; use it as the
concrete answer to "what does a real one look like?"

## How it maps to the toolkit

| Toolkit concept | Here |
|---|---|
| Recon / design / status | `README.md`, `PRODUCTIZATION.md`, `HANDOFF.md` |
| Seam finding (bench) | `frida/` — `find-copy-seam` ≙ `cmod-memcpy.js`, the proven strip is `cmod-strip2.js`, `cmod-diag.js`, `heap-scan-once.js`, `ssl_driver2.py` |
| Offsets | `OFFSETS.md`, `tools/`, `jni/offsets.h` |
| Transform — push seam, same-length | `jni/prs_blank.{h,cpp}` + `test_prs_blank.cpp` (75 checks) |
| Transform — pull seam, shrink + reassemble | `jni/prs_filter.{h,cpp}`, `jni/prs_reassembly.h` + `test_prs_reassembly.cpp` (28 checks) |
| Transform — SSAI manifest strip | `jni/manifest_filter.{h,cpp}`, `jni/ssl_reassembly.h` + `test_manifest_filter.cpp` (103 checks) |
| Native wiring | `jni/hooks.cpp`, `jni/sigscan.*`, `jni/CMakeLists.txt` |
| Morphe patches | `patch/*.kt`, `extension/NativeHookLoader.java` |

## The one-paragraph story

Prime Video ads are a **client-stitched schedule**: the `GetVodPlaybackResources`
(PRS) JSON lists `intraTitlePlaylist` items, and the `type:"Remote"` ones are
ads. Deleting them = no ad breaks scheduled. An off-device MITM proxy proved
this cleans movies AND TV. Moving it in-process, the payload turned out to be
decrypted inside libignite's static BoringSSL and visible only at `memcpy` (NOT
`SSL_read`/`inflate`, which the scaffold wrongly assumed). The push-seam blanker
(`prs_blank`, same-length, complete-array-only) got movies 100%; TV playlists
are larger/chunked, so the whole-body reassembler (`prs_reassembly`) restores
the proxy's "strip the complete body" behavior for them. Remaining work is
device-side: recover the whole-body pull seam (Ghidra), build the `.so`
(ShadowHook), ship the Morphe patch.

## Re-verify (all pass)

```
cd jni
g++ -std=c++17 -D_GNU_SOURCE -Wall test_manifest_filter.cpp manifest_filter.cpp prs_filter.cpp -lz -o /tmp/t && /tmp/t | tail -1
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_filter.cpp prs_filter.cpp manifest_filter.cpp -lz -o /tmp/t && /tmp/t | tail -1
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_blank.cpp prs_blank.cpp -o /tmp/t && /tmp/t | tail -1
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_reassembly.cpp prs_filter.cpp manifest_filter.cpp prs_blank.cpp -lz -o /tmp/t && /tmp/t | tail -1
```
