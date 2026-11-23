package ajstrick81.morphe.patches.raiplay.debug

// RaiPlay 5.0.1 support, live-variant fix and WebView debug patch contributed by
// Michael Bompani (github.com/michaelbompani), from his fork michaelbompani/morphe-androidtv-patches. Thank you!

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// RaiPlayTvApp.onCreate() — first app code to run, before MainActivity builds
// the WebView that hosts the remote SPA
// (https://www.raiplay.it/appTv/rp2020SmartTV/prod/). v0 is free at index 0
// on 5.0.0 (3 registers) and 5.0.1 (4 registers).
internal object RaiPlayApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lit/rai/raiplay/androidtv/RaiPlayTvApp;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
)
