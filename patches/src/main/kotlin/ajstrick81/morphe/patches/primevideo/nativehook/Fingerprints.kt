package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Application.onCreate — earliest reliable point to load the native hook lib.
//
// We inject System.loadLibrary("pvhook") at the TOP of onCreate so JNI_OnLoad
// runs (and installs the memcpy/memmove hooks) before the first playback
// session builds its native MediaPipelineBackend.
//
// definingClass confirmed 2026-07-23 from a decoded 6.23.23+v15.5.0.70-armv7a
// APK's AndroidManifest.xml (<application android:name="…">):
// com.amazon.primevideo.PrimeVideoApplication. This also matches the class
// used for the manual System.loadLibrary("frida-gadget") smali injection in
// the earlier Frida-gadget recipe (see repo memory), so it's a repeatedly
// confirmed anchor, not a one-off guess. Re-verify on every version bump —
// an onCreate fingerprint anchored to the wrong class silently won't resolve.
//
// If pinning the class proves brittle across updates, switch this to a
// strings/custom predicate that matches the unique onCreate that also calls
// super.onCreate() on an Application — but keep it anchored to exactly one
// method so the loadLibrary isn't injected twice.
//
// NO accessFlags — deliberate (2026-11-07). Morphe compares accessFlags with
// exact integer equality (app.morphe.patcher.Fingerprint: getAccessFlags()
// then if_icmpeq), not a subset test. Measured from each build's smali:
//
//   6.23.23+v15.5.0.70   .method public onCreate()V          PUBLIC       0x1
//   6.24.7+v16.0.0.332   .method public final onCreate()V    PUBLIC|FINAL 0x11
//   6.24.13+v16.0.1.93   .method public final onCreate()V    PUBLIC|FINAL 0x11
//
// So a pinned `listOf(AccessFlags.PUBLIC)` silently fails to resolve on every
// v16 build: loadNativeHookPatch uses methodOrNull, so the loadLibrary call is
// never injected, libpvhook never loads, and the strip is absent with no error
// anywhere. That is the long-standing "v16 doesn't suppress ads" symptom
// recorded in docs/seam-map/overlay.json; the recorded fix (drop accessFlags)
// had never actually been applied to this file.
//
// definingClass + name + empty parameters + returnType V already identify
// exactly one method in this class, so dropping accessFlags costs no precision
// and is drift-tolerant across future public/final churn.
// ─────────────────────────────────────────────────────────────────────────────
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/primevideo/PrimeVideoApplication;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
)
