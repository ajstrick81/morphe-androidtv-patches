# Netflix household — EXPERIMENTAL runtime CLCS seam (Nikflix-derived)

**Status:** probe RAN on `.211` (2026-08-29, clone v13.0.1). Anchor identified; neuter slot still empty.
Probe now decoupled behind `CLCS_PROBE_ENABLED=false` (dev-only) — see "Probe run result" below.

## Probe run result (2026-08-29, `.211` `com.netflix.ninja.clone` 13.0.1/25028)

9 hits across 4 markers. Address range tells us what's anchorable:
- `clcsInterstitialProfileGate` @ **0x350x** — raw **network/telemetry buffers** (HTTP response bytes,
  request context, `X-Request-Id` headers). NOT source, NOT anchorable (transient MSL/log data).
- `clcsInterstitialPlaybackAndPostPlayback` @ **0x35e4x** — **telemetry** (`makeDGSRequest`,
  `"eventType":"graphql"` debug events). NOT anchorable.
- `pushClcsInterstitialGate` @ **0x50c / 0x53** — **JS SOURCE** (readable: `function d(e,t){var r,a,o=
  null==e||null==(r=e.componentT…`, router `case`s). ✅ **This is the anchorable consumer**, same heap
  region where MHU renders were patched (0x50c5xxxx).

CONFIRMS prior recon: the op names go out as opaque MSL (only appear in net/telemetry), while the
**router push fn is JS source**. Next-step target = discriminate the household (ProfileGate→MHU)
branch inside `pushClcsInterstitialGate` / its `d(e,t)` builder and neuter it length-preserving —
WITHOUT breaking the generic profile-select path (memory: ProfileGate is generic).

⚠️ AD-KILL INTERACTION (important): running the probe in a household build starved `fastMASTER`
(the getAdMetadata pre-roll kill) — a pre-roll played during the test. Root cause = single JS thread,
serialized heavy scans (fastHH 16 MHU scans/100ms + probe 4 markers). Hence `CLCS_PROBE_ENABLED` is
now default OFF. Any future neuter must be a light, write-once anchor (like patchHH), NOT a standing
scan loop, or it will regress ad-kill.

---

**Original plan below.**

**Date:** 2026-08-29
**Gate:** `HH_ENABLED` (the "Suppress Household Prompt" opt-in). Default OFF — nothing runs otherwise.
**Files:** `patches/src/main/resources/netflix/native/killads.js` (CLCS section, just above `var tries=0;`)

---

## Why this exists

Our shipped household path is the **appboot-heap byte-edit race** (`HH_ANCHORS` / `fastHH`
flipping `setAccountSharingFlags` + `neuterMhuRenders` nulling MHU screens). It must patch the
boot source before it materialises (~30s) — a fragile one-shot race (the "timing wall",
memory: `netflix-household-prompt-timing-wall`).

**Nikflix** (github.com/YidirK/Nikflix, GPL-3.0; web extension) shows the enforcement is actually a
**per-`/watch` RUNTIME GraphQL call**, not a boot flag:

- op: `CLCSInterstitialPlaybackAndPostPlayback`
- endpoint: `web.prod.cloud.netflix.com/graphql`
- fix (web): override `fetch`/`XHR`, return `{"data":{}}` when that op is seen → prompt never fires.

A runtime seam is **structurally better** than our boot race: it fires every playback (repeatable,
no 30s race to lose) and anchors on a stable op name that survives class-rename churn.

## Why we can't just copy them

