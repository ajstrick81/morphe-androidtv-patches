package ajstrick81.morphe.patches.mediasetinfinity.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// Mediaset Infinity Android TV (it.mediaset.infinitytv) — ad seam.
//
// Autopsy of 8.1.13: the APK is a thin, un-obfuscated Kotlin SHELL
// (it.mediaset.mediasetplay.ctv.*, ~130 classes). It hosts one WebView that
// loads the remote "ctv-lng" SPA
//   https://static1.mediasetplay.mediaset.it/app/ctv-lng/androidtv/bootstrap.js
// and exposes two JS bridges:
//   ISAndroidTVBridge (CTVJavascriptBridge)   — device info, lifecycle, ad ID
//   ISExoPlayerBridge (Media3JavascriptBridge) — a "dumb" media3/ExoPlayer the
//     SPA drives with prepare(url, mime, drm…)/seekTo/setPlayWhenReady.
//
// There is NO ad code in the APK: no Google IMA, no FreeWheel/Smartclip SDK, no
// ad classes, no ad strings (only AdvertisingIdHelper, which hands the ad ID to
// JS). Ad decisioning, scheduling and UI all live in the remote SPA. So unlike
// RaiPlay (native parseAdContext seam) there is nothing native to null out —
// the only place the shell can see ads is the SPA's own network traffic.
//
// SEAM: the shell's single WebViewClient (BaseActivity.getClient() → anonymous
// BaseActivity$getClient$1) already overrides shouldInterceptRequest (to serve
// bundled assets). Every XHR/fetch/script request the SPA makes passes through
// it, so the ad-decision call can be answered locally before it leaves the TV.
//
// Matched structurally rather than by the anonymous class's `$1` suffix (which
// renumbers when Mediaset adds another anonymous class): the method named
// shouldInterceptRequest(WebView, WebResourceRequest) in an app-package class
// that extends android.webkit.WebViewClient. Exactly one such class on 8.1.13.
// ─────────────────────────────────────────────────────────────────────────────
internal object ShellWebClientInterceptFingerprint : Fingerprint(
    name = "shouldInterceptRequest",
    returnType = "Landroid/webkit/WebResourceResponse;",
    parameters = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebResourceRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    custom = { _, classDef ->
        classDef.type.startsWith("Lit/mediaset/mediasetplay/ctv/") &&
            classDef.superclass == "Landroid/webkit/WebViewClient;"
    },
)
