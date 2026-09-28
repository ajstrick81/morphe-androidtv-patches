package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

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
// ─────────────────────────────────────────────────────────────────────────────
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/primevideo/PrimeVideoApplication;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
