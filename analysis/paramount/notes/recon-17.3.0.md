# Paramount+ Android TV 17.3.0 — static recon vs. 16.19.0

**APK:** `com.cbs.ott` 17.3.0, versionCode **520000982**, APKM bundle
(arm64-v8a + armeabi-v7a, 320dpi), APKMirror post date 2026-11-04.
**Reviewed:** 2026-11-08. **Method:** `apktool d` on `base.apk` (4 DEX →
`smali`, `smali_classes2`, `smali_classes3`), fingerprint anchors verified
against the actual bytecode (not jadx names).

Last blessed target was **16.19.0 / 520000827**. This is the first 17.x
look, so it is a major-version bump, not a point release.

## Verdict

All three existing patch fingerprints still resolve on 17.3.0, and **no
fingerprint changes are needed** — a later smali diff against 16.21.0 showed
the seams are not merely still-matching but *unchanged*.

**However, 17.3.0 is NOT supported: VOD does not play on a patched build.**
The title opens `VideoPlayerActivity` and sits on an infinite spinner, with no
exception of any kind. Live TV is unaffected. The cause is not identified and
is not a fingerprint failure. See "On-device results" and "Corrections" below;
read those before trusting anything in the original static sections, which
were written before any of it was tested.

## Build-level diff

| | 16.19.0 | 17.3.0 |
|---|---|---|
| versionCode | 520000827 | 520000982 |
| minSdk | 24 | 24 (unchanged) |
| targetSdk | — | **36** |
| DEX files | — | 4 (`classes.dex` … `classes4.dex`) |
| Packer | none | none |
| Obfuscation | R8, name-renamed app code; AVIA + okhttp3 **not** renamed | same |

`okhttp3` is still unrenamed (`AviaNetworkInterceptor` literally
`.implements Lokhttp3/Interceptor;`), so Patch 3's direct `Lokhttp3/*`
type references remain valid.

## Patch 1 — VOD ad gate (`ShouldPlayAdFingerprint`)

✅ **Matches.**

```
.method private shouldPlayAd(Lcom/paramount/android/avia/player/dao/ad/AviaAdPod;)Z
  — com/paramount/android/avia/player/resource/dai/AviaDAIResourceProvider.smali:4288
  .locals 1
```

- Still `private`, still returns `Z`, still on a class whose leaf name is
  `AviaDAIResourceProvider;`. Every sibling in the package is an inner class
  (`$1`…`$5`, `$Listener`, `$Listener$2/3`), so the `endsWith` anchor stays
  unambiguous.
- Body unchanged in shape: `resourceConfiguration.getAdControl()` → null ⇒
  `return true`, else `AviaAdControl.playAd(pod)`.
- Injecting `const/4 v0, 0x0 / return v0` is still register-safe (`.locals 1`).

**Structural change (harmless):** on 16.17/16.19 the position monitor
`AviaDAIResourceProvider$3` called `shouldPlayAd` directly. On 17.3.0 R8 has
routed it through a synthetic bridge in the outer class —
`public static bridge synthetic v0(AviaDAIResourceProvider, AviaAdPod)Z` at
line 4383 → 4387 — plus two internal call sites (lines 6341, 6578). All three
still funnel into the same private method, so forcing `false` still gates
every pod. The fingerprint anchors the callee, not the caller, so this
drift cost nothing — the hardening note in `Fingerprints.kt` paid off here.

## Patch 2 — Pause ads (`PauseAdOverlayFingerprint`)

✅ **Matches.**

- Class present: `com/paramount/android/pplus/widgets/player/tv/view/CbsPauseWithAdsOverlay.smali`.
- Anchor literal `" not updating overlay."` present (line 790), still unique
  in-class; `"renderState: "` also still there (line 776).
- Enclosing method is now
  `public static final G(Lcom/.../CbsPauseWithAdsOverlay;Lr22;)V` (line 252)
  — **R8 has staticized the render method**, moving the receiver to `p0` and
  the state object to `p1`. It is still the render state machine (dispatches
  on `l22`/`m22`/`q22`/`n22` state subclasses, calls back into
  `CbsPauseWithAdsOverlay.E(J)V`).
