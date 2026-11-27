---
name: "morphe-atv-patch"
description: "Use when writing, fixing or re-targeting a Morphe patch in ajstrick81/morphe-androidtv-patches: new app version, broken fingerprint, new ad seam, or a new Android TV app."
---

# Morphe Android TV patch workflow

For work in `ajstrick81/morphe-androidtv-patches` (Morphe patcher 1.3.x, Kotlin, GPL-3.0). Read `.claude/CLAUDE.md` first: it holds the project philosophy (growth mindset, ownership) and repo conventions, and it wins over this skill if they ever disagree.

## Pick the path

1. **App updated, patch broke or needs a new target** → Path A (drift).
2. **Same app, new ad surface** (pause ads, live breaks, overlay) → Path B, starting at step B2.
3. **New app** → Path B from the top.

## Path A: version drift

1. Decompile the new build with apktool (the `.apkm` bundle, Android TV build, armeabi-v7a split). Never commit APKs or `decompiled/`.
2. Run the drift checker before touching code:
   `node scripts/seam-map/check.mjs <app> <apktool-dir>`
   If a class is `CLASS_MISSING` after an R8 rename, rerun with `--deep --ref <last-good-apktool-dir>`; the renamed class keeps its `.source` file name and ranks first.
3. Read each status:
   - `OK` on every seam → usually zero code changes; only add the `AppTarget`.
   - `SIGNATURE_CHANGED` / `FLAGS_CHANGED` / `STRINGS_MISSING` → re-anchor the fingerprint on something stable (see Fingerprint rules).
   - `UNVERIFIABLE` → custom matcher; confirm by hand with `scripts/smali-search/esm.sh` (`esm index <dir>` once, then `esm code '<regex>' <dir>`).
4. Diff the patched method against the last good build. Instruction-identical bodies are worth stating in the target comment.
5. Add the target (see Compatibility rules), build, device-test, then update docs (see Finish).

## Path B: new seam or new app

1. **Classify the engine** using `docs/SEAM_MAP.md` → Engines: Google IMA/DAI client SDK, manifest SSAI, or proprietary native/JS. Seams usually port within a family (Fox One is Tubi-class; RTÉ followed the IMA playbook), so read the closest sibling's `Fingerprints.kt` first.
2. **Find delivery before code.** AdGuard Home query log filtered by the TV's IP is the highest-value diagnostic; it beats logcat for WebView traffic. PCAPdroid confirms which host serves the ad. Never run AGH and AGP at the same time. Note load-bearing hosts (e.g. `imasdk.googleapis.com` for Paramount+) before blocking anything.
3. **Map the four layers** for the seam: delivery → code seam (what the fingerprint matches) → patch evidence (the log line our code prints when it acts) → device signature (what logcat shows when an ad really plays).
4. **Choose the cut point.** Prefer emptying the ad *schedule* (cue points → empty `long[]`, insertion points → empty list, empty IMA `StreamRequest`) over killing a callback. Content start is often gated on ad callbacks (RTÉ v2 and Paramount hung on an infinite spinner when `onAdsManagerLoaded` was neutered). Server-stitched live SSAI usually cannot be removed client-side: mask it (slate + mute, see `docs/LIVE_SPORTS_AD_SLATE_PLAYBOOK.md`) or leave it and say so in the description.
5. Write `Fingerprints.kt` and the patch, following the rules below. Logic beyond a one-line hook goes in a Java extension (`extensions/extension/src/main/java/ajstrick81/morphe/extension/<app>/`) with a `-keep` rule in `extensions/proguard-rules.pro` if only smali calls it.

## Fingerprint rules

