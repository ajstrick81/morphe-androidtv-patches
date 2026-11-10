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

**17.3.0 is not marked supported, but the reason is weaker than this note
originally claimed.** On 2026-11-08 VOD would not start on any patched 17.3.0
build — `VideoPlayerActivity` opens and sits on an infinite spinner, with no
exception of any kind, while live TV plays.

> ### ⚠️ ALL 2026-11-08 DEVICE RESULTS ARE CONFOUNDED (noted 2026-11-09)
>
> Every one of those four device tests ran inside one long session of repeated
> installs, force-stops and relaunches, and **device graphics-memory (CMA)
> starvation was never checked for**. That failure mode — spinner, no picture,
> decoder running, and *no* FATAL / VerifyError / DRM error — is already
> documented on this exact device (coffey, 2026-10-31) and its fix is a reboot,
> not a rebuild. Install churn is itself a way to cause it.
>
> The crash scans run that day grepped only
> `FATAL|AndroidRuntime|VerifyError|NoClassDefFound|UnsatisfiedLink`. None of
> those match the starvation signature, so "clean, no fatals" was reported for
> a device that may well have been starved from the second build onwards.
>
> **Consequence:** "17.3.0 VOD is broken" is NOT established. Neither is the
> A1/A2 fork below. The results are confounded rather than void — the device
> state during each test is simply unknown, so none of them can settle anything
> alone. The device was never rebooted and never re-tested clean.
> Re-run from a rebooted device with `testing/scripts/devicehealth.sh check`
> before drawing any conclusion about 17.3.0. Full signature, thresholds and
> method notes: [docs/ONN_PLAYBACK_HEALTH_CMA.md](../../../docs/ONN_PLAYBACK_HEALTH_CMA.md).
>
> What does survive, because it rests on static evidence rather than on those
> tests: the seams are byte-identical to 16.21.0, and the patched dex is
> verified well-formed at all three injection sites.

See "On-device results" and "Corrections" below; read those before trusting
anything in the original static sections, which were written before any of it
was tested.

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
| 4 | All 3 reduced to `nop` (behaviourally vanilla) | not retested | ❌ infinite spinner |

Build 4 exists and is verified correct (`E:\Morphe\work\pplus-17.3.0-allnop.apk`,
`morphe.slate.d` absent from its dex) but was not installed — testing stopped
here deliberately, see "What to do next".

**All four rows are CONFOUNDED — see the confounder box in "Verdict".** They
were taken across one session of repeated installs with no reboot and no CMA
check, so the device state behind each one is unknown. Treat them as weak
evidence that cannot carry a verdict on its own, not as proof about the builds —
and not as nothing either. Note live TV played throughout, so the device was not
starved to the point of having no graphics capacity at all; that doesn't show
VOD had enough (VOD plausibly needs larger contiguous allocations), but it does
argue against assuming total starvation.

What the logs show: nothing — but only because the wrong thing was grepped. No
exception, no `AdError`, no retry storm, nothing in the app process at all.
The CMA/gralloc starvation signature was never searched for, and it would not
have matched any pattern that was. The app does load `imasdk` / `pcam.jar`
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

1. **Pluto's Hooks 3/4/8 are a HYPOTHESIS for Paramount+, not a requirement.**
   An earlier version of this note said 17.x VOD ad suppression "is a port of
   Pluto Hooks 3/4/8". That overreached. Pluto needs the DASH manifest strip
   because *Pluto's* VOD mid-rolls are FreeWheel-stitched into a multi-period
   manifest; Paramount+ 16.x VOD ads, pre-roll and mid-roll, were fully handled
   by the `shouldPlayAd` gate alone (see the coverage list in
   ParamountPatch.kt). Different ad delivery, so the same hooks do not
   automatically apply. Whether Paramount+ 17.x changed delivery is unknown and
   cannot be known until VOD plays at all — there are currently no observations
   of a 17.3.0 ad, only of playback never starting.
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

