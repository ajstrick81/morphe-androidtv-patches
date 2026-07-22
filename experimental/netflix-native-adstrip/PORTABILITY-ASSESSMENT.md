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

## 3. Mechanical port worksheet (placeholder fills, PORTING-CHECKLIST)

Ready to drop into the scaffold templates *once §1/§2 are answered*. `[VERIFY]` = read it off
the decoded APK, don't trust this table.

| Placeholder | Value |
|---|---|
| `<app>` | `netflix` |
| `<App>` / `<APP>` | `NF` / `NFNativeHook` |
| `<HOOK>` | `nfhook` → `libnfhook.so` |
| `<app.package.name>` | `com.netflix.ninja` |
| `<app/Application/subclass>` | [VERIFY] read `<application android:name=…>` from decoded `AndroidManifest.xml` |
| `<compat version>` | `13.0.0-25009` (single-arch — [VERIFY] which ABI: armeabi-v7a vs arm64-v8a) |
| `<TARGET_SONAME>` | [VERIFY] the post-MSL media lib in `lib/<abi>/` — **not** system `libssl` |

Which reference transform to start from (§2 of the checklist) is **undecided** until we know
the schedule format at the seam: a JSON schedule → `prs_filter`/`prs_blank` family; an
HLS/DASH manifest with SSAI markers → `manifest_filter`. [VERIFY] by capturing one break.

## 4. What I need from you to continue past paper

The empirical steps can't run in this cloud container. To move forward, upload here:
1. **The `.apkm`** (the one the request pointed at). Lets me decode `AndroidManifest.xml`
   (Application subclass), enumerate `lib/<abi>/` sonames + ABI, and grep smali to rule out a
   bytecode/DNS path — steps 3 and the §0 rule-outs, all doable off-device.
2. **A plaintext capture of one ad break** (movie *and* series — they differ), from a
   PC MITM with a device CA. This is the METHODOLOGY §1 de-risk: it tells us whether the
   schedule is reachable/strippable at all, before any native work.
3. Eventually, device-side Frida access (rooted or gadget) to run `find-copy-seam.js` and
   locate the post-MSL seam for §2/§3.

Without (1) and (2), the honest status is: **plausible on category, blocked on MSL, unproven.**

## 4b. Prior art surveyed (and why it doesn't move us)

Community repos evaluated for Netflix ad-structure insight. Pattern so far: they
operate at the wrong layer (server-side, or web-DOM) for a native-TV media-plane strip.

| Repo | What it is | Useful for us? |
|---|---|---|
| `cruizviquez/Micro-Netflix-Ads-Ctr` | Flask + scikit-learn CTR **simulation** on synthetic data; Netflix-styled UI. No real internals. | No — models the ad *server's* decisioning, not the client media plane. |
| `Dreamlinerm/Netflix-Prime-Auto-Skip` | Browser **web** extension; detects ads by DOM scraping (`span[class*="mmvz9h"]`, `data-uia="pause-ad-*"`) and skips via `video.playbackRate=8` + mute. No manifest/API. | No — wrong platform (web DOM, not native nrdp) and wrong strategy (drives the player, doesn't strip the stream). Selectors don't exist in `com.netflix.ninja`. |
| `sshh12/…dda3a89514…` (gist) | Web-session network teardown (177 reqs): names MSL, licensed-manifest endpoint, Open Connect byte-range streaming, ad system "Monet". | **Partially** — see §2b. Corroborates the MSL wall + manifest shape from real captures and adds the "Monet" lead; still no ad-break schema. Web endpoints, not native. |

Strategic note: both sidestep the stream rather than strip it. The "skip/accelerate the
player" idea has a native analogue (hook nrdp playback control, not the media bytes) but
that's a different hook target than this toolkit's and is unproven-reachable — flag it as an
alt path, not a lead.

## 5. Verdict

- ✅ Toolkit is real and its transforms pass here.
- ✅ Netflix is the *right category* of target (native media plane, shared TLS, no DNS/bytecode reach).
- ⚠️ Netflix is **harder than Prime Video**: MSL hides the plaintext behind Netflix's own
  crypto layer, so the scaffold's fast TLS/inflate seam won't work; the seam is deeper and
  the offset recovery has no OpenSSL/zlib string anchors.
- ⛔ Cannot proceed empirically without the APK + a capture (neither reachable from this
  environment).
