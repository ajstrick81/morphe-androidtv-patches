# Netflix — native ad-strip toolkit portability assessment

**Target:** `com.netflix.ninja` (Netflix Android TV), build `13.0.0-25009`, single-arch APKM.
**Toolkit:** Native in-process ad-strip toolkit (Android TV / Morphe), extracted from the
Prime Video `libignite` work.
**Question:** does the toolkit's methodology port to Netflix?
**Date:** 2026-07-22 · **Status:** paper recon only — no APK/device bytes examined yet.

> Honesty note. This is a desk assessment written **without the APK**. The `.apkm` the
> request pointed at is a local Windows file (`C:\Users\…\Downloads\com.netflix.ninja_…apkm`),
> which the cloud build environment can't reach. Every line below tagged **[VERIFY]** is a
> claim that must be confirmed on the bench against the real binary before any code is
> written — exactly what METHODOLOGY §1 demands. Nothing here is a proven offset or seam.

---

## 0. Toolkit self-check (done, in this environment)

The toolkit's reference transforms build and pass here, so "the toolkit works" is not in
question — only whether Netflix is a fit:

| Suite | Build line | Result |
|---|---|---|
| `prs_blank` | `g++ … test_prs_blank.cpp prs_blank.cpp` | PASSED (75 checks) |
| `prs_reassembly` | `g++ … test_prs_reassembly.cpp prs_filter.cpp manifest_filter.cpp prs_blank.cpp -lz` | PASSED (28 checks) |
| `prs_filter` | `g++ … test_prs_filter.cpp prs_filter.cpp manifest_filter.cpp -lz` | PASSED |
| `manifest_filter` | `g++ … test_manifest_filter.cpp manifest_filter.cpp prs_filter.cpp -lz` | PASSED |

---

## 1. Recon — what kind of ad delivery is this? (METHODOLOGY §0)

Netflix's ad tier is **server-side stitched (SSAI-class)**: ad breaks are delivered inside
the same adaptive stream from Netflix's own Open Connect CDN, with the break schedule
negotiated by the native playback runtime — not by bytecode the app exposes to us, and not
splittable by DNS (ads and content share hosts/session). That places Netflix squarely in
the category the toolkit exists for: **the media plane fetched by the app's native
pipeline over a shared TLS session.** So on *category* it's a match.

