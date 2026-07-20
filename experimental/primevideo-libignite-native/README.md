# Prime Video ATV — in-process native interception on libignite (SCAFFOLD)

**Status:** scaffolding, 2026-07-20. Not part of the build. This is the
"endgame" successor to the off-device MITM rig: move the TLS/manifest
interception *inside* the Prime Video process so the whole ad-strip runs on
the Onn 4K Plus with no PC, no proxy, no CA on the device.

Nothing here compiles into a working `.so` yet — the two runtime offsets it
depends on (`SSL_read`, `inflate` inside `libignite.so`) must be recovered
with Ghidra on your PC. See [`OFFSETS.md`](./OFFSETS.md). Everything *around*
those offsets — the hook install path, the signature scanner, the manifest
filter, and the bytecode patch that loads the library — is written and ready.

---

## Why this layer, and why it's the endgame

The shipping ad-strip (`patches/.../primevideo/ads`) owns the **control
plane**: media3 / ExoPlayer2 `AdPlaybackState`, Minerva metrics, and the
Volley `BasicNetwork` chokepoint. That kills SSAI scheduling, impression
reporting, and every ad host reachable over Volley.

What it provably **cannot** reach — documented in the extension's own header
comment — is the **media plane**: mid-roll ad segments delivered at an
`/iad_<id>/` path on the *same* safe-harbored CDN host as the movie, over the
*same* TLS session, fetched by Amazon's **native** MediaPipelineBackend
(libcurl, tagged `DOWNLOADER` in logcat). No bytecode hook touches it. DNS
can't split it from content (same host). The MITM rig only beat it by
terminating TLS off-device.

The endgame is to reproduce the rig's interception **in-process**:

```
  ┌─────────────────────────── Prime Video process ───────────────────────────┐
  │                                                                            │
  │   native DOWNLOADER ──▶ libcurl ──▶ BoringSSL ──▶ [SSL_read] ──▶ (gzip?)   │
  │                                                        │            │      │
  │                                                        │        [inflate]  │
  │                                                        ▼            ▼      │
  │                                              ┌──────────────────────────┐  │
  │                                              │   our injected hook      │  │
  │                                              │   manifest_filter():     │  │
  │                                              │   strip /iad_ segments   │  │
  │                                              └──────────────────────────┘  │
  │                                                        │                   │
  │                                                        ▼                   │
  │                                          native HLS/DASH parser (unaware)  │
  └────────────────────────────────────────────────────────────────────────────┘
```

We hook the points where bytes are **already plaintext** without us holding
the session keys:

- **`SSL_read`** — returns decrypted HTTP response bodies. Primary tap.
- **`inflate`** — returns decompressed bodies, for the gzip'd manifest case.
  (zlib, statically linked; hook only if `SSL_read` output is compressed.)

Both are statically linked into `libignite.so` **and stripped**, so
`dlsym()` returns null. That's the whole reason this was blocked: you can't
resolve them at runtime the easy way. Ghidra recovers them once; a runtime
**signature scan** (`sigscan.*`) re-finds them on each launch so a minor app
bump doesn't hard-break us to a new hardcoded address.

## The one design wrinkle to keep in mind

`SSL_read` / `inflate` hand you response **bodies**, not the request **URL**.
So from a single read you *cannot* say "this buffer is an `/iad_` segment."
That forces a clean two-phase split:

- **Phase A — manifest rewrite (this scaffold).** Ad segments are
  discoverable *inside* the manifest text: HLS `#EXT-X-DISCONTINUITY` runs and
  `/iad_` segment URIs, DASH ad `<Period>`s. We sniff each plaintext buffer;
  if it's a manifest, we strip the ad entries in place and hand the shortened
  manifest to the native parser. This is fully doable from response-body
  interception alone.

- **Phase B — request-side URL nulling (future).** For any ad delivery that
  is *not* distinguishable in the manifest (true inline SSAI with no marker),
  you need the request URL, which lives on the **request** side. That's a
  second hook on libcurl's request path (`curl_easy_setopt`/the DOWNLOADER's
  request builder) to drop segment GETs whose URL contains `/iad_`. Left as
  phase 2 — noted in `hooks.cpp` where it would wire in.

Phase A is the high-value 80%: on this build the ad segments *do* carry the
`/iad_` marker, so manifest rewriting should strip them without ever letting
them download.

## Target ABI