- The fingerprint matches on `returnType = "V"` + the string + the
  `endsWith("CbsPauseWithAdsOverlay;")` class anchor, none of which care
  about static-vs-instance, so it still resolves. `return-void` at index 0
  still no-ops the whole state machine.

Worth noting for the future: because the method is now static, a fingerprint
that had anchored on an instance signature would have broken. The
string-anchor choice is what saved this one.

## Patch 3 — Live ad slate (`AviaNetworkInterceptorFingerprint`)

✅ **Matches.**

```
.method public intercept(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;
  — com/paramount/android/avia/player/player/core/network/AviaNetworkInterceptor.smali:1442
  .locals 16
```

- Name `intercept`, return `Lokhttp3/Response;`, leaf class
  `AviaNetworkInterceptor;` — all three anchors hold. Only one sibling
  (`$1`), so no ambiguity.
- `.locals 16` means `p1` is still a high param register, so the injected
  `move-object/from16 v0, p1` prologue is still **required** and still
  correct; a bare `invoke-interface {p1}` would still be illegal.
- Original body still opens into a `:try_start_0` block, same as 16.17/16.19,
  so the `addInstructions(0)` + `:morphe_continue nop` fall-through idiom
  behaves as before.
- Live pod-serving still looks alive: `AviaDAIResourceProvider` retains the
  `isFastStartPodServing:Z` field and `liveAdPosition`/`lastLiveAdPositionTime`
  bookkeeping, i.e. the Google DAI pod-serving path the slate rewrite depends
  on is still the live mechanism.

The slate-URL rewrite itself (`/linear/pods/v1/` → `/<pod>/slate/0/<hash>/`,
`?d=` learning via `morphe.slate.d`) is a pure runtime URL transform with no
bytecode anchor, so static analysis can't confirm it — the DAI endpoint's
behavior has to be re-checked on-device during a real live break.

## Gap: a second, un-gated `shouldPlayAd` on the IMA provider

The AVIA provider lineup is unchanged (`dai`, `ima`, `clid3`, `uri`,
`AviaSkipAdResourceProvider`), but note:

```
.method private shouldPlayAd(Lcom/paramount/android/avia/player/dao/ad/AviaAdPod;)Z
  — com/paramount/android/avia/player/resource/ima/AviaIMAResourceProvider.smali:3310
  (called at :644)
```

`AviaIMAResourceProvider` has its own `shouldPlayAd` with an identical
signature. Patch 1 deliberately anchors on `/AviaDAIResourceProvider;`, so
**the IMA path is not gated**. If any 17.x VOD playback is routed through
client-side IMA rather than DAI, pre/mid-rolls would still play there.

This is not hypothetical: the Pluto TV v17 work hit exactly this and ships
*both* gates (`AviaDaiShouldPlayAdFingerprint` +
`AviaImaShouldPlayAdFingerprint` — see `docs/SEAM_MAP.md:423-424`). The same
two-gate shape is the obvious candidate for Paramount 17.x.

**Caveat:** on 16.12.0 an IMA-side intervention provoked an IMA retry storm /
ANR (recorded in `ParamountPatch.kt` history). That was the *empty-request*
approach, not a `shouldPlayAd` gate, and Pluto v17 runs the IMA gate without
trouble — but it means the IMA gate should be added and watched on logcat
rather than assumed safe.

### Gate added (2026-11-08)

`ImaShouldPlayAdFingerprint` + "Patch 1b" now ship in `Fingerprints.kt` /
`ParamountPatch.kt`, applied via `methodOrNull` so a build with no reachable
IMA provider skips the hook instead of failing the whole patch — same shape as
Pluto's `AviaImaShouldPlayAdFingerprint`.

Why this seam is safe to force false: the real caller is the synthetic bridge
`W(AviaIMAResourceProvider, AviaAdPod)Z` (line 640), invoked from
`AviaIMAResourceProvider$VideoAdPlayerAdapter` (:1118) and `$1` (:1154). In the
adapter, a `false` result falls through to IMA's own teardown — it clears the
current pod (`F`), ad (`D`), `AdPodInfo` (`G`) and `AdMediaInfo` (`E`), then
calls `resumeContent()` (`U`) — i.e. the provider's native skip-the-pod path,
not an abort. The method is `.locals 1`, so the `const/4 v0, 0x0 / return v0`
injection is register-safe.