## Baseline CONFIRMED: patched 16.21.0 played VOD

Confirmed by the maintainer, 2026-11-08: **VOD worked on the patched 16.21.0
build** (stable-key-signed, installed since 2026-10-31). So this is a real
regression at 16.21.0 -> 17.3.0, not a long-standing gap, and the question
"what changed in 17.3.0" is the right one.

Combined with everything above, the regression is pinned between these facts:

  - the three patch seams are byte-identical between the two versions
  - the patched 17.3.0 dex is verified well-formed at all three sites
  - Patch 3 is inert for VOD URLs
  - a build with Patch 1 reduced to `nop` hangs identically
  - 16.21.0 patched the same way plays VOD

Those cannot all be true *and* have the patch be the cause, unless the patch
interacts with something new in 17.3.0 rather than being wrong in itself.

**RESOLVED 2026-11-08: the all-`nop` build hangs too (build 4 above).** The
patch is a bystander. Every hook injects a single `nop`, `morphe.slate.d` is
absent from its dex, and VOD still never starts. Nothing on the patch side can
fix this, and no further patch-side bisecting is worth doing.

What remains is one open fork, since the all-`nop` build still went through the
merge and re-sign:

  A1. 17.3.0 itself will not play VOD on this Onn.
  A2. The merge/re-sign pipeline breaks 17.3.0 VOD, though it did not break
      16.21.0's.

Testing A1 directly needs the official 17.3.0 installed, which requires a
different signature and therefore an uninstall — it costs the account login, so
it is not worth doing for this.

A cheaper control that costs nothing: **re-merge 16.21.0 with the CURRENT
APKEditor (1.4.9), patch it and install it.** 16.21.0 is a known-good app
version, so this isolates the tooling. If VOD works, the pipeline is sound and
17.3.0 is the problem (A1) — and the working app is restored in the same step.
If VOD hangs, APKEditor 1.4.9 (or something else in the current pipeline) is the
culprit rather than 17.3.0, which would affect every app in this repo, not just
Paramount+. Note the existing `paramount-16.21.0-universal.apk` dates from
2026-10-23 and was merged by an unknown earlier APKEditor version, which is
exactly why re-merging it now is informative.

Withdrawn lead: an earlier note here suspected APKEditor's
`extractNativeLibs=true`. That comparison was invalid — it read the 17.3.0 value
from `base.apk` and the 16.21.0 value from a merged universal, and
`extractNativeLibs="true"` is Paramount's own manifest setting in 17.3.0, not
something the merge imposed.

### Downgrading to 16.21.0 is BLOCKED without an uninstall

`adb install -r -d` does NOT work here. Attempted 2026-11-08:

```
INSTALL_FAILED_VERSION_DOWNGRADE: Update version code 520000892 is older
than current 520000982
```

`-d` only permits a downgrade for *debuggable* apps; a release-signed build is
refused regardless. So with 17.3.0 (520000982) installed, 16.21.0 (520000892)
cannot go back in place. The remaining routes, none of them free:

  - `adb shell pm uninstall -k com.cbs.ott` keeps the data/cache directory, then
    install 16.21.0. The signature matches (same stable key), so the kept data
    should reattach and the login should survive — but this is not guaranteed,
    `-k` fails outright on some devices, and 17.3.0-written data may not be read
    cleanly by 16.21.0.
  - Full `adb uninstall` + reinstall, then sign in again. Reliable, costs the
    login.
  - Install the OFFICIAL 17.3.0 from the Play Store after uninstalling. Costs
    the login too, but it is the one action that also answers the open A1/A2
    fork for free: if official 17.3.0 plays VOD, our merge/re-sign pipeline is
    implicated (A2); if it does not, 17.3.0 is simply broken on this device (A1).

