# Netflix ad-strip investigation — session handoff

**For:** a fresh session picking this up. **Status:** ⚠️ **REOPENED** — the earlier
"not strippable / research-parked / closed" verdict has been **withdrawn**. See
**`REOPENING.md`** (read it first): the appboot signature was misread as a runtime wall
when it is a **load-time door**, and the app's own **no-ad path runs 6 of 7 titles**
(§3e). The live lead is **seam A — scrub ad-break markers from the decrypted manifest
object in the Hermes heap**, downstream of both MSL and the signature. This doc + the
detail below (`PORTABILITY-ASSESSMENT.md`) remain the full architecture context; only the
*conclusion* changed.

---

## 0. Session re-verify (2026-08-06, on-device 13.0.1-25028)

Ran REOPENING.md **step 1** against the *current* device APK (Netflix updated 25009 → **25028**;
pulled `split_config.armeabi_v7a.apk` off the Onn at `.211`, extracted `libnetflix.so`, 88.5 MB).
**All seam markers survive** — architecture is unchanged by the bump:

| marker | count | meaning |
|---|---|---|
| `HERMESATOM` | 1 | Hermes engine present |
| `appboot_fail_nas_verify` / `appboot_key` | 2 / 5 | appboot signature door present |
| `RSASSA` / `SPKI` | 15 / 31 | appboot pubkey verify present |
| `getMslEncoderFactory` / `MslEncoderFormat` | 53 / 18 | MSL layer present |
| `milo.prod.js` / `milo_update_url` / `milo_ignore_hash_errors` | 6 / 1 / 3 | milo present |
| `libandroid_netflix` / `OpenSSL 3.2.1` | 1 / 43 | soname + crypto unchanged |

**⭐ BONUS FINDING (upgrades the seam strategy):** the appboot / nrdp / milo layer is embedded as
**plain minified JavaScript source**, NOT Hermes bytecode (HBC). Proof: the `appboot_fail_nas_verify`
and `milo_ignore_hash_errors` strings appear *inside readable JS* — e.g. `l.declare({appboot_key:…,
appboot_fail_nas_verify:["appboot_fail_nas_verify",!1],…})` and
`if(fe.milo_ignore_hash_errors){…warn("Allowing insecure response…")}`. HERMESATOM=1 / "Hermes "=0
means Hermes is the *runtime* but boot scripts are loaded as JS via `nrdp.gibbon.loadScript`.
Consequence: **seams B and C (live-edit the appboot ad-break resolver to force its own empty return)
are viable**, not just seam A (data scrub) — the resolver will be readable JS in the heap. This was
the main open risk from the assessment ("if dumps are HBC, lead with seam A"); it is retired.

Also surfaced: `appboot_test_response`, `appboot_drop_mt`, `appboot_ignore_retrycontrol` config
knobs worth probing during dump analysis.

### Step 2 DONE — gadget capture APK built & verified (2026-08-06)

- Downloaded `frida-gadget-17.9.1-android-arm` (ELF32 ARM), staged at
  `patches/src/main/resources/netflix/native/armeabi-v7a/libgadget.so` (16 MB; **gitignored** via
  `patches/src/main/resources/netflix/native/**/*.so`). Both gadget patches now list + enable in the
  CLI. Merged base+armv7a → universal, patched → `nf-gadget.apk`. VERIFIED in the output APK:
  `lib/armeabi-v7a/libgadget.so` + `libgadget.config.so`, `System.loadLibrary("gadget")` in
  classes3.dex (absent in original), manifest `debuggable="true"` + `extractNativeLibs="true"`,
  Morphe-signed. The build path is reproducible.

### Step 3 BLOCKED — Netflix is a PREINSTALLED SYSTEM APP on the Onn (can't replace on non-root)

On-device proof (`.211`): installed Netflix is Play-signed `bcfa260e`, `firstInstallTime=2008-12-31`
(epoch → system image), and appears under `pm list packages -s`. Consequences:
- In-place update of the Morphe-signed gadget build → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  (signatures differ). Expected.
- `pm uninstall -k` only removed the **25028 update**, reverting to the **system-image base
  12.1.1-23010**; the retained data still carries the old signer so the re-signed install still fails.
