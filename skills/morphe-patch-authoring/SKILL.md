---
name: morphe-patch-authoring
description: "Write, debug and maintain Morphe bytecode patches for Android apps (Kotlin, smali, fingerprints). Use when creating a new Morphe patch, re-targeting a patch after an app update or R8 rename, fixing a fingerprint that no longer matches, fixing VerifyError or patch-abort failures, or suppressing ads in an Android or Android TV app with Morphe."
license: GPL-3.0
---

# Morphe patch authoring

Morphe (github.com/MorpheApp) is a bytecode patcher for Android APKs. A patch is Kotlin: one or more **fingerprints** that locate methods in the app's dex, and a **bytecodePatch** that edits those methods. This skill covers writing patches that survive app updates.

Start new projects from `MorpheApp/morphe-patches-template`. Put your code under your own package root, never under `app.morphe.*` (that namespace belongs to the upstream libraries you import).

## Workflow

### New patch

1. **Decompile** the exact build you target with apktool. For split bundles (`.apkm`/`.xapk`), use the base plus the ABI split your device runs. Never commit APKs or decompiled sources.
2. **Find the behaviour at the network level first.** A DNS query log filtered by the device's IP (AdGuard Home, Pi-hole) or a packet capture (PCAPdroid) tells you which host delivers what, and when. That narrows the code search a lot. Note hosts the player *needs* (some players fail without the ad SDK's host) before blocking anything.
3. **Classify the mechanism.** For ads there are three families, and each has its own kind of seam:
   - **Client ad SDK** (Google IMA/DAI, etc.): the schedule surfaces as cue points, ad-break lists, an `AdsManager` or a `StreamRequest`.
   - **Server-side stitching (SSAI)**: ads are in the stream; the client only sees manifest markers and ad hosts. Options: request an ad-free variant or fallback URL, strip ad periods, or detect the break and mask it.
   - **Proprietary engine** (native library, JS VM, WebView): find where the schedule crosses into the player and empty it there.
4. **Pick the smallest cut point that leaves playback intact.** Prefer emptying the *schedule* (return an empty list/array, an empty request) over killing a callback. Players often block content start until an ad callback completes, so neutering that callback gives an infinite spinner.
5. **Search the smali.** Grep for SDK API references, log tags and string constants. On big apps (30k+ smali files), build a trigram index (Google `codesearch`: `cindex` once, then `csearch`) instead of grepping the whole tree each time.
6. Write the fingerprint, then the patch, then test on a device.

### App update (drift)

1. Decompile the new build next to the last known-good one.
2. For each fingerprint, check whether the target still matches: class present, same signature, same access flags, anchor strings present. Script this if you maintain more than a couple of apps; it pays off fast.
3. After an R8 rename, find the renamed class by its `.source` line (often preserved) or by method signature plus the strings and SDK references it uses.
4. Diff the patched method bodies between builds. If they're instruction-identical, you usually only need to add the new version as a target.

## Fingerprint rules

- **Match structure and stable references, not obfuscated names.** Good anchors: return type, parameter types, access flags, string constants, and references to third-party SDK APIs that R8 can't rename. Example: a method whose body references `Lcom/google/ads/interactivemedia/v3/api/AdsManager;->start()`.
- **`strings` only looks at the target method's own body**, not its siblings. To pin a method by something elsewhere in its class, use a `custom` lambda guard:

  ```kotlin
  object CuePointsToTimesFingerprint : Fingerprint(
      returnType = "[J",
      parameters = listOf("Ljava/util/List;"),
      accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
      custom = { _, classDef ->
          // Pin to the ad-util class: the one that also builds an IMA AdsRequest.
          classDef.methods.any {
              it.returnType == "Lcom/google/ads/interactivemedia/v3/api/AdsRequest;"
          }
      },
  )
  ```

- **Make sure it's unique.** A loose fingerprint that matches the wrong method patches silently and breaks something unrelated. Add one more constraint until only one method can match.
- **If you must pin an obfuscated name**, scope that patch's compatibility to the one build it was pinned against, and say so in a comment. R8 renames per release.
- **Comment every fingerprint** with the concrete method it hits on the current build, why the match is unique, and why patching it is safe for content.

## Patch rules

- **Use `methodOrNull`** so a missed fingerprint skips that hook instead of aborting the whole patch. Use the eager `.method` only when a miss must be fatal, and then make sure the compatibility list contains only versions where it matches.
- **Don't clear instructions.** `implementation!!.instructions.clear()` throws `UnsupportedOperationException`. Prepend an early return instead:

  ```kotlin
  SomeFingerprint.methodOrNull?.addInstructions(0, "return-void")
  ```

  Where your Morphe utils version provides `returnEarly()` / `returnEarly(false)` / `returnEarly(0)`, prefer it: it emits the verifier-correct return for the method's type.
- **Object returns must satisfy the verifier.** Return a value of the declared type and keep within the method's register count. An empty `long[]`:

  ```smali
  const/4 v0, 0x0
  new-array v0, v0, [J
  return-object v0
  ```

  `VerifyError` with `type=Undefined` or `type=Conflict` usually means a register held the wrong type at the return, or you used a register the method doesn't have.
- **Keep hooks tiny; put logic in an extension.** A hook should be one early return, one constant override, or one `invoke-static` into a compiled Java/Kotlin extension class that holds the real logic (conditions, try/catch, logging). Extension entry points called only from injected smali need a ProGuard/R8 `-keep` rule or they get stripped.
- **Don't copy obfuscated SDK internals between apps.** Obfuscated class names inside a shared SDK (e.g. which `zz*` class implements `StreamRequest`, which constant means VOD vs live) differ per app build. Read them from the target app's own bytecode.
- **Log what you change.** A short log line when a hook fires gives you the fastest drift alarm later: if the ad shows up and your line doesn't, the seam moved.

## Compatibility and naming

- List target versions newest first. Mark untested builds experimental until they've run on a real device, and record what was and wasn't verified in a comment.
- Remove versions the service has cut off server-side rather than leaving them looking supported.
- Users and tooling identify a patch by its `name`. Renaming packages or classes is safe; renaming `name` breaks people's saved selections.
- Keep descriptions honest about scope: say what's removed and what isn't (e.g. "live server-stitched ads are not affected").

## Ethics and scope

- Patches should take away, not add: no calls to servers you control, no analytics or telemetry of your own.
- Anything beyond the patch's stated purpose (app clones, certificate-pinning or TLS overrides, region spoofing) should be a separate, opt-in patch whose description states the trade-off.
- Only patch apps you're entitled to use, and respect the licences of upstream patch projects you borrow from (Morphe and most patch repos are GPL-3.0). Credit forks and contributors whose work you adopt.

## Testing checklist

- Content starts promptly with no spinner or stall.
- No ad pods, no ad markers on the scrubber, no pause at former break positions.
- Seeking across a former break works.
- Pause, resume, next-episode and live channels (if any) still work.
- Your hook's log line appears in logcat; no `VerifyError` or `ClassNotFoundException`.