`Constants.COMPATIBILITY` pins `6.23.23+v15.5.0.70-**armv7a**` → build the
`.so` for **`armeabi-v7a`** first. `arm64-v8a` is included in
`CMakeLists.txt` for forward-compat but is untested until a 64-bit target
ships. Inline hooking differs per ABI; the hook lib (Dobby) handles both, but
your Ghidra signatures are **per-ABI** — an armv7a signature will not match an
arm64 binary.

## Layout

```
experimental/primevideo-libignite-native/
├── README.md              ← this file
├── OFFSETS.md             ← Ghidra worksheet: how to find SSL_read / inflate, where to paste results
├── jni/
│   ├── CMakeLists.txt     ← NDK build (armeabi-v7a + arm64-v8a); expects Dobby vendored in
│   ├── offsets.h          ← the ONLY file you edit after Ghidra: signatures + fallback offsets
│   ├── sigscan.h/.cpp     ← runtime byte-pattern scanner over libignite's .text
│   ├── manifest_filter.h/.cpp  ← the ad-strip logic (HLS + DASH), pure/testable
│   └── hooks.cpp          ← JNI_OnLoad bootstrap: resolve base, scan, install SSL_read/inflate hooks
└── patch/
    ├── Fingerprints.kt    ← Application.onCreate fingerprint
    └── LoadNativeHookPatch.kt  ← injects System.loadLibrary("pvhook") early in app startup
```

## Deployment path (what happens when you're home)

1. **Ghidra pass** (PC) — open the device's `libignite.so` (armeabi-v7a),
   find `SSL_read` and `inflate`, and fill in `jni/offsets.h`. Full
   step-by-step in `OFFSETS.md`.
2. **Vendor Dobby** — drop the Dobby source/prebuilt under `jni/dobby/`
   (see `CMakeLists.txt` header). It's the inline-hook engine for both ABIs.
3. **Build** — `ndk-build` / CMake to produce `libpvhook.so` for
   `armeabi-v7a`.
4. **Bundle + load** — the `patch/` files add `libpvhook.so` to the APK and
   call `System.loadLibrary("pvhook")` in `Application.onCreate`, so the hooks
   install before the first playback session. (Move them into the real patch
   tree per the checklist below to include them in a `morphe` build.)
5. **Verify on device** — logcat tag `PVNativeHook`:
   - `resolved SSL_read @ 0x… (via signature)` / `inflate @ 0x…`
   - `manifest_filter: stripped N ad segments (/iad_)` on playback start
   - watch the `DOWNLOADER` tag: no `/iad_` GETs should follow a strip.

## Promotion checklist (scaffold → real patch)

When it works on-device, move it into the build the same way speed-control
documents its reactivation:

1. `patch/Fingerprints.kt`, `patch/LoadNativeHookPatch.kt` →
   `patches/src/main/kotlin/ajstrick81/morphe/patches/primevideo/native/`
2. `libpvhook.so` (per ABI) → wherever the patcher sources bundled native libs
   for the resource-patch step (mirror how other native assets are added; if
   none exist yet, this is the first and needs a small ResourcePatch to copy
   into `lib/armeabi-v7a/`).
3. Add an R8 `-keep` for whatever class holds the `loadLibrary` call so the
   injected startup hook isn't stripped.
4. Register `loadNativeHookPatch` in the patch list and gate it behind the
   same `Constants.COMPATIBILITY` as the ads patch.

## Honest status / risks

- **Unbuilt & untested here.** This cloud container has no NDK, no device,
  and no `libignite.so`, so none of this has been compiled or run. Treat the
  C++ as reviewed-but-unverified skeletons.
- **Signature fragility.** If Ghidra signatures are too short they'll match
  multiple sites; too long and an app update breaks them. `offsets.h` keeps
  both a signature *and* a fallback file-offset for exactly this reason.
- **`SSL_read` volume.** It fires for *all* TLS traffic, not just manifests.
  The filter must cheaply reject non-manifest buffers (magic-byte sniff in
  `manifest_filter.cpp`) or it'll add latency to every read. Keep the fast
  path fast.
- **Reassembly.** A manifest can span multiple `SSL_read` calls. The scaffold
  handles the common single-read case and documents where buffering would go
  for the chunked case — do not assume one read == one manifest in the wild.
- **Phase B still open.** True markerless inline SSAI (if this build ever
  switches to it) needs the request-side hook. Not built.

See project memory `primevideo-native-speed-deadend` for the prior native
poke at this stack (playback-speed trick-play), which established that the
native engine is sealed to bytecode — this approach goes *under* bytecode
instead.
