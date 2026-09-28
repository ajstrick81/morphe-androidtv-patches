# Native in-process ad-strip toolkit (Android TV / Morphe)

A battle-tested **methodology + scaffolding** for building *no-PC, in-process,
native* ad-strip patches for Android TV APKs — extracted and generalized from
the Prime Video project (included whole as a worked example under
[`examples/primevideo/`](examples/primevideo/)).

> **What problem this solves.** Some ads can't be killed from bytecode or DNS:
> the ad segments arrive over the *same* TLS session, from the *same* CDN host,
> fetched by the app's *native* media pipeline. The only way to touch them
> without a PC/proxy is to intercept *inside the app process*, at a point where
> the bytes are already plaintext, and rewrite them before the native parser
> reads them. This toolkit is how you do that repeatably.

---

## The shape of every project built with this

```
  ┌───────────────────────── app process ─────────────────────────┐
  │  native downloader ─▶ TLS/BoringSSL ─▶ [decrypt] ─▶ (gzip?)     │
  │                                            │           │        │
  │                                            ▼        [inflate]   │
  │                                   ┌──────────────────────────┐  │
  │                                   │   OUR injected hook       │  │
  │                                   │   strip / blank / reassy  │  │
  │                                   └──────────────────────────┘  │
  │                                            ▼                     │
  │                              native HLS/DASH/JSON parser (unaware)│
  └────────────────────────────────────────────────────────────────┘
```

Three cooperating pieces, all templated here:
1. **A native `.so`** that hooks the plaintext seam and edits the ad bytes.
2. **A Morphe resource patch** that bundles the `.so` into `lib/<abi>/`.
3. **A Morphe bytecode patch** that loads it at `Application.onCreate` (so the
   hook is armed before the first playback session).

## Read these in order

| Doc | What it gives you |
|---|---|
| [`docs/METHODOLOGY.md`](docs/METHODOLOGY.md) | The end-to-end playbook (recon → seam → transform → offsets → build → ship). Start here. |
| [`docs/FINDING-THE-SEAM.md`](docs/FINDING-THE-SEAM.md) | The Frida bench method to locate *where* plaintext ad markers appear in-process. |
| [`docs/GHIDRA-OFFSETS.md`](docs/GHIDRA-OFFSETS.md) | Recovering stripped function offsets + turning them into runtime signatures. |
| [`docs/TESTING.md`](docs/TESTING.md) | The discipline that keeps this honest: host tests, mutation testing, sanitizers. |
| [`docs/LESSONS.md`](docs/LESSONS.md) | Hard-won pitfalls. Read before you burn a day rediscovering one. |
| [`docs/PORTING-CHECKLIST.md`](docs/PORTING-CHECKLIST.md) | Concrete find-and-replace to adapt the scaffold to a new APK. |

## Layout

```
docs/                    ← the generalized playbook (above)
scaffold/                ← generic, reusable building blocks
  jni/
    sigscan.{h,cpp}      ← runtime signature scanner (generic, copy as-is)
    sigmatch.h           ← masked-signature matcher (generic, copy as-is)
    test_harness.h       ← the tiny host-test harness
    offsets.h.template   ← the one file you fill after Ghidra
    CMakeLists.txt       ← NDK build (ShadowHook), rename <HOOK>
  patch/*.template       ← Morphe bundle + load + fingerprint patches
  extension/*.template   ← the 5-line System.loadLibrary loader
  tools/                 ← offset-recovery scripts + Frida bench methodology
    frida/               ← find-copy-seam.js, heap-oracle.js, driver.py
examples/
  primevideo/            ← the COMPLETE proven project: transforms, host tests
                           (103+34+75+28 assertions), docs, patches. Copy the
                           patterns from here; it all builds and passes.
```

## Quickstart (prove the example, then adapt)

```
cd examples/primevideo/jni
# the reference transforms + their host tests all build with plain g++:
g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_blank.cpp prs_blank.cpp -o /tmp/t && /tmp/t | tail -1
# -> ALL TESTS PASSED (75 check(s), 0 failure(s))
```
Then follow `docs/PORTING-CHECKLIST.md` for your target.

## Legal / scope

This is tooling for modifying apps you are authorized to modify, for personal
ad-filtering and interoperability research. Respect the terms and law that
apply to you. Nothing here exfiltrates data or targets other users; it edits a
response body in the app's own memory on your own device.
