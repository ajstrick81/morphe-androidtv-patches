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
- **"Monet" lead — weaker than it first looked (corrected after reading the full gist).** The
  gist's *only* Monet evidence is a **sandboxed marketing iframe** on Akamai
  (`ae.nflximg.net/monet/scripts/`) firing **conversion/retargeting pixels** —
  `adwords_Simplicity_NMLanding`, `fb_simplicity_nmLanding`, `tiktok_nmLanding` (signup-funnel
  tags), not video ad-break inserts. The author's session was a normal **non-ad-tier** browse, so
  no video ad break appeared → still **no ad-break schema anywhere in the gist.** Treat `/monet/`
  as *marketing* ad-tech; the ad-supported-plan video insertion is a separate surface this capture
  never touched. Don't key a strip on "Monet" without confirming it against an actual ad-tier break.

### How this teardown was produced — and why it doesn't extend to the app for free

The gist is **not APK/binary analysis** (it says so: "built entirely from live network traffic
analysis … 177 requests captured"). Its precision comes purely from **reading self-documenting HTTP
signals** in a captured **web** session: custom `x-netflix.*` headers, URL path segments
(`/cadmium/licensedmanifest/`, `/msl_v1/nrdjs/`), query params (`drmSystem=widevine`), GraphQL
persisted-query operation names, `Content-Encoding: msl_v1` — then naming each subsystem from its
evidence string (cross-ref'd to Netflix's open-source, e.g. Falcor). No decompilation.

Transfer to `com.netflix.ninja`:
- **Same §1 capture method applies** (device proxy + CA) and is worth doing to enumerate the app's
  endpoints/envelopes — but web is easy precisely because most web calls aren't MSL and DevTools
  exposes all; the **app pins to MSL for playback**, so the licensed-manifest call is captured only
  as ciphertext.
- **`/nrdjs/` in the web MSL path cross-confirms our target** — same NRD platform as the nrdp
  runtime inside `libnetflix.so`. Independent confirmation the seam is there.
- **Net:** the gist hands us the endpoint map and the MSL envelope shape, not the plaintext. To read
  the manifest on the app we still need the MSL decrypt hook (→ why we want `libnetflix.so`), on an
  ad-tier account, mid-break.

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

### 3a. Config split — native library inventory (measured 2026-07-22)

`config.armeabi-v7a` libs received (all ELF 32-bit ARM). The split ships **`libnetflix.so`**
(the Java loader's `loadLibrary("netflix")` target) — so that, not `libandroid_netflix.so`, is
the in-APK monolith runtime and the **seam target**. `libandroid_netflix.so` from the nrd
`libraries` manifest appears to be an nrd-managed/out-of-band variant not present in the split.

| Library | Size | SHA-256 (short) | Identity / relevance |
|---|---|---|---|
| **`libnetflix.so`** | **~84 MB** | *(pending upload)* | **THE TARGET.** Monolith nrdp runtime; MSL decrypt + manifest parse + player live here, past the TLS boundary. Statically links its crypto (assume BoringSSL) — system `libssl` won't see plaintext. |
| `libcronet.92.0.4515.131.so` | 3.2 MB | `e977261e…` | **Chromium Cronet** (Chromium 92) HTTP/TLS stack, BoringSSL inside. The `SSL_read` seam is here but yields **MSL ciphertext**, not the schedule. Confirms §2. |
| `libc94d.so` | 405 KB | `b58d0e16…` | **Obfuscated/stripped** (hash-name; only `JNI_OnLoad` + self-contained `_Unwind_*`/`__aeabi_*` exported). Likely a protected anti-tamper/security module. Watch, not target. |
| `libbugsnag-root-detection.so` | 3.7 KB | `20756e73…` | Bugsnag crash-SDK root check (`/system/bin/su`, SuperSU, daemonsu via `performNativeRootChecks`). **Telemetry-grade** anti-tamper — tags crash reports, not a hard patch gate. |
| `libc++_shared.so` | 1.5 MB | `d6c9d2de…` | NDK C++ runtime. Support only. |

Anti-tamper read: no hard integrity wall found *yet* in the small libs — Bugsnag is telemetry.
Real integrity/DRM checks (and any Netflix self-tamper detection) are expected inside
`libnetflix.so` / Widevine; assess once the monolith is in hand.

### 3b. `libnetflix.so` decode — the seam is a JS VM, not a native buffer (measured 2026-07-22)

Reassembled from 17 uploaded parts. **88,465,312 bytes**, SHA-256
`b3873f003d6223ad4b9215bbf42d45053b3133cb184a519572ffde544109da2c` (size-exact, valid ELF 32-bit
ARM; user Get-FileHash confirmation pending). This finding **reshapes the whole port.**

**What the monolith actually is.** SONAME `libandroid_netflix.so` (so on-disk `libnetflix.so` ==
the nrd-manifest `libandroid_netflix.so` — one library, mystery resolved). NEEDED lists **no
`libssl`/`libcrypto`** → TLS is **statically-linked OpenSSL 3.2.1** (strings: "OpenSSL 3.2.1 30 Jan
2024", `SSL_read`/`SSL_write`). Inside it: a **Hermes** JS engine (`HERMESATOM`, `HbC`/bytecode),
the **Gibbon** renderer (`DGIBBON_*`), and **nrdp** platform (`DNRDP_*`). MSL is implemented **in
JavaScript** running on Hermes (strings are JS: `getMslEncoderFactory()`, `MslEncoderFormat`,
`sendLogBlob({…type:"milo_ssl_write_error"})`).

**The decisive structural fact.** The plaintext manifest is **not** a native buffer handed to a
native parser (the model the toolkit is built for). The flow is:
`native OpenSSL SSL_read (MSL ciphertext) → MSL decrypt in Hermes JS → manifest is a JS string/object
in the Hermes heap → parsed by JS`. So:
- Hooking a native `SSL_read`/`memcpy`/`inflate` seam yields **MSL ciphertext**, never the schedule.
- The plaintext only exists **inside the Hermes VM**. A native inline hook can't cleanly reach a JS
  heap string. **The toolkit's native-`.so`-hook mechanism largely does not port to Netflix.**

**Where the ad logic actually lives — `milo` (the downloadable player JS).** The player runtime is
**milo**, and it is **not baked into the `.so`** — it's downloaded and disk-cached:
- Delivery URL: `https://occ.a.nflxso.net/genc/nrdp/milo/1.0.3806-57ec2bae/milo.prod.js`
  (also `milo.debug.js`, `milo.prod.assertions.js`, `milo-update.js`). Plain JS.
- Cached on-device via `MiloDiskCache` (`nrdp.storage.MILO_CURRENT` / `MILO_CURRENT_INDEX`);
  integrity via `milo_update_hash` (a `milo_ignore_hash_errors` flag also exists); version-pinned
  ("Mismatched milo version. Expected …").
- The `.so` itself contains **no** video ad-break schema — every "advert*" string in it is
  **MDX/UPnP casting** (`MdxConfigure`, `z.upnp.startAdvertising`, `advertisingPort/TTL`) or
  Bluetooth LE, and `"advertisingstatechanged"` is a UPnP discovery event. The ad-supported-tier
  ad-break parsing lives in the **milo bundle**, not here.

**Consequence — the port strategy forks, and the native path looks wrong for Netflix:**
1. **milo JS layer (promising).** `milo.prod.js` is a small, plain-JS, public device asset that
   contains the manifest ad-break parsing + ad scheduling. **Obtaining it likely reveals the
   ad-break schema by static analysis — solving the blocker with no live MSL capture.** A strip
   could plausibly be a *milo-JS* modification (disk-cache replacement), gated by `milo_update_hash`
   — a different mechanism than this toolkit's native hook.
2. **Native/Hermes hook (hard).** Reaching the plaintext means hooking inside Hermes — far harder
   than the toolkit's memcpy/inflate seam and not what the scaffold provides.

Anti-tamper: statically-linked OpenSSL + milo hash-verification + version pinning; a Hermes/JS-bundle
approach must contend with `milo_update_hash` (though `milo_ignore_hash_errors` is intriguing).

### 3c. milo config-override surface — a plausible strip delivery path (measured 2026-07-22)

Chasing the integrity question in `libnetflix.so` surfaced a **config-override mechanism** that could
deliver a JS-layer strip *without* cracking MSL or the milo hash. All of these are plain
`nrdp.js_options` keys (same surface that sets dozens of runtime options; populated from nrdp
bootargs/config at startup — Java side: `getNflxCmdLineOptions()`, `SetConfigFromNrdp(String)`,
`nativePropertyGet`, storage keys `MILO_STAGING_URL`/`MILO_STABLE_URL`):

- `milo_update_url` — **override where milo is fetched** (default `https://occ.a.nflxso.net/genc/nrdp/milo/1.0.3806-…/milo.prod.js`)
- `milo_update_hash` — the expected integrity hash (default `634c8ca88f3c…`)
- `milo_ignore_hash_errors` — **ignore hash mismatch** (JS getter `get milo_ignore_hash_errors(){return this.#re}`)
- `milo_ignore_ssl_mismatch`, `milo_http_proxy` — ignore TLS mismatch / route the fetch through a proxy

**Candidate strip path (unproven):** serve a modified (ad-stripped) milo, point `milo_update_url` at
it, set `milo_ignore_hash_errors=true` → the app runs our milo. This sidesteps MSL (we're replacing
the *player*, not decrypting the manifest) and the integrity hash.

**The load-bearing open question:** can `nrdp.js_options.milo_*` be **set in a production build**?
This is `NF_ANDROID_PROD_BUILD=true` / `BUILD_PRODUCTION=ON` / `NRDP_HAS_QA=AUTO`, so this QA/bring-up
surface may be locked down in prod. Whether js_options is populated from a patch-writable source
(config file, intent extra, device property, or a bytecode injection at the `SetConfigFromNrdp` seam)
is **testable on device** and is now the key feasibility gate for a milo-override strip.

### 3d. CORRECTION — milo is networking, not the player; the ad layer is signature-locked (measured 2026-07-23)

Obtained and analyzed the real `milo.debug.js` (5.7 MB, unminified, `(c) 2024 Netflix`, milo
`1.0.3806-57ec2bae`) — proving the direct-download method works. But it **corrects the milo pivot**:

- **milo is Netflix's JS networking layer, NOT the player/ad layer.** Module census:
  `milo.request.http`, `milo.websocket.*`, `milo.WebSocketFramer.*`, `milo.WS.parseHeader`,
  `milo.diskCache.DiskCache`, `milo.requestManager.setShim`. A rigorous whole-file ad-vocabulary
  census (adBreak/interstitial/midroll/preroll/cuePoint/SSAI/…) came back **empty** — the earlier
  "ads×193" was a substring false-positive (loads/reads/payloads). **No ad logic in milo.**
- **The player/UI app is a separate bundle loaded via `appboot`** (Gibbon `gibbon.load({url: appboot_filter_url})`),
  from `appboot.netflix.com` — and it is **cryptographically signature-verified**, not hash-checked:
  `appboot_key` (`KeyFormat.SPKI`), `WebCryptoAlgorithm.RSASSA_*`, `appboot_fail_nas_verify`
  (ECDSA×101 / RSASSA×25 / SPKI×33 in the binary). The verifying **public key is baked into
  `libnetflix.so`.**

**Consequence — the milo-override strip idea does NOT reach the ad layer.** The modifiable layer
(milo, hash-bypassable) is transport-only and sees the manifest as **MSL ciphertext**. The layer that
holds the plaintext ad-break logic (the appboot UI app) is **RSA/ECDSA-signed against a pubkey in the
native binary** — to modify it you'd have to defeat signature verification by patching that pubkey in
`libnetflix.so` (deep native patching + re-hosting the app + MSL context), or forge Netflix's
signature (infeasible). That is strictly harder than milo's hash and puts us back in native-binary
territory — exactly what the toolkit's "no deep native work" premise tries to avoid.

**Net protection stack Netflix puts around its ads:** TLS (Cronet, static OpenSSL) → MSL (JS, in the
runtime) → appboot **signature** on the ad-bearing UI app. Three independent layers; the strippable
one (milo hash) guards only the plumbing.

**Also settled — the dex is a thin shell.** A scan of the readable `com.netflix.*` namespace found
**no** milo loader, JS-bridge, or integrity class (only the Bugsnag telemetry reporter). milo fetch,
the Hermes bridge, MSL, and integrity all live in native `libnetflix.so`. → **No further dex mining
is warranted**; the two live fronts are (a) obtaining `milo.prod.js` to learn *what* to strip, and
(b) testing whether the milo-override config path is reachable in prod (*how* to ship it).

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

APK + all native libs decoded (§3/§3a/§3b). The decode **redirected the target**: the ad logic
is in the downloadable **milo** JS bundle, not the `.so`. Priority order now:
1. **The `milo` bundle** — highest value, small, plain JS. Two easy ways to get it:
   (a) pull the on-device cache: `com.netflix.ninja`'s `MiloDiskCache` under the app data dir
   (`/data/data/com.netflix.ninja/…`, needs root/adb-run-as), or
   (b) fetch the URL from a normal machine/browser (this sandbox's proxy blocks `occ.a.nflxso.net`,
   but your Windows box isn't restricted):
   `https://occ.a.nflxso.net/genc/nrdp/milo/1.0.3806-57ec2bae/milo.prod.js`
   Upload `milo.prod.js` (or `.debug.js`) here → I static-analyze it for the ad-break schema and the
   strip point. **This is the artifact that unblocks the whole thing.**
2. **A post-MSL manifest from an ad-tier account** (still the ground truth) — but milo may hand us
   the schema first, cheaper.
3. Device Frida only if we go the (hard) Hermes-hook route — likely deprioritized in favor of milo.

Status: **category-confirmed; target redirected from `.so` → milo JS bundle; blocked on obtaining
milo (or an ad-tier manifest).**

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
| `Netflix_Ad_Interception_Expert_Analysis.txt` (LLM analysis of the gist) | Argues the gist captured the **marketing/attribution** layer, not in-stream ads; concludes ad-tier commercials are an SSAI "hard ceiling" — unreachable by intercepting anything in the gist. | **Mixed** — see §2c. Independently confirms Monet=marketing (good) and surfaces a promo-trailer win (BOB/JAW/CLCS Shakti flags). But its "impossible" is **web-network-scoped + pure-SSAI-assumed**; it never saw the app. Our decode shows ad-break logic in client-side **milo** JS, and the ad UI (countdown, no-seek) proves the client *does* hold break metadata — contradicting "the player doesn't know which segments are ads." |

### 2c. Reconciling the "SSAI hard ceiling" claim with our binary evidence

An LLM analysis of the gist concluded ad-tier commercials are unreachable ("server-side stitched…
the player does not know which segments are ads"). Correct that you can't block a separate ad
network call (there isn't one) — but overstated as a universal "impossible," for two reasons our
decode exposes:
- **Scope:** the analysis only had the gist (web traffic). It never saw `libnetflix.so`/Hermes/**milo**.
  Its ceiling is a *web-network-interception* ceiling, not a statement about the client JS layer.
- **The client demonstrably holds ad-break state:** the ad-tier UI renders a countdown, "ad X of Y",
  and disables seek during breaks — impossible without in-process break timing/metadata. So milo *does*
  know where the ads are; "the player doesn't know" is false for Netflix.

Open question it doesn't resolve (and neither do we, yet): even if segments are server-stitched,
does neutralizing milo's ad-break handling **skip/blank** the break, or merely **desync the UI while
the ad still plays**? That is the METHODOLOGY §1 de-risk — answerable only by reading `milo.prod.js`
and testing, not by assuming. Verdict: the analysis is a good sanity check that kills the naïve
"block the ad call" idea, but its "hard ceiling" does not account for the client-side milo surface.

Strategic note: both sidestep the stream rather than strip it. The "skip/accelerate the
player" idea has a native analogue (hook nrdp playback control, not the media bytes) but
that's a different hook target than this toolkit's and is unproven-reachable — flag it as an
alt path, not a lead.

## 3e. EMPIRICAL CAPTURE — pure same-host SSAI, nothing to block (measured 2026-07-23)

On-device PCAPdroid capture from the Onn (ad-tier account), a real session that **included a
15-second pre-roll**. Analyzed with `testing/scripts/analyze_pcap.py` (DNS + TLS-SNI host
inventory; no decryption needed). Behavioral note: **~7 movies played with no pre-roll, then one
did** → the ad decision is **stateful/frequency-capped**, not per-title (matches the toolkit's own
`heap-oracle.js` frequency-cap guard).

**Result — 18 unique hosts in the whole session; exactly ONE is non-Netflix:**
- Content **and the ad** both served from the same Open Connect byte-range hosts:
  `ipv4-c0NN-dpa001-…oca.nflxvideo.net`, `ipv4-c7NN-ord001-ix…oca.nflxvideo.net`.
- Netflix control/telemetry: `api-global.netflix.com`, `nrdp26.{prod.ftl,ws.prod.cloud,logs,push.prod}.netflix.com`,
  `logs.netflix.com`, `occ-0-…nflxso.net`, `preapp.prod.partner.netflix.net`, `…darnuid.netflix.com`.
- **Only non-Netflix host: `sessions.bugsnag.com`** — crash reporting, not ads.
- **Zero** third-party ad hosts / beacons (adnxs/doubleclick/xandr/nielsen/freewheel/… = 0 hits).

**Conclusion — the ad footprint is indistinguishable from content at the network layer:**
- The pre-roll came from the **same `oca.nflxvideo.net` OCA infrastructure** as the movie, over the
  same TLS sessions — textbook **SSAI**. There is **no separate ad host, no ad-decision call to a
  distinct domain, and no client-fired third-party beacon.** Ad telemetry rides inside MSL to
  Netflix's own `logs.netflix.com` (Ichnaea).
- **DNS / AdGuard is useless here:** the only blockable non-Netflix host is Bugsnag (crash
  telemetry); blocking `oca.nflxvideo.net` would kill *all* video, ads and content alike.
- **No network-layer interception point** exists that isn't either same-host content or MSL ciphertext.

Caveat: one session, one pre-roll (not a mid-roll break). But the result is unambiguous and converges
with every static finding (SSAI + MSL + appboot signature), so it's very likely representative.

## 8. Verdict

- ✅ Toolkit is real and its transforms pass here.
- ✅ Netflix is the *right category* of target (native media plane, shared TLS, no DNS/bytecode reach) —
  now **confirmed against base.apk bytes**: no ExoPlayer/OkHttp/Java ad surface exists.
- ✅ **Target library identified from bytes:** `libandroid_netflix.so` (nrdp runtime v26.1,
  armeabi-v7a); Application subclass and `extractNativeLibs` resolved for the Morphe patches.
- ⚠️ Netflix is **harder than Prime Video, and structurally different**: MSL is decrypted **in an
  embedded Hermes JS engine**, so the plaintext manifest is a JS-heap string, not a native buffer.
  The scaffold's native memcpy/inflate/SSL_read seam yields only MSL ciphertext → **the toolkit's
  native-`.so`-hook mechanism largely does not port to Netflix.**
- 🔀 **Target redirected by the decode:** the ad-break parsing lives in the **downloadable `milo`
  JS bundle** (`occ.a.nflxso.net/genc/nrdp/milo/1.0.3806-…/milo.prod.js`, disk-cached), not in
  `libnetflix.so`. The realistic strip surface is **milo JS**, gated by `milo_update_hash`.
- ⛔ **milo obtained — and it corrected the plan (see §3d):** milo is Netflix's JS *networking*
  layer (hash-checked, modifiable) with **no ad logic**. The ad-break logic lives in the separate
  **appboot UI app**, which is **RSA/ECDSA signature-verified against a pubkey baked into
  `libnetflix.so`** and served from `appboot.netflix.com` with MSL/device context.
- 🧱 **Honest feasibility:** Netflix wraps its ads in three independent layers — TLS → MSL → appboot
  signature — and the only easily-modifiable layer (milo) is transport that sees only ciphertext.
- 📡 **Empirically confirmed (§3e):** an on-device capture of a real pre-roll shows **pure same-host
  SSAI** — the ad is served from the same `oca.nflxvideo.net` OCA hosts as content, with **zero
  third-party ad hosts/beacons.** So there is **no DNS/AdGuard block, and no network interception
  point** that isn't same-host content or MSL ciphertext. Every avenue this toolkit and its
  companion tools can reach is closed.
- ⛔ **Final verdict — Netflix is not strippable with our toolset.** The only conceivable paths left
  are (a) defeat the appboot RSA signature via a native pubkey patch in `libnetflix.so` + re-host the
  UI app, or (b) patch the native ad-scheduling logic inside `libnetflix.so` directly — both deep,
  version-fragile native reverse-engineering far beyond the bytecode/DNS model that makes the repo's
  other apps tractable. **Recommendation: research-parked / closed.** Documented end-to-end here so
  the conclusion is resumable if Netflix ever moves ads to a separable plane.
