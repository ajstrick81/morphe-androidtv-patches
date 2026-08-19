# YouTube for Android TV 7.11.300 — Test-Day Runbook (Onn device)

Everything needed to build, install, and verify the ad-suppression patch on the
Walmart **Onn Google TV** box. Follow top to bottom.

---

## 0. Pre-flight checklist (have these ready)

- [ ] **The exact APK we patched.** The `.apkm` bundle for
      `com.google.android.youtube.tv` **7.11.300 (711300320)**, armeabi-v7a —
      the same one already in the repo at
      `analysis/youtube-tv/711300320/` (base + split). Re-download from APKMirror
      if needed; confirm SHA256 of `base.apk` = `cdd3f1f3…5c3fe24b`.
- [ ] **Morphe Manager** installed (on the Onn itself, or on a PC/Android that
      can build). This is the standard apply path — it ingests the `.apkm` and
      handles the split + signing + install.
- [ ] **The patch bundle** with the new `youtubetv` patches. Two ways to get it:
      - **Build locally** (needs a GitHub token with `read:packages` for the
        MorpheApp registry) — see §1; or
      - **Cut a release** of this branch so `patches-x.y.z.mpp` includes it and
        Manager can download it.
- [ ] **Onn in developer mode:** Settings → System → About → click *Android TV
      OS build* 7× → back → Developer options → enable **USB debugging** (and
      **Network debugging** if going over Wi-Fi).
- [ ] **adb** on your laptop, Onn on the same network / USB.

> **Arch note:** our build is armeabi-v7a (32-bit). Onn Google TV boxes are
> arm64, which runs 32-bit apps fine — the v7a build will install and run. If the
> Play Store later offers an arm64 split you want instead, we'd re-pull that
> bundle, but v7a is correct for this test.

---

## 1. Build the patch bundle (`.mpp`) — machine with the token

```bash
git fetch origin claude/powershell-atv-youtube-upload-0m7mg2
git checkout claude/powershell-atv-youtube-upload-0m7mg2

# credentials for the MorpheApp GitHub Packages registry:
#   ~/.gradle/gradle.properties →  gpr.user=<gh-user>   gpr.key=<PAT read:packages>
#   (or export GITHUB_ACTOR / GITHUB_TOKEN)

./gradlew :patches:compileKotlin      # type-check the new Kotlin first
./gradlew :patches:build              # produces the patch bundle
./gradlew :patches:generatePatchesList
```

- **If `compileKotlin` errors:** copy the error here and I'll fix the Kotlin.
  (The patches mirror the compiling `vix`/`netflix` patches and the fork's
  working version, so this should be clean.)
- The build output bundle is what you import into Morphe Manager.

---

## 2. Apply the patch (Morphe Manager)

1. Open Morphe Manager → import the local patch bundle from §1 (or select the
   released version once it lists `YouTube Android TV`).
2. Select the **7.11.300 `.apkm`**.
3. Enable **Skip ads** (it pulls in *Client context hook* automatically).
4. Apply. Manager patches `base.apk`, re-signs base + split with one key, and
   installs.

### Manual fallback (if Manager won't handle the split on TV)
Patch + sign both parts with the same key, then:
```bash
adb connect <onn-ip>:5555          # if over network
adb install-multiple base.patched.apk split_config.armeabi_v7a.apk
```

---

## 3. Verify on-device

1. **Confirm install/version:**
   ```bash
   adb shell dumpsys package com.google.android.youtube.tv | grep versionName
   ```
   → `7.11.300`.
2. **Sign in.** YouTube TV uses the pairing-code flow (open the app → it shows a
   code → link at `youtube.com/activate`). This is OAuth device-flow, **not**
   signature-bound, so re-signing should not block sign-in.
3. **Ad test (the real check):** play a video that reliably shows a pre-roll
   (a big music video or a long popular upload). **Expected: no video ad, straight
   to content.** Try 3–4 videos incl. one long enough for a mid-roll.
4. **Playback/DRM sanity:** confirm normal videos play at full quality and seek
   works — the hook only changes the reported OS name; `googlevideo.com` +
   Widevine traffic is untouched, so playback must be unaffected.

### Optional: confirm the hook actually fired
While reproducing, capture logs:
```bash
adb logcat -v time | grep -iE "automotive|innertube|clientContext|Morphe"
```
The definitive signal is empirical (pre-roll gone). If ads still show, that tells
us the OS-name path differs on this build — jump to §5.

---

## 4. Rollback (clean slate)

```bash
adb uninstall com.google.android.youtube.tv
# reinstall stock if it was a system app on the Onn, or from the unpatched .apkm:
adb install-multiple base.apk split_config.armeabi_v7a.apk
```
The Onn's preinstalled YouTube may be a system app — if so, our sideload installs
alongside/over the user build; note whether we're updating the system app or
installing fresh, and keep the stock `.apkm` handy to restore.

---

## 5. If it doesn't work — fast triage (paste me the result)

| Symptom | Likely cause | Next move |
|--------|--------------|-----------|
| **compileKotlin fails** | API drift in patcher 1.3.2 | paste the error; I'll adjust the Kotlin |
| **Patch "not applicable" / fingerprint miss** | The Play/Onn build isn't byte-identical to our APKMirror pull | pull `base.apk` off the Onn (`adb shell pm path …` → `adb pull`) and send it; I'll re-anchor the fingerprint |
| **Installs, but ads still play** | OS-name gate differs, or ads come via a path automotive doesn't suppress | grab a logcat during a pre-roll + the InnerTube response; we add the JS-injection fallback (`STOCK_PATCH_FEASIBILITY.md`) |
| **Sign-in fails after re-sign** | Google validates signature for this build | add the fork's **GMS/microG** patch (package rename) — kept out of v1 on purpose |
| **App crashes on launch** | verifier rejected the injected return, or split mismatch | send `adb logcat` around launch; check both APKs signed with the *same* key |

---

## What's already done (so test day is just build → install → watch)

- Target locked to `7.11.300` and fingerprint **verified against the shipped
  bytecode** (`Lfam;->c(Context)Z` → caller `Lfht;` sets OS "Android Automotive").
- Patches written to the repo's own API idioms (`patches/.../youtubetv/`).
- Full architecture + feasibility notes in `analysis/youtube-tv/711300320/`.
- Approach chosen (context spoof) is a single verifier-safe instruction — lowest
  risk of the options we scoped.
