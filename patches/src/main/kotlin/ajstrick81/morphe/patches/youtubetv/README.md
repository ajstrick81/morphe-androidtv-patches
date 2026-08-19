# YouTube for Android TV — patches

Target: `com.google.android.youtube.tv` **7.11.300** (versionCode 711300320),
single-arch armeabi-v7a. Full reverse-engineering write-up lives in
`analysis/youtube-tv/711300320/`.

## Why these patches look different from a normal app

YouTube for Android TV is a **Chromium-based Cobalt shell** that loads the
leanback web app from `https://www.youtube.com/tv` at runtime. The UI, player,
and ad logic are served from the network and run in native `libchrobalt.so` —
**none of it is bundled smali**. So the classic "find the ad method and stub it"
approach has nothing local to stub.

The leverage instead is the **InnerTube client context** the app sends with every
request. YouTube does not serve video ads to the **Android Automotive** OS, and
the app reports its OS from a single cached feature check.

## Patches

| Patch | File | What it does |
|-------|------|--------------|
| **Client context hook** | `misc/contexthook/ClientContextHookPatch.kt` | Forces the automotive-feature check (`Lfam;->c(Landroid/content/Context;)Z`) to return `true`, so the InnerTube client context reports OS = **Android Automotive**. |
| **Skip ads** | `ads/SkipAdsPatch.kt` | User-facing entry; depends on the context hook. No body of its own — suppression is entirely upstream via the reported OS name. |

### Fingerprint resilience
The obfuscated names drift across versions (`Leyn`→`Lfam` from 7.05.301 to
7.11.300; builder `Lffr`→`Lfht`). `misc/contexthook/Fingerprints.kt` therefore
anchors on the **stable** shape — the `"android.hardware.type.automotive"` string,
return type `Z`, and the `c(Context)` signature — not the renamed class. Re-verify
against a fresh `base.apk` on each app bump.

## Build & test (needs the Morphe registry token)

The `app.morphe.patches` Gradle plugin is hosted on GitHub Packages
(`maven.pkg.github.com/MorpheApp/registry`) and requires credentials:

```bash
# gradle.properties or env:
#   gpr.user=<github-username>   gpr.key=<PAT with read:packages>
# or GITHUB_ACTOR / GITHUB_TOKEN
./gradlew :patches:compileKotlin        # type-check
./gradlew :patches:build                # build the patch bundle
./gradlew :patches:generatePatchesList  # refresh patches-list.json
```

Apply with the Morphe patcher/Manager against the 7.11.300 `base.apk`, then
**re-sign and install the split set together** (the app sets
`splits.required=true`, `requiredSplitTypes=base__abi`):

```bash
adb install-multiple base.patched.apk split_config.armeabi_v7a.apk
```

### On-device verification
1. Sign in, play any video that normally shows a pre-roll → no video ad.
2. Sanity-check playback and DRM still work (the hook only changes the reported
   OS name; `googlevideo.com` + Widevine traffic is untouched).

## Credit
The Android Automotive context approach was first demonstrated for 7.05.301 in the
community fork `andersonlucasg3/morphe-androidtv-patches`. Here it is re-verified
and retargeted for 7.11.300.
