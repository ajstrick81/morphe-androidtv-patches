package ajstrick81.morphe.patches.mediaset.ads

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Mediaset Infinity Android TV (it.mediaset.infinitytv) — ad seam.
//
// Recon of 8.1.13 (#217): the APK is a thin shell. BaseActivity hosts a WebView
// that loads Mediaset's remote React TV app
// (static1.mediasetplay.mediaset.it/app/ctv-lng/androidtv/bootstrap.js); video is
// played by a native media3 ExoPlayer driven over Media3JavascriptBridge. There is
// NO ad SDK in the Java code. Ad decisions are made by the web app per play
// request (advData.engine = FREEWHEEL or IMA):
//   • FreeWheel ad calls to *.fwmrm.net (config: freewheel.serverUrl 7cf6f.v.fwmrm.net)
//   • IMA VMAP tags from pubads.g.doubleclick.net (optionally wrapped by SpringServe)
//   • pause / welcome display ads from pubads.g.doubleclick.net/gampad/adx
// Only once the ad call returns does the web app hand ad media to the native player.
//
// Every request the web app makes goes through the shell's own
// WebViewClient.shouldInterceptRequest (BaseActivity$getClient$1), which already
// exists to serve local assets. Answering the ad hosts there with an empty
// response stops the ad decision itself, so no ad media ever reaches the player.
// ─────────────────────────────────────────────────────────────────────────────

// The shell's WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest).
// The app's own classes are not obfuscated; pinned to the ctv package so it can't
// match a WebViewClient from a library (androidx.webkit, Sentry, ...).
object ShouldInterceptRequestFingerprint : Fingerprint(
    name = "shouldInterceptRequest",
    returnType = "Landroid/webkit/WebResourceResponse;",
    parameters = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebResourceRequest;"),
    custom = { _, classDef ->
        classDef.type.startsWith("Lit/mediaset/mediasetplay/ctv/") &&
            classDef.superclass == "Landroid/webkit/WebViewClient;"
    },
)
