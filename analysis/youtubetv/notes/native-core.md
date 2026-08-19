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

## What the `.so` disassembly would add (pending arm64 split)

With `split_config.arm64_v8a.apk` uploaded, the next-level questions to answer
by disassembling the platypus/player `.so`:

1. **Does native splice ads independently of the Java cuepoint dispatch?**
   Find the native symbol backing `onCuepointList` / the cuepoint provider and
   see whether break scheduling reads native-held cuepoints directly. This is
   the decisive answer to "will the Java no-op work?".
2. **What does `nativeSetDaiDisabledByNoConfig` actually do?** Disassemble it to
   confirm it disables break scheduling globally (vs. just stopping config
   fetch). If it globally disables, candidate patch B is the clean fix.
3. **JNI symbol names:** look for `Java_..._CuePointDataProviderWrapper_*` and
   `nativeSetDaiDisabledByNoConfig` exports, plus `RegisterNatives` tables in
   the platypus lib (YouTube uses dynamic JNI registration, so grep the
   `.so` for the method-name strings above to find the native fn pointers).

## Tooling for the native step (when split is available)

```
unzip split_config.arm64_v8a.apk 'lib/arm64-v8a/*.so' -d nativelib/
# identify the player core
for so in nativelib/lib/arm64-v8a/*.so; do
  echo "== $so =="; strings -a "$so" | grep -iE 'nativeSetDaiDisabledByNoConfig|onCuepointList|CuePointDataProvider|platypus' | head
done
# disassemble the DAI functions (objdump/radare2/Ghidra headless)
#   r2 -A libplatypus.so ; then: / nativeSetDaiDisabledByNoConfig ; axt @ hit
```