Build ready for whichever path is chosen:
`E:\Morphe\work\pplus-16.21.0-patched-fresh.apk` — 16.21.0 re-merged with
APKEditor 1.4.9, patched with the current source, stable-key signed, hooks
verified live (`morphe.slate.d` present in its dex, so it is not a no-op build).
Note APKEditor 1.4.9 applied `extractNativeLibs=true` to the 16.21.0 merge as
well, so that setting is tool behaviour and not specific to 17.3.0.

The control test this build was made for has NOT been run, because it could not
be installed. It remains the cheapest way to separate A1 from A2 without
touching the Play Store, once the package can be downgraded.

### Device state as of the 2026-11-08 stop

The Onn is running the **17.3.0 all-`nop`** build (versionCode 520000982):
behaviourally vanilla, no ad suppression, and VOD does not play. Live TV works.
This is not a usable daily state and should be resolved before further work.

## Provider selection map (17.3.0, static — 2026-11-09)

Which AVIA provider serves a given stream is decided by the CONFIGURATION the
app builds, dispatched in `AviaDefaultResourceProviderFactory.createResourceProvider`:

| Configuration | Provider |
|---|---|
| `CLID3ResourceConfiguration` | `AviaCLID3ResourceProvider` |
| `UriResourceConfiguration` | `AviaUriResourceProvider` |
| `IMAResourceConfiguration` | `AviaIMAResourceProvider` |
| `DAIResourceConfiguration` | `AviaDAIResourceProvider` — **and**, when the DAI config carries a `clid3` sub-config, the factory additionally calls `AviaDAIResourceProvider.setCLID3(...)` |

That last row matters: a DAI stream can ALSO run CLID3, which is the
client-side (CSAI) ad path — the one Pluto's v17 Hook 4 suppresses at
`CLID3Processor.processAdPodStartAsync`. So "VOD is DAI" would not by itself mean
`shouldPlayAd` covers every ad on it.

App-layer builders (R8-renamed, one per configuration, found by searching for
`new-instance` of the readable config classes outside the `avia` package):

| Config | Builder class (17.3.0) | Identifying evidence |
|---|---|---|
| DAI | `tj6` | logs its own tag `DaiResConfigUseCase`, plus `DaistreamKey: `, `DAIResourceConfiguration:: `, `playing:CMS source ID =  `, `promoskip` |
| IMA | `aq6` | constructs `IMAResourceConfiguration` (line ~1716); large multi-purpose class, also carries DMA/affiliate strings |
| CLID3 | `ti6` | constructs `CLID3ResourceConfiguration` |
| Uri | `pz4`, `l52`, `AbstractAviaPeekviewPlayer` | peekview/trailer paths |

`tj6` itself carries no live-vs-VOD string discriminator; the branch is in its
callers (`yu3`, `hoe`). Rather than chase that through renamed code, note what
the above already buys us:

### The log tags defeat the "AVIA logs nothing" problem

The reason install-and-compare was useless on 2026-11-08 is that AVIA logs
nothing about ad decisions at default verbosity. But the **app's own
configuration layer** logs readable tags. So a single filtered capture during a
VOD attempt answers which provider VOD uses, with no guessing:

```bash
adb logcat -c
# start a VOD title on the TV, then:
adb logcat -d | grep -E "DaiResConfigUseCase|DAIResourceConfiguration|DaistreamKey|IMAResourceConfiguration|CLID3ResourceConfiguration|processAdPodStart|imasdk"
```

- `DaiResConfigUseCase` / `DaistreamKey:` present → VOD is DAI, so Patch 1's
  `shouldPlayAd` gate is on the right seam.
- `IMAResourceConfiguration` present instead → VOD is client-side IMA, and
  Patch 1 does not cover it. That would be the first real evidence for an IMA
  gate — the thing that was assumed in db79539 and never demonstrated.
- `CLID3ResourceConfiguration` alongside DAI → CSAI ads run in parallel with the
  DAI pods, and Pluto's Hook 4 becomes relevant for Paramount+ too.

Capture this on the same run as the reboot-then-retest below; it costs nothing
extra and settles a question that has so far only been speculated about.

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
