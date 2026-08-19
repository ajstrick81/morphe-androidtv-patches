# YouTube TV — "Suppress ad breaks" on-device test runbook (Onn, armeabi-v7a)

Patch under test: `youtubetv/ads/SuppressAdBreaksPatch.kt` — drives the app's own
`nativeSetDaiDisabledByNoConfig(nativePtr)` in `CuePointDataProviderWrapper.<init>`
(sets native flag obj+0x71 = "DAI disabled by no config"). See
`native-core.md` for why this lever was chosen over dropping cuepoints.

## Build

1. Patch build must run against the private Morphe registry (this container
   can't; `./gradlew :patches:build` fails on `app.morphe.patches` plugin auth).
2. Produce the patched APK set for `com.google.android.apps.youtube.unplugged`
   10.33.2 (versionCode 210330210) with the `Suppress ad breaks` patch enabled.
   Keep the split config used by the Onn (armeabi-v7a) — do NOT re-sign with a
   different ABI set.

## Install (Onn Android TV)

```
adb connect <onn-ip>:5555
adb install-multiple base.apk split_config.armeabi_v7a.apk   # patched base + its splits
# (or the patcher's mount/install flow this repo uses for other ATV apps)
adb shell monkey -p com.google.android.apps.youtube.unplugged 1
```

## Verify the hook actually ran (before judging ads)

The patch injects one call in `CuePointDataProviderWrapper.<init>`. Confirm it is
present and reached:

```
# logcat while starting a live channel — watch for the provider being created
adb logcat -c && adb logcat | grep -iE 'CuePoint|Dai|ssdai|platypus|AdBlock'
```

There is no guaranteed log line for the disable itself (it's a native flag set),
so the primary signal is behavioral (below). If you want positive proof the
native call fired, attach and break on the JNI symbol
`nativeSetDaiDisabledByNoConfig` (fn @0x574c9e in libgoogle3.so) — see the frida
snippet at the bottom.

## The three things to observe on a LIVE channel with ad breaks

1. **Do commercial breaks disappear?** Watch a channel through a known ad break
   (e.g. a live sports/《network》break). PASS = content continues / no ad video.
2. **Does the "Enjoy the zen" filler disappear too?** It rides the same cuepoint
   path, so if breaks are suppressed the Zen slate should also be gone. Its
   presence = breaks NOT suppressed.
3. **Does the ad-blocker ENFORCEMENT wall appear?** THIS IS THE KEY RISK. Watch
   for: playback failing, a black screen, an "ad blocker"/"content unavailable"
   message, or playback that starts then aborts a few seconds in. Any of these
   = the native enforcement (`FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT`) tripped
   → this lever is not safe as-is.

Record VOD/DVR behavior separately from LIVE — they may differ.

## Outcome matrix → next action

| Breaks gone? | Enforcement wall? | Verdict / next step |
|---|---|---|
| Yes | No  | ✅ Success — the sanctioned lever works. Tighten, document, ship as opt-in. |
| Yes | Yes | ⚠️ Native disable stops breaks but the server still enforces. Investigate whether reporting the breaks as fulfilled (via `nativeOnAdBreakFulfillmentStatusChanged`) placates the server, or accept live is unfixable. |
| No  | No  | Native flag set too early / gets reset, OR native schedules independently of this flag. Move the call to AFTER `setCuePointDataProvider` (PlaybackControllerWrapper site) and retest; if still no change, the flag isn't the scheduler gate. |
| No  | Yes | Worst case — flag didn't suppress but tripped enforcement anyway. Revert; reassess whether any client-side suppression is viable given PoToken/StreamProtection. |

## Fallback hook (if constructor timing is too early)

If row 3 ("No / No") happens, switch the injection site to
`PlaybackControllerWrapper` right after
`playbackController.setCuePointDataProvider(cuePointDataProviderWrapper)`
(~L1745 region), calling
`cuePointDataProviderWrapper.nativeSetDaiDisabledByNoConfig(cuePointDataProviderWrapper.nativePtr)`
unconditionally — this matches exactly where the app's own gated runnable would
fire. It's a harder fingerprint (deep nested method) but the most faithful timing.

## Optional: prove the native call fired (frida)

```js
// libgoogle3.so base + 0x574c9e (thumb -> |1). Adjust base at runtime.
const base = Module.findBaseAddress("libgoogle3.so");
Interceptor.attach(base.add(0x574c9e | 1), {
  onEnter() { console.log("[+] nativeSetDaiDisabledByNoConfig called"); }
});
// and the vmethod#11 impl to confirm the flag write:
Interceptor.attach(base.add(0x5768d4 | 1), {
  onEnter(a) { console.log("[+] CuePointDataProviderImpl::SetDaiDisabledByNoConfig this=", a[0]); }
});
```
