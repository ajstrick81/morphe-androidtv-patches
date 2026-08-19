# YouTube TV — Native Player Core (DAI/cuepoint) analysis

Analysis of the C++ core from the **JNI boundary** (Java side of base.apk). Full
ARM disassembly of the `.so` itself is pending upload of
`split_config.arm64_v8a.apk` (base.apk contains **zero** `.so` files — all
native libs ship in the arch splits).

## The native core is "platypus"

- Player core = **platypus** (`PlatypusException`, package
  `.../media/player/exo2/platypus/`), YouTube's native media engine.
- Live streaming uses **onesie / SABR** (`native_onOnesieLiveMetadata`,
  `native_onOnesieMediaHeader`, `native_onOnesieSabrSeek`,
  `SabrLiveProtos$SabrLiveMetadata`), not classic DASH/HLS client parsing —
  another reason ad segments are server-driven, not client-manifest-visible.
- `.so` is loaded via `System.loadLibrary(str)` (dynamic name); the platypus/
  player library lives in the arm64/armeabi split, not base.apk.

## DAI is native-authoritative (evidence)

The Java `CuePointDataProviderWrapper` is a thin JNI shim around a native object
(`long nativePtr`). The **direction of the calls** shows native owns DAI:

- `createNative(NativeCallback, Executor) -> long` — native creates the cuepoint
  provider; Java holds only the pointer.
- `native_setCuePointDataProvider(long, CuePointDataProviderWrapper)`
  (on `PlaybackController$CppProxy`) — the provider is registered **into** the
  native player.
- `onCuepointList(byte[])` — native → Java **callback** delivering the cuepoint
  list (native is the source).
- `nativeOnAdBreakFulfillmentStatusChanged(long, String id, int status,
  byte[], String[])` — Java reports break fulfillment **back to** native
  (status = `CuePointStatus` value). Native **tracks** which breaks were
  fulfilled → native schedules/accounts for breaks.
- `nativeSetDaiDisabledByNoConfig(long)` — Java can tell native to **disable
  DAI**. A native disable switch only exists because native is the authority.

**Consequence:** a pure-Java `onCuepointList` no-op (the `Suppress ad breaks`
patch) is **doubtful on its own** — native may already have the cuepoints (it
sent them) and schedule stitched breaks regardless of whether Java re-dispatches
them. Must be validated on-device; treat as unproven.

## Better lever: the app's OWN native DAI-disable path

`PlaybackControllerWrapper` (`exo2/platypus`, ~L1745) already contains a code
path that calls `nativeSetDaiDisabledByNoConfig(nativePtr)` — but **gated behind
a server hotconfig experiment flag**:

```
bfot flag = hotConfig.get(45731777L)          // experiment flag
if (flag != null && flag.kind == 1 && flag.boolValue) {
    executor.schedule {
        if (!wrapper.f98911a) wrapper.nativeSetDaiDisabledByNoConfig(nativePtr)
    }
}
```

So Google ships a **sanctioned "turn DAI off" native call**, normally only
reached when experiment `45731777` is enabled for the account. This is the
strongest candidate lever:

> **Candidate patch B (recommended over the callback no-op):** force the
> `45731777` gate true (or unconditionally schedule the
> `nativeSetDaiDisabledByNoConfig(nativePtr)` call), invoking the app's own
> native DAI-disable. Because it uses the native core's intended "no DAI" state,
> it is far more likely to actually stop stitched breaks than blindly dropping
> the Java cuepoint dispatch.
>
> Caveats: (1) semantics — "disabled by **no config**" may mean "play content
> through with no ad decisioning" (good) OR route to unsold-break filler (the
> Zen slate). Needs device test. (2) The gate sits deep inside a large nested
> method in `PlaybackControllerWrapper`; fingerprinting the exact flag-check is
> harder than the clean `CuePointDataProviderWrapper` shim — may be easier to
> hook the public `nativeSetDaiDisabledByNoConfig` caller site, or add an
> extension that calls it right after `setCuePointDataProvider`.

## Nearby hotconfig flags (context, not yet mapped)

Other experiment flags in the same `PlaybackControllerWrapper` region (unknown
semantics — candidates for further ad/DAI gating):
`45703767, 45709258, 45712886, 45717361, 45720636, 45721040, 45721497,
45731777, 45752321, 45756615`.

## NATIVE DISASSEMBLY (done — armeabi-v7a split, the Onn device's ABI)