Cheap-win triage (do these rule-outs first, they're free):
- **DNS split?** [VERIFY] Almost certainly no — Open Connect serves ads and content from the
  same appliance/host. Confirm with a capture before spending native effort.
- **Bytecode chokepoint?** [VERIFY] Unlikely to reach the media plane. The Ninja app is a thin
  Java shell around a large native runtime + JS UI; the ad schedule is handled natively, so
  there is probably no ExoPlayer `AdPlaybackState` / OkHttp seam to patch. Confirm by
  decoding the APK and grepping smali for a player/ads surface.

## 2. The Netflix-specific risk that Prime Video did **not** have: MSL

This is the crux of the port and the reason Netflix is materially harder than Prime Video.

Netflix wraps its app-layer traffic in **MSL (Message Security Layer)** — its own
end-to-end message crypto *on top of* TLS. The toolkit's whole premise is "hook the seam
where the bytes are already plaintext, between TLS-decrypt and the native parser." On
Netflix, decrypting TLS is **not enough**: at `SSL_read` the manifest/schedule is still MSL
ciphertext. Plaintext only appears **after MSL decryption, deeper inside the native runtime**.

Consequences for the checklist:
- **§2 seam-finding moves.** The generic candidates the scaffold leads with —
  `SSL_read` / `inflate` / `memcpy` at the TLS boundary — will show you MSL ciphertext, not
  the ad schedule. The real seam is a post-MSL-decrypt buffer inside Netflix's own media
  `.so`. [VERIFY] the target soname by decoding the APK (`lib/<abi>/`); candidates are the
  nrdp/mediapipeline libraries — **do not assume a name; read it off the APK.**
- **§3 offsets get harder.** You're recovering a function inside a much larger, Netflix-proprietary
  library, without the friendly OpenSSL/zlib `.rodata` anchor strings `find_offsets.py`
  keys on for Prime Video. Expect to lean on the Frida `find-copy-seam.js` memcpy bench
  (scaffold/tools/frida) to locate *which* copy carries the plaintext schedule, then Ghidra
  from there.
- **Anti-tamper / DRM.** [VERIFY] Widevine (often L1 on TV) and Netflix's integrity checks
  raise the odds that an injected `.so` or a repackaged APK is detected. This needs an
  empirical "does it even boot patched" check early, before transform work.

**Bottom line:** category-fit, but the toolkit's fast path (hook the TLS/inflate seam) is
blocked by MSL. Viability hinges entirely on §1: can we reach the ad schedule *in plaintext*
at some in-process seam, and does stripping it yield clean playback? Until a capture answers
that, writing any transform or `.so` is premature (METHODOLOGY §1: "If stripping breaks
playback, stop").

## 2b. Corroboration from a web-traffic teardown (sshh12 gist)

Source: `gist.github.com/sshh12/dda3a89514f850c459380b18b1f7eb7b` — a reverse-engineering of
177 captured requests from an authenticated **web** session (Akira SPA / Cadmium player).
It is web, not `com.netflix.ninja`, so endpoint *paths* are web-specific — but MSL, the
manifest concept, and Open Connect are shared with the TV runtime, so it's real corroboration
(captured, not inferred) of the §2 blocker. What it confirms:

- **MSL is exactly the wall we described.** From the capture: *"The entire request/response is
  MSL-encrypted. The 32KB request body contains the MSL mastertoken + encrypted manifest
  request."* Header `Content-Encoding: msl_v1`; body `{mastertoken, headerdata, payload}`, all
  base64+encrypted. So at the TLS seam you get MSL ciphertext — the plaintext manifest exists
  only post-MSL-decrypt inside the runtime. Confirms §2 as observed fact.
- **Two planes, and the strip target is the control plane.**
  - *Control plane* — the **licensed manifest** (web path `POST /msl/playapi/cadmium/licensedmanifest/1`,
    returns Widevine license + Open Connect stream URLs + codec/timeline). MSL-wrapped. **This is
    where an ad break schedule/markers would live** and what a transform must reach *after* MSL decrypt.
  - *Media plane* — segments are plain **byte-range HTTP GETs** from `*.oca.nflxvideo.net/range/…`
    with a signed `t=` token (~12h expiry). Not MSL-encrypted, but Widevine-encrypted media.
    Confirms the §0 rule-outs: ads and content share Open Connect hosts → **DNS can't split them**,
    and the schedule isn't in bytecode → **no OkHttp/ExoPlayer chokepoint.**
- **New lead — the ad system is internally "Monet".** The gist names Netflix's ad tech *Monet*
  but (like every source so far) carries **no ad-break schema** — the author's session hit no ad
  break. Still, "Monet" is a concrete string/term to hunt in the APK's `.so` symbols/strings and
  in any ad-tier manifest.

Net effect on the port: this **raises confidence that the seam is post-MSL in the native runtime**
(not at the TLS boundary) and gives the manifest's shape to expect — but it does **not** supply the
ad data model. The gap is unchanged: we need a *post-MSL manifest from the ad tier* (a Frida MSL
hook, or a `pymsl`-style manifest client logged in on an ad plan), or a device capture of a break.

### Ad event taxonomy (Netflix Tech Blog: "Robust Ads Event Processing Pipeline")

A **backend** data-engineering piece (server-side "Ads Event Publisher" → Kafka → reporting/billing;
Microsoft/Xandr ad server). No on-device schema or beacon URLs — but it fixes the *vocabulary* we'll
see when a manifest is finally captured: standardized ad events of the **impression / quartile
(25/50/75) / complete** family, per break/creative. When we get an ad-tier manifest, expect fields in
these families: break id + start/duration, creative id, and tracking/beacon URLs per quartile.

**Open bench question it raises (not answered by the article):** are those beacons **client-fired**
(device holds beacon URLs + break timing in-process — a rich strip/suppress target) or **server-fired**
(SSAI; client gets only coarse break timing)? Evidence the client holds *some* structured break data
regardless: the TV/web UI shows an ad countdown, "ad X of Y", and disables seek during breaks (the
Auto-Skip web extension keyed on that very duration display). So an on-device ad model exists to
target; determine client-vs-server beacon firing on the bench — it decides whether the win is
"strip the schedule" or "also suppress client beacons."

## 3. APK decode — `base.apk` (measured 2026-07-22)

First real bytes examined. Split APKM; the user uploaded the **base split** only (native libs
live in the `config.<abi>` splits, still pending). Decoded with `unzip` + `pyaxmlparser`.

- **Provenance.** `base.apk` SHA-256 `14223fcf0688bf5be6b7b101cceeff51bdb284274e2ce9f8fa70208cc5e7ddca`,
  6.17 MB. `package="com.netflix.ninja"`, `versionName="13.0.0"`, `versionCode="25009"`,
  `compileSdkVersion=36`. Matches the target filename. ✅
- **Application subclass — RESOLVED.** `AndroidManifest.xml`:
  `<application android:name=".NetflixApplication" …>` →
  `Lcom/netflix/ninja/NetflixApplication;`. This is the `onCreate` fingerprint target for
  `LoadNativeHookPatch`. (`appComponentFactory="o.isCurrent"` — obfuscated, not needed.)
- **`extractNativeLibs="false"` — RESOLVED, and it matters.** The manifest already sets this
  false. The toolkit's `BundleNativeHookPatch` must flip it to `true` (its documented fix for
  the alignment `UnsatisfiedLinkError` when injecting a `.so`).
- **Native runtime named — `<TARGET_SONAME>` candidates RESOLVED.** No `lib/` dir in base.apk
  (0 `.so`), but `assets/nrd/armeabi-v7a/26.1/` carries nrdp's versioned-library manifest:
  ```
  libandroid_netflix.so=2a79c1d36122d40ba04576d5399cb02b   # the nrdp media runtime — prime seam target
  libc++_shared.so     =c7d7cf55ba9847fd7f50d9a95a8ba2f4
  ```
  plus `info: version=26.1, arch=armeabi-v7a, default=true`. The Java bootstrap loader also
  references `libnetflix.so` ("loadLibrary - libnetflix.so", "no libraries installed for
  version: %s"). So: **`libnetflix.so` = bootstrap stub; `libandroid_netflix.so` = the big
  nrdp runtime** that decrypts MSL and drives playback → **the seam lives here.**
- **ABI = `armeabi-v7a` (32-bit).** nrd runtime is v7a; ship the hook `.so` for v7a first
  (matches the toolkit's default and the Prime Video reference).
- **nrdp uses versioned, MD5-checked, possibly out-of-band libraries.** The `libraries`
  manifest + version check imply `libandroid_netflix.so` can be updated independent of the
  APK. **Consequence for §3 offsets:** addresses are per-nrd-build (26.1 here), even more
  version-fragile than usual — the runtime `sigscan` fallback is mandatory, not optional.
- **No Java-layer ad surface (confirms native-only).** dex string sweep: `manifest`×32,
  `msl`/`MSL`×22, `nrdp`×96, but `Monet` 0, `adBreak`/`AdBreak` 0, `ExoPlayer` 0,
  `quartile`/`beacon`/`ssai` 0. The 52 `advert` hits are **Bluetooth LE advertising** +
  **Google Advertising ID** (`AdvertisingIdClient`, `DEVICE_STR_ID_ADVERTISING_ID`) — the GAID
  used for targeting, **not** the break schedule. The MSL Java strings are DRM session mgmt
  (`com.netflix.mediaclient.service.configuration.drm.MSLWidevineDrmManager`), not the manifest.
  → **No bytecode/ExoPlayer chokepoint exists; the ad schedule is entirely inside
  `libandroid_netflix.so` past MSL decrypt.** This is the §2 thesis, now confirmed against bytes.

**Still pending (needs the `config.<abi>` split):** the actual `libandroid_netflix.so` bytes —
to confirm whether it ships in the split's `lib/armeabi-v7a/` or is downloaded at runtime,
record its SHA-256, and start Ghidra/`strings` on it for the MSL-decrypt + manifest seam.

## 4. Mechanical port worksheet (placeholder fills, PORTING-CHECKLIST)

Updated with measured values from §3. `[VERIFY]` items now resolved except the transform choice.

| Placeholder | Value |
|---|---|
| `<app>` | `netflix` |
| `<App>` / `<APP>` | `NF` / `NFNativeHook` |
| `<HOOK>` | `nfhook` → `libnfhook.so` |
| `<app.package.name>` | `com.netflix.ninja` |
| `<app/Application/subclass>` | ✅ `Lcom/netflix/ninja/NetflixApplication;` |
| `<compat version>` | ✅ `13.0.0-25009`, ABI `armeabi-v7a` (nrd runtime v26.1) |
| `<TARGET_SONAME>` | ✅ `libandroid_netflix.so` (nrdp runtime; `libnetflix.so` is the bootstrap stub) |
| `extractNativeLibs` | ✅ currently `false` → patch must force `true` |

Which reference transform to start from (checklist §2) is still **undecided** until we see the
schedule format at the seam: JSON schedule → `prs_filter`/`prs_blank` family; HLS/DASH manifest
with SSAI markers → `manifest_filter`. Resolve by capturing/dumping one post-MSL manifest.

## 6. What I need next to continue

base.apk is decoded (§3). Remaining, in priority order:
1. **The `config.armeabi-v7a` split** (the "bigger" part). Locates the actual
   `libandroid_netflix.so` bytes (or proves nrdp downloads them at runtime), records its
   SHA-256, and lets me `strings`/Ghidra it for the MSL-decrypt + manifest seam.
2. **A post-MSL manifest from an ad-tier account** — Frida hook on nrdp's MSL decrypt, or a
   `pymsl`-style client on an ad plan. This hands us the actual ad-break schema and decides the
   transform (`prs_*` vs `manifest_filter`). Still the single biggest unknown.
3. Eventually device-side Frida to run `find-copy-seam.js` against `libandroid_netflix.so` and
   pin the seam for §3 offsets.

Status: **category-confirmed and target-lib identified from bytes; blocked on the ad-break
schema (needs a post-MSL ad-tier manifest).**

## 7. Prior art surveyed (and why it doesn't move us)

Community repos evaluated for Netflix ad-structure insight. Pattern so far: they
operate at the wrong layer (server-side, or web-DOM) for a native-TV media-plane strip.

| Repo | What it is | Useful for us? |
|---|---|---|
| `cruizviquez/Micro-Netflix-Ads-Ctr` | Flask + scikit-learn CTR **simulation** on synthetic data; Netflix-styled UI. No real internals. | No — models the ad *server's* decisioning, not the client media plane. |
| `Dreamlinerm/Netflix-Prime-Auto-Skip` | Browser **web** extension; detects ads by DOM scraping (`span[class*="mmvz9h"]`, `data-uia="pause-ad-*"`) and skips via `video.playbackRate=8` + mute. No manifest/API. | No — wrong platform (web DOM, not native nrdp) and wrong strategy (drives the player, doesn't strip the stream). Selectors don't exist in `com.netflix.ninja`. |
| `sshh12/…dda3a89514…` (gist) | Web-session network teardown (177 reqs): names MSL, licensed-manifest endpoint, Open Connect byte-range streaming, ad system "Monet". | **Partially** — see §2b. Corroborates the MSL wall + manifest shape from real captures and adds the "Monet" lead; still no ad-break schema. Web endpoints, not native. |
| `medium.com/@sankalp25103/inside-netflix…` | High-level **system-design** breakdown (server-side: microservices, Open Connect, encoding pipeline). Assessed by genre + search; article 403s the fetcher. | No — one/two layers above the on-device seam. No Android/native/MSL/ad-schedule detail; strictly subsumed by the §2b gist. Context only. |
| `netflixtechblog.com/…ads-event-processing-pipeline` | **Backend** ads telemetry pipeline (Ads Event Publisher → Kafka; Microsoft/Xandr). Primary 403s; assessed via search snippets + mirrors. | Context — see §2b "Ad event taxonomy". Fixes the impression/quartile/complete event vocabulary to expect; no on-device schema/beacon URLs. Raises the client-vs-server beacon-firing bench question. |

Strategic note: both sidestep the stream rather than strip it. The "skip/accelerate the
player" idea has a native analogue (hook nrdp playback control, not the media bytes) but
that's a different hook target than this toolkit's and is unproven-reachable — flag it as an
alt path, not a lead.

## 8. Verdict

- ✅ Toolkit is real and its transforms pass here.
- ✅ Netflix is the *right category* of target (native media plane, shared TLS, no DNS/bytecode reach) —
  now **confirmed against base.apk bytes**: no ExoPlayer/OkHttp/Java ad surface exists.
- ✅ **Target library identified from bytes:** `libandroid_netflix.so` (nrdp runtime v26.1,
  armeabi-v7a); Application subclass and `extractNativeLibs` resolved for the Morphe patches.
- ⚠️ Netflix is **harder than Prime Video**: MSL hides the plaintext behind Netflix's own
  crypto layer, so the scaffold's fast TLS/inflate seam won't work; the seam is deeper (inside
  `libandroid_netflix.so`, post-MSL) and offset recovery has no OpenSSL/zlib string anchors.
  nrdp's versioned/out-of-band libs make addresses extra version-fragile → runtime sigscan mandatory.
- ⛔ **The one hard blocker now:** the ad-break schema. Needs a post-MSL ad-tier manifest
  (Frida MSL dump or `pymsl` on an ad plan) — not derivable from the APK alone.