**Still unverified:** not compiled and not run on-device. The 16.12-era retry
storm is the specific thing to watch for on logcat.

## On-device results (2026-11-08, Onn 4K Plus "coffey", 192.168.12.210)

Toolchain notes for whoever repeats this: there is no
`~/.gradle/gradle.properties` on this machine, so the documented GPR
credentials do not exist — `gh auth token` + `GITHUB_ACTOR=ajstrick81` works
instead. `adb` is at `E:\Android\Sdk\platform-tools\adb.exe`, not on PATH. The
Morphe CLI's auto-generated keystore at `E:\Tools\morphe-data\morphe.keystore`
is NOT the key the device's builds were signed with; pass
`--keystore testing/keystore/morphe.keystore` or the install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. 17.3.0 needs `-f` (not in targets).

Every build below: `INFO: Applied: Paramount+ Android TV`, installed with `-r`
over the device's existing 16.21.0 (so no data loss), launched clean via
LEANBACK, no `FATAL` / `VerifyError` / `NoClassDefFound` / `UnsatisfiedLink`.

| # | Build | Live TV | VOD |
|---|---|---|---|
| 1 | All 3 patches + the IMA gate (Patch 1b) | ✅ plays | ❌ infinite spinner |
| 2 | All 3 patches, IMA gate removed (= the 16.21 config) | not retested | ❌ infinite spinner |
| 3 | Patch 1 reduced to `nop`, Patches 2+3 live | not retested | ❌ infinite spinner |
| 4 | All 3 reduced to `nop` (behaviourally vanilla) | **NOT TESTED** | **NOT TESTED** |

Build 4 exists and is verified correct (`E:\Morphe\work\pplus-17.3.0-allnop.apk`,
`morphe.slate.d` absent from its dex) but was not installed — testing stopped
here deliberately, see "What to do next".

What the logs show: nothing. No exception, no `AdError`, no retry storm,
nothing in the app process at all. The app does load `imasdk` / `pcam.jar`
inside `VideoPlayerActivity`, so 17.3.0 VOD really does involve the IMA
provider. AVIA itself logs nothing at default verbosity, so logcat cannot
discriminate between these builds — which is exactly why bisecting by
reinstalling was the wrong tool.

## Seam diff vs 16.21.0 — the decisive evidence

16.21.0 (versionCode 520000892) is the build the device was running, patched
and signed with the stable key, before this work. Decompiled to
`E:\Morphe\decompiled\Paramount+\16.21.0` and compared:

| Patch site | 16.21.0 | 17.3.0 |
|---|---|---|
| `AviaDAIResourceProvider` | 7665 lines, `private shouldPlayAd(AviaAdPod)Z`, 3 in-class call sites | **identical** |
| `AviaNetworkInterceptor` | 3357 lines, `intercept(Chain)Lokhttp3/Response;`, `.locals 16` | **identical** |
| `AviaIMAResourceProvider` | 5400 lines, own `private shouldPlayAd`, 1 call site | 5404 lines, same shape |
| `CbsPauseWithAdsOverlay` | anchor string inside `static final H(..., Lay1;)V` | `static final G(..., Lr22;)V` |
| dex literals | `dai.google.com`=1, `isFastStartPodServing`=1, `PodServing`=29 | **identical counts** |

So the patch code is still compatible; there is nothing to port. This rules out
seam drift as the cause and points at the merged/re-signed build or at 17.3.0
itself.

## Corrections to the static sections above

- **"R8 drift" was wrong.** The synthetic bridge in front of `shouldPlayAd` and
  the staticized overlay render method were presented as 17.3.0 changes. Both
  were already present in 16.21.0. The error came from diffing against the
  16.17/16.19 prose notes in `Fingerprints.kt` instead of against 16.21
  bytecode. The fingerprints were never at risk from either.
- **The IMA gate was not shown to break VOD.** It was the first suspect and was
  reverted on that basis, but build 2 (gate removed) hung identically, so
  causation was never established. The gate is dropped as unproven, not wrong.
