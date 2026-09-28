# YouTube for Android TV 7.11.300 — Patch Strategy

Follow-on to `ANALYSIS.md`. This app is a **Cobalt/COAT shell** that loads
YouTube's leanback web app (`https://www.youtube.com/tv`) at runtime and runs it
in native `libcobalt.so`. There is no local ad code to no-op, so the leverage is
in **what URL the shell loads** and **what the network returns**.

---

## Startup-URL flow (verified in `CobaltActivity.smali`)

1. **Arg assembly** (~L998–L1062): the engine command line is built. If the launch
   intent didn't already supply a `--url=` switch, the app reads the manifest
   meta-data `cobalt.APP_URL` and appends `--url=<APP_URL>` to the arg list.
   In this build `cobalt.APP_URL = https://www.youtube.com/tv`.
2. **Startup URL extraction** (~L4298–L4345): it locates the `--url=` arg, takes
   the substring after it, and stores it in `mStartupUrl`.
3. **Guard** (~L4350–L4408): if `mStartupUrl` is non-empty:
   ```
   if (mStartupUrl.startsWith("https://www.youtube.com/tv")) {
       // normal path
   } else {
       Log("Non-Youtube startup URL detected.");
       startupGuard.disarm();   // Llnu;->disarm()
   }
   // proceeds to load mStartupUrl either way
   ```
   **The guard does not block or rewrite a non-YouTube URL.** It only logs and
   disarms a startup watchdog (which would otherwise kill the process if
   YouTube-specific startup milestones weren't reached). Net effect: **arbitrary
   startup URLs are allowed to load.**

### Override points, in order of preference

| # | Method | What to change | Repack? | Notes |
|---|--------|----------------|---------|-------|
| 1 | **Network MITM, same host** | Nothing in APK | No | Transparent proxy for `www.youtube.com` that strips/rewrites ad responses. `startsWith` guard passes (host unchanged); watchdog never disarmed. Most robust, survives app updates. |
| 2 | **Manifest `cobalt.APP_URL`** | One string in `AndroidManifest.xml` → point at a proxy front-end | Yes (resign) | Read directly at L1038; simplest in-APK patch. If pointing off-host, the guard just logs + disarms — still loads. |
| 3 | **`--url=` launch switch / `mJavaSwitches`** | Inject switch at launch or via the switches map | Maybe | `getJavaSwitches()` returns `mJavaSwitches`; a small smali hook can seed `--url`. Good for A/B testing URLs without editing the manifest each time. |

---

## Recommended path: network-layer filtering (option 1)

Because the entire UI + player + ad pipeline is served from the network and
executed in native Cobalt (its own Chromium net stack, `cobalt.org.chromium.net.*`
— **not** an Android `WebView`), the cleanest, update-proof approach is a
**filtering proxy / DNS layer** rather than a resigned APK:

- Keep the host as `www.youtube.com` so the `startsWith` guard passes untouched.
- Filter at the response level (the leanback app's ad-placement / player-response
  payloads) the same way network-side YouTube ad blockers work.
- No re-signing, no source-stamp breakage, no re-patching on every app update.

This fits the project philosophy: the "wall" (can't patch smali) isn't a wall —
it just relocates the fight to the network boundary, where this architecture is
actually *more* exposed than the phone app.

## In-APK repoint (option 2) — when you want a self-contained build

1. Full-decode with `apktool d` (sources included).
2. Edit `AndroidManifest.xml`:
   `cobalt.APP_URL` → your front-end (a proxy that serves a filtered
   `youtube.com/tv`).
3. `apktool b`, then **zipalign + re-sign** the whole split set (base +
   `armeabi_v7a`) with a consistent key.
4. Install as a **split set** — the manifest sets
   `com.android.vending.splits.required=true` and
   `requiredSplitTypes="base__abi"`, so `base.apk` alone will not install/run.

---

## Open questions for the native split (`libcobalt.so`, pending upload)

The `armeabi_v7a` split has not been examined yet. It governs whether **in-engine**
enforcement can be touched:

- **Widevine / DRM** via `dev.cobalt.media.MediaDrmBridge` — playback licensing.
- **Media pipeline** via `MediaCodecBridge` — where any client-side ad-stitch /
  SSAI handling would live.
- Whether Cobalt honors a proxy/`--proxy-server` switch natively (would make
  option 1 trivial to wire on-device).

These determine if a purely on-device solution (no external proxy box) is viable.

---

## Anti-tamper recap (from ANALYSIS.md)

- Play **source stamp** present → any resign invalidates it (fine for sideload).
- GMS **DroidGuard** attestation plumbing present → relevant only if we touch
  integrity/attestation paths, not for URL repoint or network filtering.
- `extractNativeLibs=false` → preserve alignment when repackaging the split.