- Full `adb uninstall` → **`DELETE_FAILED_INTERNAL_ERROR`** — a system app can't be removed without
  root. So the gadget APK **cannot be installed over the stock package** on this non-rooted device.
  This is the same wall as [[vix-onn-tv-keystore-mismatch]], but harder (system app, not just key
  mismatch). **Device was restored**: reinstalled the original Play-signed 25028 splits via
  `adb install-multiple` → Success, back to 25028/`bcfa260e`, login data preserved.

### Unblock path = CLONE the package (next step)

The gadget build only fails because it collides with the stock package identity. A **package-rename
clone** (à la the repo's existing `CloneAppPatch` for Pluto/Peacock/PV) gives it a NEW package name →
no signer/system collision → installs ALONGSIDE stock Netflix, debuggable + gadget-injected. The
appboot bundle loads at boot/browse (before playback), so even if Widevine playback refuses inside a
clone, `dump_appboot.js` (libc I/O taps + heap scan) can still fire during startup and yield the JS
appboot dump. RISK: Netflix has many hardcoded package refs + per-package DRM provisioning; cloning
may need more than a manifest rename.

### Clone BUILT + INSTALLED + launches — but hits DexGuard CertCheck (2026-08-06)

Wrote `patches/.../netflix/misc/clone/CloneAppPatch.kt` (opt-in, default=false), modeled on the
Pluto clone but with a critical addition: Netflix declares components with RELATIVE names
(`android:name=".NetflixApplication"`, 16 total), so the patch **expands every relative android:name
/ targetActivity to its fully-qualified `com.netflix.ninja.*` form BEFORE renaming `package`** — else
`.Foo` would re-resolve against `com.netflix.ninja.clone` and ClassNotFound at launch. Also renames
the 3 provider authorities + 11 custom permissions (and propagates renames to
`android:permission`/read/writePermission). VERIFIED in the built APK: package=`com.netflix.ninja.clone`,
`.NetflixApplication`→`com.netflix.ninja.NetflixApplication`, zero relative names left, authorities +
perms uniquified. Installed ALONGSIDE stock (`adb install` Success; both packages listed).

On launch the clone got FURTHER than expected: `com.netflix.ninja.MainActivity` resolved, Widevine
`createDrmPlugin[com.netflix.ninja.clone]` ran — then the process **died ~2.5s in**. Crash cause
(logcat): **`E Loader: CertCheck failed, crash!!!`**, and `classes4.dex` contains
`<DexGuardCertCheckException : DexGuard App CertCheck failed !`. → the final gate is **Guardsquare
DexGuard's application CertCheck**: an obfuscated runtime check that reads the APK signing certificate
and deliberately crashes when it isn't Netflix's official cert (this IS the `libc94d.so`/anti-tamper
the assessment suspected). Note libnetflix.so only leaks `StrictCertCheck` (strings packed); the
crashing check lives DEX-side in classes4.dex (DexGuard), likely native-reinforced.

**Stock Netflix restored and untouched; the clone is a separate package** — safe to leave installed
or `adb uninstall com.netflix.ninja.clone`.

### NEXT — defeat DexGuard CertCheck (the gadget is already injected in the clone)

The frida-gadget loads at Application.onCreate BEFORE the CertCheck fires, so the elegant path is to
turn the passive dumper into an active bypass:
1. Hook the signature retrieval — `PackageManager.getPackageInfo(..., GET_SIGNATURES/GET_SIGNING_CERTIFICATES)`
   and `SigningInfo.getApkContentsSigners()/getSigningCertificateHistory()` — to return Netflix's
   ORIGINAL cert. Stock signing cert SHA (from dumpsys, capture during bypass work):
   `36:38:63:59:6E:A9:92:41:EB:71:B1:A9:85:55:3A:A6:04:DE:3E:A3:C5:F0:C5:46:74:23:90:E6:82:16:4E:6B`.
2. If DexGuard reads the cert natively / straight from META-INF (not via PackageManager) — likely,
   given it's DexGuard — API hooking won't suffice; then locate the CertCheck method in classes4.dex
   (search the `DexGuardCertCheckException` reference back to its thrower) and either patch it to a
   no-op via a bytecode patch, or hook it in-process with the gadget.
3. Fallback: rooted device/emulator (replace stock package; frida-server) sidesteps CertCheck via a
   matching-signature or root-level bypass.
Once the clone survives boot, resume REOPENING.md step 4/5 (force a pre-roll, sweep the Hermes heap
for the ad-break schema, find the empty-break guard = seam B target).

### DexGuard CertCheck DEFEATED (Java) — new wall is a JNI null in Gibbon startup (2026-08-06)

Wrote the bypass: `patches/.../netflix/misc/security/{Fingerprints.kt,DisableCertCheckPatch.kt}`
("Disable Netflix CertCheck", default-enabled). The check is one obfuscated Runnable.run() (build:
`Lo/setReturnTransition$5;`) that calls a verifier `MoMD214(context, "<expected SHA-256>")` and, on
false, logs `CertCheck failed, crash!!!` then reflectively kills the process. run()'s pass-path is
just `return`, so the patch prepends `return-void` at index 0 → whole check is a no-op. Anchored on
the unique crash string (only method in the app that has it), so it survives DexGuard name rotation.

ON-DEVICE (clone, cert-bypass + gadget): the `CertCheck failed` crash is GONE — bypass confirmed.
The clone now boots FURTHER and dies on a different, non-tamper crash:
```
F libc  : Fatal signal 6 (SIGABRT) ... (com.netflix.ninja.clone)
Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_object == null
    in call to GetObjectClass
    from void com.netflix.ninja.NetflixService.nativeGibbonStartup(Surface, String, String, String, boolean, int, int)'
```
This is progress: the app reached **Gibbon startup** (where appboot loads). The abort fires ONLY
because our gadget patch forces `debuggable=true`, which turns on ART **CheckJNI**; a null object arg
to `nativeGibbonStartup` that stock (non-debuggable) tolerates now hard-aborts. Call site =
`NetflixService.smali` line ~5527, args v0..v7 = (this, Surface=p1, saveDir, dataDir, v4-string,
p3, p4, Size.height). Likely-null = the **Surface** during Netflix's speculative/preload UI startup
(or a clone-rename-broken string). CLI disable flag confirmed = `-d "<patch name>"`.

**NEXT (start here tomorrow — cheap, decisive):**
1. Build a diagnostic clone WITHOUT the gadget (so NOT debuggable → CheckJNI off):
   `java -jar $CLI patch -f -e "Clone Netflix" -d "Bundle frida-gadget (Netflix appboot capture)" -d "Load frida-gadget (Netflix appboot capture)" -p $MPP -o nf-clone-nodbg.apk netflix-universal.apk`
   Install + launch. If it BOOTS/plays → the JNI null was a CheckJNI artifact, and the clone itself
   is viable; then re-add the gadget but make it NON-debuggable and have dump_appboot.js write dumps
   to /sdcard (world-readable) instead of app-private + run-as.
2. If it still crashes non-debuggable → the null is a real clone-rename regression; identify which
   arg (add a targeted hook / check getSaveDir/getDataDir/StartupParameters under the clone package).
3. Alternative if the gadget+debuggable combo stays incompatible: root a device/emulator, install the
   cert-bypassed build over stock (frida-server, no clone, no debuggable-forced CheckJNI).
Artifacts in scratchpad (nfverify/): netflix-universal.apk, nf-clone2.apk (cert-bypass+gadget),
nf-clone-full/ (full apktool decode), clonelibs/ (extracted .so).

### 2026-08-07 — Gibbon null DIAGNOSED: it's NATIVE package-identity coupling, not a fixable arg

Ran the non-debuggable clone: **still SIGABRTs at nativeGibbonStartup** — so the crash is NOT a
debuggable/CheckJNI artifact (GetObjectClass(null) is a *fatal* JNI error ART aborts on regardless of
CheckJNI). It's a real null.

Chased which arg via a throwaway bytecode probe (logged args at the caller
`NetflixService.MoMD214(Surface, StartupParameters, Z, I)` — anchored on the unique "gibbonStartup"
string; now deleted). Result:
`GIBPROBE surface=Surface(name=null)/@... params=o.getBackgroundDrawable@...` — **surface and params
are both non-null.** Combined with static proof that the other two derived args can't be null
(`getSaveDir`/`getDataDir` return "" on null; `v4 = DetailsSupportFragment.MoMD214(v10, launchUID)`
returns its input or a built string, never null) → **ALL FOUR Java object args to nativeGibbonStartup
are non-null.** The `java_object == null in GetObjectClass` therefore fires INSIDE the native impl:
libnetflix.so calls back into Java for something (a package-keyed lookup / cached class-or-method ref
/ asset) that returns null under the renamed `com.netflix.ninja.clone` package. (Note the Surface is
`name=null` — invalid/early — but non-null, so not the abort cause.)

**Conclusion:** the clone's remaining wall is **native package-identity coupling in libnetflix.so**,
below the Java line — not patchable by fixing an arg. Behind it still looms ESN/MSL/Widevine identity
(login/playback bound to package). Grinding this means native RE of nativeGibbonStartup (no symbols;
frida blocked because the gadget can't read /data/local/tmp under SELinux `untrusted_app`).

Cert-bypassed clone data dir (via run-as, debuggable) has NO gibbon diskcache yet — it dies before
fetching appboot, so there's no cached-JS shortcut.

### 2026-08-07 (cont.) — NATIVE wall CRACKED: clone now RUNS past both anti-tampers

Went with option 2 (native RE, non-root). Enabled frida via the gadget in **listen mode**
(`on_load:wait` + `adb forward tcp:27042`; frida-python 17.9.1 drives it — note frida 17 removed the
`Java`/`Module.findExportByName` globals, so scripts are native-only: get JNIEnv via
`JNI_GetCreatedJavaVMs`, hook the shared JNI function table). Caught `GetObjectClass(null)` with a
crash-proof `__android_log_write` backtrace:
```
GetObjectClass(NULL) <- libnetflix.so!0xeb0d68 <- nativeGibbonStartup+0xd4
```
Disassembled (r2) the helper at `0xeb0c3c` + resolved its pc-relative string literals →
```
getPackageManager().getPackageInfo("com.netflix.ninja", GET_SIGNATURES /*0x40*/)  -> null
   source file literal: .../dpi/jni/RJni_SignatureCheck.cpp
```
**Root cause:** a SECOND, NATIVE anti-tamper (RJni_SignatureCheck, separate from the DexGuard Java
CertCheck) hardcodes `getPackageInfo("com.netflix.ninja", GET_SIGNATURES)`. Under the renamed clone
that's a CROSS-package query, blocked by Android 11+ package visibility → returns null → the JNI
wrapper (0xea0978) hands back null → `GetObjectClass(null)` → SIGABRT. (Also confirmed it's a
timing-sensitive path — frida overhead alone sometimes let it pass, which first hinted "race".)

**FIX (shipped, no hooking):** CloneAppPatch now injects `<queries><package
android:name="com.netflix.ninja"/></queries>`. That restores visibility so the query returns the
STOCK Netflix PackageInfo — whose signature is the GENUINE Netflix cert — so the native check returns
non-null AND passes. VERIFIED on-device: clean clone (no gadget, no frida) launches, NO crash, runs
foreground stable at ~320MB, Widevine `GenerateKeyRequest` provisioning for `com.netflix.ninja.clone`,
shows in the launcher app list. Both anti-tamper layers now defeated:
DexGuard CertCheck (bytecode patch) + native RJni_SignatureCheck (`<queries>`).

**NEXT:** rebuild the gadget+fix build (listen mode) and use frida on the now-RUNNING clone to dump
the appboot bundle from the Hermes heap (REOPENING.md step 4/5): force a pre-roll, sweep for the
ad-break schema + the empty-break guard (seam B). Open sub-question: does the clone reach login/browse
(so appboot fully loads) or stall at DRM/network — needs a few minutes of observation / a login.

### 2026-08-07 — CAPTURE PATH PROVEN OPEN: appboot JS live in the clone heap

Built the gadget+fix build (listen-mode gadget + `<queries>` fix + CertCheck bypass), attached frida
to the running clone, and scanned RW memory (native `Memory.scanSync`, results via `__android_log_write`
tag APBSCAN). Confirmed the appboot bundle is LIVE as PLAIN JS in-process:
```
marker "nrdp.gibbon": 943 hits  e.g. "nrdp.gibbon.GibbonWasmComponentContext,Re=n().function Pe(e,...t){const r=Re.was..."
marker "appboot":     232 hits  e.g. "appboot.netflix.com;nrdp-cell4.prod.ftl.netflix.com;..."
```
End-to-end the reopening thesis holds: clone runs past BOTH anti-tampers → appboot loads as readable
JS in the Hermes heap → readable in-process. "Not strippable" is fully retired.

Tooling that works (scratchpad nfverify/): `nf-listen2.apk` (gadget+fix, listen mode),
`run-diag.py` (frida-python driver: attach Gadget, load script, resume), `scan-appboot.js`
(native marker scanner). Launch recipe: `am start` MainActivity (blocks at gadget wait) →
`adb forward tcp:27042` → `py run-diag.py <script.js> <hold_s>` → re-`am start` to foreground so
Gibbon keeps a visible Surface.

**NEXT (REOPENING.md step 4/5):**
1. DUMP the appboot JS: scan for `nrdp.gibbon`, walk the containing rw range, dump it to the app
   files dir (run-as-pullable) or stream over frida. Reassemble the minified appboot source.
2. Find the AD-BREAK RESOLVER (seam B): during a real pre-roll (log in first; ad-break markers
   `adBreak`/`interstitial`/`cuePoint` only populate with playback context — the marker scan for
   those returned nothing pre-login). Look for the `0===t.length`/empty-break analogue.
3. Then choose the strip: frida hook (capture) vs a shippable transform (seam A data scrub of the
   decrypted manifest object, or seam B forcing the resolver's empty return).
Open: the clone stalls at DRM provisioning / pre-login; needs a login (or observe whether it reaches
browse) for ad-break context to appear.

### (earlier) RECOMMENDATION — switch to a rooted device/emulator (removes 3 of 4 walls)

The clone route proved the CertCheck bypass works and got into Gibbon startup, but each remaining wall
(native identity now, ESN/MSL/Widevine next) stems from the package RENAME. A **rooted** Android lets
us install the CertCheck-bypassed build directly OVER stock `com.netflix.ninja` (no clone, no rename →
no identity regression) and use frida-server (reads anywhere, no SELinux script-path issue) to dump
appboot in-process. That sidesteps walls #1 (system app), #3 (native identity), and #4 (ESN/MSL).
Needs a rooted device — the Onn `.211` isn't. Options: root the Onn, a rooted spare, or an ARM
Android VM with Widevine. If staying non-root, the only remaining clone path is native RE of
libnetflix.so's nativeGibbonStartup to find + satisfy the package-keyed null (high effort, more walls
likely). SHIPPED assets that survive regardless: the "Disable Netflix CertCheck" + "Clone Netflix"
patches (committed) — both correct and reusable on any device.

---

## 1. What this was

A portability experiment: *does the "native in-process ad-strip toolkit" (built from the Prime
Video work) port to other APKs?* Test target: **Netflix Android TV** (`com.netflix.ninja`,
`13.0.0-25009`, armeabi-v7a). Work committed on branch **`claude/toolkit-cross-apk-compat-ku719r`**
under `experimental/netflix-native-adstrip/`.

## 2. The answer (definitive)

**Netflix's ads cannot be stripped with the toolkit's approach (or DNS, bytecode, or network
MITM).** They are protected by three independent layers, confirmed both statically and empirically:

```
TLS (Cronet/static OpenSSL) → MSL (Message Security Layer, in JS) → appboot RSA/ECDSA signature
```
and delivered as **pure same-host SSAI** (ad served from the same `oca.nflxvideo.net` Open Connect
hosts as content). No separate ad host, no third-party beacon, no blockable plane.

The toolkit "ports in spirit but not in mechanism": its premise is hooking a native plaintext seam,
but Netflix decrypts MSL inside an embedded **Hermes JS engine**, so the plaintext manifest is a
JS-heap object, not a native buffer — and the ad-bearing player JS is signature-locked.

## 3. Architecture map (measured, not guessed)

- **`libnetflix.so`** (84 MB, soname `libandroid_netflix.so`) = the nrdp monolith: **Hermes** JS
  engine + **Gibbon** renderer + static **OpenSSL 3.2.1** + **MSL** (implemented in JS). Application
  subclass `Lcom/netflix/ninja/NetflixService…` → actually `Lcom/netflix/ninja/NetflixApplication;`
  (`extractNativeLibs=false`). No `lib/` in base.apk; native libs are in the `config.armeabi_v7a`
  split.
- **milo** = downloadable JS **networking** layer (HTTP/WS/diskCache/MSL transport). Hash-checked
  (`milo_ignore_hash_errors` bypass exists). Fetched from
  `occ.a.nflxso.net/genc/nrdp/milo/<ver>/milo.prod.js`. **Contains NO ad logic** (it's plumbing).
- **appboot UI app** = the player/UI JS (Gibbon-loaded from `appboot.netflix.com`). **This holds the
  ad-break logic**, and is **RSA/ECDSA signature-verified** against a pubkey baked into
  `libnetflix.so` (`appboot_key`/SPKI/RSASSA). This is the wall.
- **dex** = thin Java shell; no milo loader / JS bridge / integrity class. Every "advert*" string in
  the app is **Bluetooth LE / MDX casting** or Google Ad-ID, NOT video ads (proven by decompiling
  `Lo/getArguments;` = the BLE advertiser agent — see `decompiled/BleAdvertiseAgent.deobfuscated.java`).
- **Empirical capture:** a real 15s pre-roll session hit 18 unique hosts; the ONLY non-Netflix one is
  `sessions.bugsnag.com` (crash telemetry). Ad came from the same OCA hosts as content = SSAI.
  Frequency-capped (~7 movies no ad, then one) → ad decision is server-side/in-manifest.

## 4. Committed artifacts (on the branch)

- `experimental/netflix-native-adstrip/PORTABILITY-ASSESSMENT.md` — the full running assessment
  (§0 toolkit self-check → §3e empirical capture → §8 verdict; also a prior-art survey table).
- `experimental/netflix-native-adstrip/decompiled/BleAdvertiseAgent.deobfuscated.java` — deobfuscated
  BLE agent (proof "advert"==Bluetooth, not video ads).
- **Reusable tooling** (built this session, generic — works for any app in the harness):
  - `testing/scripts/capture.sh` — drive PCAPdroid on the Onn over adb (start/stop/pull/run, `--decrypt`).
  - `testing/scripts/analyze_pcap.py` — DNS+SNI host-footprint report from a pcap; `--ad MM:SS-MM:SS`
    flags AD-ONLY hosts; `--vs ADFREE.pcap` A/B-diffs an ad title against an ad-free one. (needs `scapy`.)
  - `testing/capture-runbook.md` — the timestamped capture + A/B protocol.
  - `netflix` added to `testing/config/apps.conf`.

## 5. Local environment / workflow (user's Windows PC + Onn TV)

- **Onn Android TV** at `adb connect 192.168.12.210:5555`. Developer options + Network debugging on.
- **PCAPdroid** installed on the Onn (`com.emanuelef.remote_capture`); needs its **Control permission**
  granted for headless adb control. PCAP saves to `/sdcard/Download/PCAPdroid/`.
- User has **Wireshark** (used tshark/GUI filters), **AdGuard Premium** (PC), and can rig a MITM.
- **Division of labor:** the cloud session can't reach the LAN/Onn — the user runs captures on their
  PC and uploads pcaps; the session analyzes them. (adb/PCAPdroid = user side; analyze_pcap = here.)
- **Files the user has locally (NOT in repo, big/copyright):** the Netflix `.apkm` and its splits,
  the reassembled `libnetflix.so` (sha256 `b3873f00…`), `milo.debug.js` (5.7 MB), the capture pcaps.
  A fresh cloud session won't have these — re-request from the user if needed.

## 6. If resuming

- Nothing is pending on Netflix — it's closed. Reopen only if Netflix moves ads to a separable plane
  (re-run the §3e capture to check for a new non-Netflix ad host).
- The **capture→analyze pipeline is the real reusable win** — point it at a *softer* target next
  (an app with clean bytecode ad hooks like the repo's working ones). `analyze_pcap.py --vs` is ideal
  for any "which hosts are ad-specific" question.
- Useful Wireshark display filter that nailed it: non-Netflix SNI →
  `tls.handshake.extensions_server_name and not (…contains "nflx" or …contains "netflix")`.