- **"Gap" below overstates its case.** The un-gated IMA `shouldPlayAd` is real,
  but nothing here shows that gating it is either necessary or safe.

## The Pluto TV v17 precedent (same AVIA build, same owner)

Pluto's `skipAdsV17Patch` is the only other patch here targeting AVIA 17.x, and
`FingerprintsV17.kt` states v17 Pluto moved to "Paramount's AVIA player (same
internals as Paramount+ / CBS)". Pluto v17 is 17.2.0, Paramount+ is 17.3.0.
Every seam Pluto hooks exists in Paramount+ 17.3.0:

| Pluto hook | Target | In Paramount+ 17.3.0 |
|---|---|---|
| 3 — DASH manifest strip | `AviaDashParser.parse(Uri, InputStream)` | yes, and **`.locals 23`**, the same count Pluto recorded for 17.2.0 |
| 4 — CSAI suppression | `CLID3Processor.processAdPodStartAsync(Map, J)` | yes, `private …(Ljava/util/Map;J)V` |
| 8 — ad metadata | `StitcherSession(id=` / `AdsData(adBreaks=` / `, adBreaksDurationInSec=` | all three anchors present |

Two consequences:

1. **17.x VOD ad suppression is a port of Pluto Hooks 3/4/8, not a tweak to our
   Patch 1.** Pluto established that on this player the `shouldPlayAd` gates only
   skip the DAI pre-roll pod; the mid-roll ad video is stitched into the
   multi-period DASH manifest and has to be removed at the parser, with the
   content timeline re-based and the stale ad-break metadata neutralised. That
   is almost certainly also true of Paramount+ 17.x.
2. **Pluto documents this exact spinner symptom, with a cause worth ruling out.**
   Its Hook 3 comment records that a non-range `invoke-static` on a parameter
   above v15 is *silently dropped* by the inline compiler, leaving an orphan
   `move-result-object` → VerifyError → the class is rejected → "player never
   starts (spinner -> menu, device-confirmed 17.2.0)". Our three injections use
   only v0..v5 and the one register-heavy method (`intercept`, `.locals 16`)
   already uses `move-object/from16`, so the trap should not apply — but this is
   checkable offline by disassembling the patched APK, and that is worth doing
   before any further install.

## Lead: 17.3.0 added decoder-fallback handling that 16.21.0 lacked

The AVIA package grew 826 -> 829 smali files. The three new classes are:

```
AbstractAviaDecoderFallbackStrategy.smali
AbstractAviaDecoderFallbackStrategy$FallbackAction.smali
AbstractAviaDecoderFallbackStrategy$RendererFailureContext.smali
```

New renderer-failure / decoder-fallback machinery is a plausible way to produce
a silent stall with no exception, and it is independent of our patches — which
fits the one fact that killed every patch-side hypothesis: all three patch
configurations hang identically. It also sits with the decoder noise in the
launch log (`CCodecConfig`, `invalid hdr type`, `no hdr10+ data`,
`ms12 system input underrun`) and with live playing while VOD does not, if VOD
uses a different codec or HDR profile on this panel.

This is a hypothesis with a mechanism, not a conclusion. It predicts that the
all-`nop` build hangs too, and that *unpatched* 17.3.0 hangs as well.

## Patched-dex verification (2026-11-08) — the injections are sound

Ruling out the Pluto failure mode on our own build. `pplus-17.3.0-patched-stablekey.apk`
(build 2: all three patches, no IMA gate — the configuration that hung)
disassembled to `E:\Morphe\decompiled\Paramount+\17.3.0-patched` and read at
each injection site.

| Patch | Site | Emitted bytecode |
|---|---|---|
| 1 | `AviaDAIResourceProvider.shouldPlayAd` :4288 | `.locals 1` intact, `const/4 v0, 0x0` + `return v0` at entry, original body below unreachable ✅ |
| 2 | `CbsPauseWithAdsOverlay.G` :252 | `.locals 8` intact, `return-void` at entry ✅ |
| 3 | `AviaNetworkInterceptor.intercept` :1442 | all instructions present, no orphan `move-result-object`, labels `:cond_0/:cond_1/:cond_2` resolved, `:cond_2` = the `morphe_continue` `nop` at :1565, fall-through into the original body (`move-object/from16 v1, p0`, the four `const-string`s, `:try_start_0`) intact ✅ |

