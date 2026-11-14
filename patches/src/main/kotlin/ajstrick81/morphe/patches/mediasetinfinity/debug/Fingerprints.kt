package ajstrick81.morphe.patches.mediasetinfinity.debug

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// BaseActivity.onCreate(Bundle) — builds the WebView that hosts the remote SPA.
// The app's own switch, BaseActivity.isWebViewDebuggable(), is deliberately NOT
// flipped: besides enabling inspection it also makes onReceivedSslError proceed
// on EVERY certificate error, which would weaken TLS. Calling
// WebView.setWebContentsDebuggingEnabled(true) directly gives chrome://inspect
// without that side effect. v0 is free at index 0 (8 registers, p0/p1 = v6/v7;
// BaseApplication.onCreate has no free local, hence the Activity).
internal object ShellActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lit/mediaset/mediasetplay/ctv/BaseActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
)