ATV Netflix networking is **native (Gibbon), not WebView/OkHttp** — no `fetch` to override,
`shouldInterceptRequest` won't see it, and a DNS/origin block on `web.prod.cloud.netflix.com` is too
coarse (that host serves essential GraphQL; DNS can't filter by operation). So we target the **JS
that consumes the CLCS response** with the same memory-scan+byte-edit trick `fastMASTER` uses on
`getAdMetadata`.

## KNOWN ATV lineage (from prior recon — read before probing)

Memory `netflix-household-prompt-timing-wall` (2026-08-13 heap-dump grep) already established:

- The op name **is** in the ATV JS heap — but as **camelCase** `clcsInterstitialPlaybackAndPostPlayback(Query)`,
  inside a 115-member `CLCS*` family. `libnetflix.so` had **ZERO** CLCS hits → the whole layer is
  **JS-only**; a native transport-intercept is NOT viable (request leaves as opaque MSL bytes).
- **The household gate is a DIFFERENT op than Nikflix's web target.** Web uses
  `CLCSInterstitialPlaybackAndPostPlayback` (the /watch interstitial). On ATV the ownership prompt is
  `clcsInterstitialProfileGate` (`QueryType.ProfileGate`), routed via
  `pushClcsInterstitialGate`, rendering **MHU** screens (`MhuManageHousehold`, `MhuSetHousehold*`).
- ⚠️ `clcsInterstitialGate`/`ProfileGate` is **generic** — it also drives legit "who's watching"
  profile selection. A blunt neuter breaks profile-select. The safe discriminator is the **MHU_***
  render table (household-only strings), which is exactly what `neuterMhuRenders` already targets.

So the probe scans the whole family (`CLCS_MARKERS`), and the anchor we want is the **ProfileGate/MHU
consumer**, not the Playback op. This runtime seam is the same enforcement `neuterMhuRenders` hits at
the render layer — the goal here is to neuter it one level earlier, at the **decision/response** the
gate acts on, so the app proceeds without ever queuing the MHU screen (beating the ~30-44s mount race).

---

## What's wired now

1. **`clcsProbe()` — read-only.** Tight 100ms scan (~45s) for `CLCSInterstitialPlaybackAndPostPlayback`
   in `rw-` heap. Logs `CLCS-PROBE hit@<addr> next80=<...>` for up to 8 hits, then stops
   (`clcsProbeDone`). This is how we discover the real ATV consumer string.
2. **`patchCLCS()` + `CLCS_ANCHORS[]` (empty).** Neuter slot, same idiom as `patchHH`. No-ops until
   an anchor is added. Intentionally **not** in the apply-DONE gate — best-effort, must not hang the loop.

Both armed alongside `fastHH`, only under `HH_ENABLED`.

## On-device test (.211)

1. Build .mpp → merge .apkm → Morphe patch with **Suppress Household Prompt enabled** → install.
2. Trigger a `/watch` that normally shows the household prompt.
3. Capture:
   ```
   adb -s 192.168.12.211:5555 logcat -s KILL:* | grep CLCS
   ```

### Reading the result

- **Hits logged** → copy the `next80=` context. Find the consuming expression (a guard/ternary/assign
  that acts on the CLCS result — e.g. `if(<x>.interstitial)`, `...Available&&<x>`, a `render:` gate).
  Promote it to an anchor (see below), rebuild, retest. Success = prompt gone with **no** appboot race.
- **Zero hits** → most likely just means the household code hasn't loaded yet (prior recon: it
  materialises **~+30-44s** into appboot, not early). Let the probe keep scanning (~60s). Only if it's
  still empty after the household screen is known-resident would native-net be implicated — and prior
  recon already **ruled native out** (libnetflix.so had 0 CLCS hits), so re-confirm timing before
  concluding anything. Do NOT pivot to a native transport hook on a single empty pass.

## Promoting a probe hit to a neuter anchor

Add to `CLCS_ANCHORS` in killads.js. Each entry is a length-preserving single-byte flip:

```js
{ a:'<unique substring from next80>', off:<index of byte to flip within a>,
  chr:'<expected char at off>', to:'<replacement char>', lbl:'clcs:<what-it-does>' }
```

Rules (match `patchHH`):
- `a` must be **unique** in the heap and stable across the module.
- The edit must be **length-preserving** (flip one char, e.g. a truthy guard `f`/`1` → `0`, or a
  ternary discriminant) so the JS module still parses. Never insert/delete bytes.
- Prefer neutering the **decision** (make the enforcement branch dead) over nulling a render — a dead
  decision means the app proceeds; a null render just blanks the screen (that's what MHU already does
  as LAYER 2 fallback).
- Verify on-device via logcat `PATCH CLCS:` line + prompt actually gone, not just "anchor flipped".

## Honest scope

Client-side **prompt suppression** only. Server-side / public-IP household detection is untouched —
this cannot make a genuinely out-of-household session appear in-household to Netflix's backend.

## Credit

Seam identity (op name + empty-`{data:{}}` sufficiency) from Nikflix — author **YidirK**, API-block
contributor **Buckibarnes17**. Our implementation is an independent native-app memory edit, not a
fetch/XHR override. See repo `NOTICE` and killads.js:175.
