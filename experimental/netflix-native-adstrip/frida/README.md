# Acquiring the Netflix appboot bundle on a **non-rooted** Onn (frida-gadget)

`dump_appboot.js` captures the appboot player JS in-process — **after** MSL-decrypt and
**after** the load-time appboot signature check — so it never touches the signed bytes at rest.
No root: Frida runs as **frida-gadget**, a `.so` loaded inside Netflix's own process.

## Why this is offset-free
`libnetflix.so` is stripped + statically links OpenSSL/Hermes/Gibbon, so symbol hooks are
unreliable. The script uses only **libc file I/O** (always exported) + **generic memory scanning**
+ **export recon**. It also tags each dump `JS` vs `HBC` (Hermes bytecode) — which decides whether
seam B (edit the ad resolver as source) is available or we lean on seam A (scrub the manifest data).

## Step 1 — get frida-gadget into Netflix (two routes)

**Route A — reuse the toolkit (recommended).** The Morphe `LoadNativeHookPatch` already injects a
native `.so` and loads it in `Lcom/netflix/ninja/NetflixApplication;` `onCreate` (the subclass the
assessment resolved for exactly this). Point it at **`libgadget.so`** instead of the hook lib:
1. Download frida-gadget for **armeabi-v7a** (match the app ABI), rename to `libgadget.so`.
2. Drop a gadget config next to it so it loads our script in listen mode:
   ```json
   // libgadget.config.so  (frida looks for <soname>.config.so)
   { "interaction": { "type": "script", "path": "/data/local/tmp/dump_appboot.js" } }
   ```
   (or `"type":"listen"` to attach live from your PC — see Step 3).
3. Run the patcher against `com.netflix.ninja` so the gadget + config ship in the APK and
   `extractNativeLibs` is forced `true` (the toolkit's `BundleNativeHookPatch` already does this).
4. **Also flip `android:debuggable="true"`** in the repackaged manifest — lets you `adb pull`/`run-as`
   the dumps without root.

**Route B — plain apktool.** `apktool d base.apk` → add `lib/armeabi-v7a/libgadget.so` +
`libgadget.config.so` → inject `System.loadLibrary("gadget")` at the top of
`NetflixApplication.onCreate` in smali → `apktool b` → `apksigner sign` with your own key.
(Same effect; Route A is just your toolkit doing this for you.)

Either way you re-sign the **APK** with your own key — that's fine. The **appboot** signature is
Netflix's *internal* signature on the downloaded UI bundle, separate from the Android APK signature;
account login and playback are unaffected by re-signing the APK.

## Step 2 — push the script + install

```
adb -s 192.168.12.210:5555 push dump_appboot.js /data/local/tmp/dump_appboot.js
adb -s 192.168.12.210:5555 install -r netflix.patched.apk
adb -s 192.168.12.210:5555 shell monkey -p com.netflix.ninja 1   # launch
```

The gadget loads on boot, arms the libc taps immediately, runs export recon at +5 s, and does the
first heap sweep at +20 s.

## Step 3 — force an appboot load, then a pre-roll

1. Just launching the app fetches + caches appboot → the **libc I/O taps** and the **heap sweep**
   should catch it. Watch logcat: `adb logcat -s frida:* | grep appboot-dump` (gadget logs to
   logcat), or attach live: `frida -U -n com.netflix.ninja` then `%resume` and call
   `rpc.exports.sweep()` / `rpc.exports.sweepfor("adBreak")`.
2. To capture the **manifest with ad-break markers** (seam A's target data), reproduce §3e: play
   ~7 titles to trip the frequency cap, then the title that shows a pre-roll — during that session
   call `sweepfor("adBreak")` (and try `interstitial`, `cuePoint`, `breakStart`).

## Step 4 — pull the dumps

```
# app external dir (no root, no debuggable needed):
adb -s 192.168.12.210:5555 pull /sdcard/Android/data/com.netflix.ninja/files/nfdump ./nfdump
# or, if you set debuggable=true, from the internal files dir:
adb -s 192.168.12.210:5555 shell run-as com.netflix.ninja ls files/nfdump
```

## Step 5 — upload to the session

Drag-and-drop the `nfdump/` files (especially any tagged `_JS_` with a `gibbon`/`appboot`/`adBreak`
marker) **into the chat**, same as `milo.debug.js`. I'll static-analyze for:
- the **ad-break schema** (fields seam A must scrub), and
- the **empty-break guard** (the `length===0 → return []` branch that seam B forces) —
the Netflix equivalents of Prime Video's `resolveWithAdBreaks` / `0===t.length`.

## If nothing lands
- **All dumps tagged `HBC`** → appboot ships as Hermes bytecode. Good to know early: it means seam B
  needs an HBC-aware edit, so we lead with **seam A** (manifest data is a normal JS object regardless).
  Send an HBC dump anyway — I can disassemble it.
- **Gadget doesn't load / app crashes on boot** → the repackage tripped an integrity check
  (`libc94d.so` is the suspect from §3a). That's the "does it even boot patched" gate; tell me the
  logcat and we adjust (e.g. load the gadget later, or from a separate process).
- **No netflix paths in the I/O log** → appboot may be served from memory only; rely on the heap
  sweep and `sweepfor()` with the markers above.