- Match **structure and stable references**, not obfuscated names: return type, parameter types, access flags, and references to SDK APIs that R8 can't rename (e.g. `Lcom/google/ads/interactivemedia/v3/api/AdsManager;->start()`).
- The `strings` parameter only scopes to the target method body, not sibling methods. To pin a method by something elsewhere in its class, use a `custom = { method, classDef -> ... }` guard (e.g. "the class that also has a method returning `AdsRequest`").
- Avoid pinning an R8 name (`Lv8/x;->y`). If you must, give that patch its own pinned `Compatibility` listing only that build, with a comment saying why (see `DISNEY_PLUS_TV_HOUSEHOLD_PINNED`).
- Comment each fingerprint with: the hook number, the concrete method on the current build, why it's unique, and why patching it is content-safe. Follow `rte/ads/Fingerprints.kt` as the house style.

## Patch rules

- Use `methodOrNull?.` so a future miss degrades gracefully. The eager `.method` aborts the whole patch on a miss (the cause of #289); use it only when a miss must be fatal.
- `implementation!!.instructions.clear()` throws `UnsupportedOperationException`. To neuter, prepend: `addInstructions(0, "return-void")`, or prefer Morphe's `returnEarly()` / `returnEarly(false)` / `returnEarly(0)`, which produce verifier-correct returns per type.
- Hand-written smali that returns objects must be verifier-correct for the declared return type (e.g. `const/4 v0, 0x0` / `new-array v0, v0, [J` / `return-object v0` for an empty `long[]`). Check register count.
- IMA empty-request trick: the `StreamRequest` impl class and the VOD/Live type constants are obfuscated per app (MLB: `zzdm` + `zzafs->zzd` VOD; `zzcx` was the pause-ad class). Verify from the app's own `createVodStreamRequest` bytecode, never from another app.
- Patch `name` is the user-facing identity; renaming it breaks users. Packages can move freely. All code lives under `ajstrick81.morphe.patches.<app>`; never add code under `app.morphe.*`.
- Description must be honest about scope (what's removed, what isn't, e.g. "Live DAI is not affected"). Anything beyond ad removal (clones, household prompts, origin blocks, TLS/cert overrides) is opt-in and states its trade-off. No project-owned servers, analytics or telemetry.
- Emit a patch-evidence log line where practical, so drift shows up as "evidence quiet, device signature present".

## Compatibility rules

- Targets go in the app's `shared/Constants.kt` or `shared/compat/AppCompatibilities.kt`, **newest first**.
- A new build is `AppTarget("<ver>", isExperimental = true)` until device-tested. The comment above it records: device (onn 4K Plus, armeabi-v7a, unless a user tested elsewhere, credit them), date, what was verified, and what was not.
- Remove builds the service has cut off server-side rather than leaving them listed (Disney #302).
- If one patch misbehaves on a build the others support, give that patch its own `Compatibility` excluding the build (see `HBO_TV_ORIGIN_BLOCK_SAFE`).

## Build and test

- Local Gradle needs a GitHub Packages token (`gpr.user`/`gpr.key`). Without one, push the branch and let CI build.
- Adam's ADB runs from Windows Command Prompt: no bash `$()`, get PIDs in a separate command. Write commands that work there.
- Install path users follow: `.apkm`, Morphe Manager, "Optimize for device architecture" off.
- On device, confirm: content starts promptly, no ad pods, no scrubber markers, no stall at break positions, pause behaviour, and that the evidence line appears in logcat.

## Finish

1. Update `docs/seam-map/overlay.json` (mechanism notes, drift history), then regenerate: `node scripts/seam-map/extract.mjs && node scripts/seam-map/render.mjs`. Don't hand-edit `SEAM_MAP.md`.
2. Update the README status table row (version links to the exact Android TV APKMirror release, date) and the app's `docs/<APP>_ANNOUNCEMENT.md` if it has one. Archive superseded Reddit posts under `docs/archive/` rather than deleting.
3. Research and failed experiments go to `experimental/<topic>/README.md` or `docs/analysis/<app>/`, recording what was learned.
4. Commit: short-lived branch, PR into `main`. `feat`/`fix` cut a release; use `docs`/`chore`/`experiment` when it shouldn't. Give Adam **complete file replacements, not snippets**, and land all files of a change in **one commit** so CI never sees a half-applied state.
5. Credit outside contributors and forks in the commit, the target comment and the announcement (e.g. the semicoloncolonel fork for Pluto v17).