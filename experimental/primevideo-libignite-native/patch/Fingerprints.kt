package ajstrick81.morphe.patches.primevideo.nativehook

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// Application.onCreate — earliest reliable point to load the native hook lib.
//
// We inject System.loadLibrary("pvhook") at the TOP of onCreate so JNI_OnLoad
// runs (and installs the SSL_read/inflate hooks) before the first playback
// session builds its native MediaPipelineBackend.
//
// definingClass TODO: set to the app's real Application subclass. Find it in
// the decoded APK's AndroidManifest.xml → <application android:name="…">.
// On this build it is expected to be one of the Amazon ignition Application
// classes (e.g. Lcom/amazon/ignition/…Application;). Confirm before building —
// an onCreate fingerprint anchored to the wrong class silently won't resolve.
//
// If pinning the class proves brittle across updates, switch this to a
// strings/custom predicate that matches the unique onCreate that also calls
// super.onCreate() on an Application — but keep it anchored to exactly one
// method so the loadLibrary isn't injected twice.
// ─────────────────────────────────────────────────────────────────────────────
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/ignition/IgnitionApplication;", // TODO confirm from manifest
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
