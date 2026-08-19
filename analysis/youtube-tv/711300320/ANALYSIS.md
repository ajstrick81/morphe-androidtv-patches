# YouTube for Android TV 7.11.300 (711300320) — APK Analysis

**Package:** `com.google.android.youtube.tv`
**Version:** 7.11.300 (versionCode `711300320`)
**Source bundle:** `com.google.android.youtube.tv_7.11.300-711300320_1arch_..._apkmirror.com.apkm`
**Analyzed split:** `base.apk`
- SHA256: `cdd3f1f376a72e15a67fc3fa1d511a69706fa78a8d73f3829f54adce5c3fe24b`
- Size: 11.57 MB
- minSdk 24 / targetSdk 36 / compileSdk 37

> The `.apkm` is a single-arch (`1arch`) bundle: `base.apk` (11.5 MB) +
> `split_config.armeabi_v7a.apk` (66.9 MB). The split is **32-bit ARM only**
> (`armeabi_v7a`, no `arm64_v8a`) and carries the native Cobalt engine
> (`libcobalt.so`). Only `base.apk` was pulled in for this pass.

---

## Headline finding: this is a Cobalt/COAT app, not a native leanback app

The YouTube TV client is **not** a conventional native Android app whose UI and
player live in smali. It is a **Cobalt "COAT" (Cobalt on Android TV) shell** that
loads the YouTube TV **web app from the network at runtime** and runs it inside
Google's native Cobalt/Starboard HTML5+JS engine.

Evidence:
- `assets/cobalt_shell.pak` (Chromium/Cobalt resource pak), `assets/icudtl.dat`,
  `dexopt/baseline.prof` — Chromium/Cobalt runtime assets.
- Dex classes: `dev.cobalt.coat.CobaltActivity`, `dev.cobalt.coat.StarboardBridge`,
  `dev.cobalt.media.MediaCodecBridge`, `dev.cobalt.media.MediaDrmBridge`,
  `dev.cobalt.shell.Shell*`, plus a bundled `cobalt.org.chromium.*` base layer.
- Manifest `MainActivity` meta-data:
  - `cobalt.APP_URL = https://www.youtube.com/tv`
  - `cobalt.ENABLE_SPLASH_SCREEN = true`
  - `android.app.lib_name = cobalt`  → native engine is `libcobalt.so` (in the ABI split)
- **No JS/player bundled in base.apk** — no `*.js`, and the `.pak` contains no
  `adplacement` / `get_video_info` / `player_response` strings. The entire UI,
  player, and **ad logic are downloaded from `youtube.com/tv`** and executed by
  the native engine.

### Why this matters for patching
The phone/tablet YouTube app is patched (ReVanced-style) by editing **smali** —
the ad-serving and player code ships *inside* the APK. **That approach does not
apply here.** In this TV build:
- The dex is just a **bootstrap shell** (Cobalt bridge, GMS, phenotype feature
  flags, billing, cast, TV recommendations). ~10,400 classes total, but none of
  it is the video/ad pipeline.
- The ads are enforced **server-side + in remotely-loaded JS + in the native
  `libcobalt.so` media path**. There is no local ad-injection function to no-op.

This is exactly the "wall that looks impenetrable" the project philosophy warns
about — so here are the real vectors that remain, ranked by leverage.

---

## Patch surface #1 — the Cobalt startup URL (highest leverage)

`CobaltActivity` builds the engine command line and, when no `--url=` switch is
supplied via the launch intent, reads `cobalt.APP_URL` from the manifest
meta-data and injects `--url=<that value>` (`CobaltActivity.smali`, ~L998–L1062):

```
const-string v4, "--url="
...
const-string v2, "cobalt.APP_URL"        # manifest meta-data key
invoke-virtual {p0, v2}, Landroid/os/Bundle;->getString(...)
invoke-virtual {v4, p0}, Ljava/lang/String;->concat(...)   # "--url=" + APP_URL
invoke-virtual {v1, p0}, Ljava/util/ArrayList;->add(...)   # append to arg list
```

It also honors a real `--url=` **command-line switch** already present in the
args (`cobalt_org_chromium_base_CommandLine_*` JNI, `mStartupUrl` field at L50,
set at L4345). So the boot URL is overridable in two ways:

- **Repoint `cobalt.APP_URL`** in `AndroidManifest.xml` (e.g. to a proxy that
  serves a modified TV web app, or an ad-filtering front-end). Simplest,
  most durable patch point — one manifest string.
- **Inject a `--url=` switch** at launch. `MainActivity`/`StandalonePlayerActivity`
  already read an intent URL (`getIntentUrlAsString`, L1430), so a launch-intent
  or a small smali hook can override the startup URL without touching the engine.

This is the cleanest lever: everything the app shows comes from that URL, so
controlling it controls the whole experience.

## Patch surface #2 — network layer (proxy / DNS / hosts)

Because the client streams its UI and video from Google endpoints, an
**on-device or network-level filtering proxy** (DNS sinkhole, MITM proxy that
rewrites the TV web app, or a modified `--url` endpoint) is the most robust
route to ad suppression on this architecture — and it survives app updates,
unlike a resigned APK. Note Cobalt has its own net stack
(`cobalt.org.chromium.net.*`), so system-wide interception is more reliable than
anything scoped to a Java `WebView`.

## Patch surface #3 — Phenotype feature flags (secondary)

`assets/phenotype/*.binarypb` + the `PhenotypeMetadataHolderService` wiring are
Google's server-driven flag system. Flags are fetched from GMS, not authoritative
locally, so this is a weak standalone lever — but worth watching if a flag gates
an ad behavior we can force.

---

## Anti-tamper / signing notes

- `META-INF/APKMIRRO.{SF,RSA}` — the APKMirror-side signature (re-signed for
  distribution); plus a Play **source stamp** (`stamp-cert-sha256`,
  `com.android.stamp.*` meta-data). Standard resign will invalidate the source
  stamp — expected, not a blocker for sideload.
- `android:extractNativeLibs="false"` — native libs load from within the split;
  keep alignment intact when repackaging.
- `com.android.vending.splits.required = true` + `requiredSplitTypes="base__abi"`
  — the app **requires its ABI split installed**; a bare `base.apk` won't run.
  Any install/patch must ship base + the `armeabi_v7a` split together (merge to a
  standalone APK, or install as a split set).
- GMS **DroidGuard** client is present (`...droidguardclient...` phenotype/registration)
  — attestation plumbing exists; relevant only if we later touch integrity paths.

---

## Recommended next steps

1. **Pull the `armeabi_v7a` split** and inspect `libcobalt.so` for the media/DRM
   and net paths (Widevine via `MediaDrmBridge`, ad-stitch handling). That's where
   any *in-engine* enforcement lives.
2. **Prototype the `--url` repoint** against a filtering front-end for
   `youtube.com/tv` — validate whether the TV web app renders and plays when
   served through a proxy. This is the fastest path to a working ad-reduced client.
3. Decide the delivery model: resigned split-set sideload vs. a
   network/DNS-based solution that needs no repackaging (and no re-signing on
   every app update).

*Generated from static analysis of `base.apk`; the ABI split has not yet been examined.*
