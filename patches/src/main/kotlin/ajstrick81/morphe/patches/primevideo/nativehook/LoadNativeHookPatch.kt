package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.primevideo.misc.extension.primeVideoExtensionPatch
import ajstrick81.morphe.patches.primevideo.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// DEX-side half of the in-process native interception: load libpvhook.so at
// startup so its PLT/GOT hooks on libignite's memcpy/memmove imports install
// before the first playback session. The hook blanks PRS Remote items (movies)
// and empties getVideoAds/regolith ad responses (TV) in place; see
// docs/PRIME_VIDEO_ATV_SYSTEM_DESIGN.md and ../jni/ (experimental/
// primevideo-libignite-native/jni/, production source despite the path).
//
// Three patches cooperate:
//   bundleNativeHookPatch (resource) — writes libpvhook.so into lib/<abi>/
//   primeVideoExtensionPatch (bytecode) — merges the extension DEX that
//       contains NativeHookLoader (reuses the SAME extension module as the ads
//       patch; no new extension is built)
//   loadNativeHookPatch (this) — injects the NativeHookLoader.load() call into
//       Application.onCreate
//
// We call the extension's NativeHookLoader.load() rather than inlining
// System.loadLibrary so the load is wrapped in try/catch + logcat ("fail loud"),
// matching the SkipAdsPatch extension convention. NativeHookLoader.load() has an
// R8 -keep in extensions/proguard-rules.pro, since only injected smali calls it.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val loadNativeHookPatch = bytecodePatch(
    name = "Load native ad-strip hook",
    description = "Loads libpvhook.so at startup to strip ads in-process: blanks Remote (ad) " +
        "items in the PRS intraTitlePlaylist and empties getVideoAds ad responses " +
        "(libignite memcpy/memmove GOT/PLT import hook).",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // The .so must be in lib/<abi>/ before we inject the load call, and the
    // extension DEX (NativeHookLoader) must be merged before we reference it.
    dependsOn(bundleNativeHookPatch, primeVideoExtensionPatch)

    execute {
        // Inject at index 0 of Application.onCreate so JNI_OnLoad runs before
        // any native media pipeline is constructed.
        //
        // Optional (issue #120): methodOrNull so a missing anchor skips rather
        // than aborting the whole patch. The native hook simply won't load on
        // that build (a no-op) and the rest of the patch set still applies; on
        // supported versions this fires as before.
        //
        // Correction (2026-11-07): this comment used to blame a "v16 refactor"
        // for the anchor not resolving. The class never moved —
        // Lcom/amazon/primevideo/PrimeVideoApplication; is still the manifest's
        // android:name and still in classes.dex on 6.24.7 and 6.24.13. What
        // failed was the fingerprint's pinned accessFlags=[PUBLIC] against v16's
        // `public final` onCreate, under Morphe's exact-equality flag compare.
        // So on v16 this methodOrNull was always null and the strip never loaded
        // — silently, which is why v16 looked architecturally out of reach.
        // accessFlags is now dropped (see Fingerprints.kt).
        ApplicationOnCreateFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/primevideo/nativehook/NativeHookLoader;->load()V
            """
        )
    }
}