Specifically on the Pluto Hook 3 trap: nothing was silently dropped. Every
register used by the Patch 3 prologue is v0..v5, well inside the v0..v15 limit
for non-range invokes, and `p1` is read with `move-object/from16`. `.locals 16`
is unchanged.

Also worth noting for the VOD question: on a non-live URL the injected prologue
executes three invokes (`request()`, `url()`, `toString()`), fails
`contains("/linear/pods/v1/")` and jumps to `:cond_2`. It is inert for VOD, and
it sits BEFORE `:try_start_0`, so it is not even inside the method's own
exception handler.

**Conclusion: the patch is not malformed on 17.3.0.** Taken with build 3
(Patch 1 reduced to `nop`) hanging identically, the patch-side explanation for
the VOD hang is now weak. The decoder-fallback change and the merged/re-signed
build are the live candidates.

## UNTESTED PREMISE — check this before anything else

Nobody has confirmed that **VOD played on the patched 16.21.0 build**. The Onn
had it installed and stable-key-signed since 2026-10-31 and it was treated here
as a known-good baseline, but only live TV has ever been observed working on a
patched Paramount+ build in this session. If VOD was already broken on patched
16.21.0, then nothing regressed in 17.3.0 and the whole "what did 17.3 break"
framing is wrong — the question becomes "has patched Paramount+ VOD ever worked
on this device, and if so on which build". Answer this first; it is free and it
can invalidate several of the hypotheses above.

## What to do next (one test at a time, each with a stated prediction)

1. **Install build 4 (all-`nop`) and try VOD.** This is the only test that
   discriminates, and it needs no new code. Prediction: if VOD still hangs, the
   patch is exonerated entirely and the fault is the merged/re-signed 17.3.0
   build or the app itself — most likely candidate being VOD's Widevine licence
   path rejecting a re-signed client while live TV (less protected) survives.
   If VOD plays, the fault is in Patch 2 or Patch 3, and Patch 3 is the
   candidate since Patch 2's method is pure view work (`setAlpha`,
   `findViewById`, Glide, `ViewPropertyAnimator`) and cannot gate playback.
2. **Only if build 4 plays:** bisect Patch 2 vs Patch 3 — one build each.
3. **If build 4 hangs:** stop patch-side work. Install the *unpatched* merged
   universal (signed with the stable key) and try VOD. If that hangs too, 17.3.0
   is unusable on this device regardless of patching, and the answer is to stay
   on 16.21.0 until a later 17.x build changes something.

Do not add `AppTarget("17.3.0")` under any of these outcomes until VOD plays.

## Recommended next steps (original, superseded by the list above)

1. **On-device verify 17.3.0 as-is** per `docs/AGENT_PATCH_WORKFLOW.md`:
   build `.mpp` → merge the `.apkm` → patch → install on the Onn 4K
   (`adb connect 192.168.12.210:5555`) → confirm via logcat, not just
   "Applied". Check VOD pre-roll + mid-roll, pause ads, and a live break.
2. **Watch the IMA gate (Patch 1b) on logcat.** It is now applied
   unconditionally where the provider exists, so confirm IMA tears down and
   resumes content rather than re-requesting the pod (the 16.12-era retry
   storm).
3. **Rebuild the generated seam map** — `docs/SEAM_MAP.md` says "Generated — do
   not edit by hand", so the Patch 1b row needs
   `node scripts/seam-map/extract.mjs && node scripts/seam-map/render.mjs`.
   `docs/seam-map/overlay.json` (a source, not generated) is already updated.
4. **Only then** add `AppTarget("17.3.0")` to the head of
   `PARAMOUNT_TV.targets` in `AppCompatibilities.kt` and refresh the
   "Validated against" headers in `ParamountPatch.kt` / `Fingerprints.kt`
   plus the README support table (line 45, currently showing `16.17.0`,
   8/4/26) and the README's APKMirror download link (line 215, still points
   at the 16.17.0 release page).