The native core is **`libgoogle3.so`** (13.5 MB, monolithic). `libplatypus.so`
is a 5.5 KB loader stub (`JNI_OnLoad_libplatypus`); the real DAI code is in
`libgoogle3.so`. Confirmed C++ symbols (Itanium-mangled, in rodata):
- `youtube::media::CuePointDataProvider`, `CuePointDataProviderImpl`,
  `CuePointDataProvider::Callback`, and jni `CuePointDataProviderCallbackWrapper`
- proto types `video_streaming::CuepointList` / `CuepointContext` /
  `ServerStitchedDaiInfo` / `StitchedSegmentsMetadataList`, `streamer::CuePoint`

JNI is **dynamically registered** (stripped lib, no exported symbols). Found the
`JNINativeMethod` table entry for the disable lever by scanning for a pointer to
the `"nativeSetDaiDisabledByNoConfig"` string:
- name `nativeSetDaiDisabledByNoConfig`, sig `(J)V`, **fn @ vaddr `0x574c9e`**
  (Thumb).

Disassembly of that fn (JNI `(env, thiz, jlong ptr)`, `ptr` low word in r2):
```
cbz  r2, ret          ; if native ptr == 0, return
ldr  r0, [r2]         ; obj = *ptr        (deref shared_ptr control -> impl)
ldr  r1, [r0]         ; vtable = *obj
ldr  r1, [r1, #0x2c]  ; slot 11 (0x2c/4) = a VIRTUAL method
bx   r1               ; tail-call it
```
→ `nativeSetDaiDisabledByNoConfig` is a thin shim that **tail-calls virtual
method #11 on the `CuePointDataProviderImpl`** — a real native "disable DAI"
operation the C++ core owns and honors. **This confirms candidate patch B is a
genuine lever:** forcing the app to invoke this call flips a native switch the
core respects, rather than fighting the Java dispatch. (Answers the old open
question: native IS authoritative — it created and holds the cuepoints and
exposes its own disable — so the Java `onCuepointList` no-op alone, patch A, is
the weaker bet.)

## ⚠️ ANTI-TAMPER WALL: native ad-blocker enforcement (NEW, important)

`libgoogle3.so` contains an SSDAI **response-action enum** (one merged rodata
blob), the set of actions the native core takes on an SSDAI/ad-stitch server
response:
```
DISABLE_SSDAI | FAIL_PLAYBACK | FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT
| FALLBACK_TO_CABR | FALLBACK_TO_SECONDARY_HOST | HANDLE_HTTP_RESPONSE_CODE
| RESUME_PLAYBACK_WITH_AD_BLOCKER_DETECTED
```
So YouTube TV ships the **same native ad-blocker enforcement** as mainline
YouTube: the *server* can command the client to **fail playback and show an
ad-blocker warning**, or resume flagged. It is **server-driven**, and the client
signals its ad state back via `nativeOnAdBreakFulfillmentStatusChanged`
(per-break fulfillment status). If we suppress breaks, we either report
non-fulfillment or stop fetching ad segments/firing beacons — exactly what the
server watches — and it can respond with
`FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT`.

**Strategic consequence (reframes the whole patch effort):**
- Naive ad removal (patch A, or blindly emptying breaks) risks tripping
  enforcement → black screen / "ad blocker" wall, a *worse* outcome than ads.
- The **least-risky lever is the app's own sanctioned path**
  (`SetDaiDisabledByNoConfig` / the `45731777`-gated call): it is a *legitimate*
  client state the server already understands ("no DAI config"), so it is the
  suppression most likely to avoid enforcement. Still must be verified on-device
  against a live break — enforcement behavior can only be observed at runtime.
- A network-level block of the DAI/ad hosts would likely trip the same
  enforcement (missing beacons/segments), so it is **not** a safe shortcut here.

## Remaining native questions (next dig, if pursued)

1. Disassemble virtual method #11 on `CuePointDataProviderImpl` to see exactly
   what state it clears (break scheduler vs. just config fetch).
2. Find the function that switches on the SSDAI response-action enum to learn
   the precise trigger for `FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT` (what
   client signal the server keys on) — this defines the safe envelope for any
   suppression.
3. arm64 (`split_config.arm64_v8a.apk`) is only needed for 64-bit devices; the
   Onn runs v7a, already analyzed here.

## Tooling used (reproducible)

```
unzip split_config.armeabi_v7a.apk 'lib/*' -d nativelib/
strings -a libgoogle3.so | grep -iE 'CuePointDataProvider|Ssdai|DaiDisabled|AdBlocker'
# locate dynamic-JNI fn ptr: scan rodata/.data for a LE pointer to the method
#   name string; the JNINativeMethod triple is {name*, sig*, fn*} (12 bytes)
# disassemble Thumb at fn vaddr with capstone (CS_ARCH_ARM, CS_MODE_THUMB)
```
