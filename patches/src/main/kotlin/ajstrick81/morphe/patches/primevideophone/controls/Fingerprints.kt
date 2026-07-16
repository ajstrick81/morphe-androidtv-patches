package ajstrick81.morphe.patches.primevideophone.controls

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// PlayerActivity.onResume() — install hook for the D-pad key interceptor.
// classes2.dex / smali/com/amazon/avod/app/PlayerActivity.smali
//
// PlayerActivity is a plain Activity subclass (ConfigurationAwareActivity ->
// ... -> Activity). Its player transport UI is com.amazon.video.sdk.uiplayerv2
// rendered in Jetpack Compose and built for touch — no view is D-pad focusable,
// and the Activity overrides neither dispatchKeyEvent nor onKeyDown, so remote
// directional keys go nowhere during playback.
//
// onResume (rather than onCreate) is chosen as the install point because the
// install is idempotent (guards against re-wrapping) and onResume re-asserts
// the wrapping Window.Callback on every foreground transition, surviving any
// callback the app sets after onCreate.
// ─────────────────────────────────────────────────────────────────────────────
object PlayerActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/avod/app/PlayerActivity;",
    name = "onResume",
    parameters = emptyList(),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
