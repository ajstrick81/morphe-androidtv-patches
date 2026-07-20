package ajstrick81.morphe.patches.tubi.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// Hook 1 — FoxImaAdListeners.adEventListener_delegate$lambda$10$lambda$9
object FoxImaAdEventListenerFingerprint : Fingerprint(
    definingClass = "Lcom/fox/android/video/player/api/ima/listeners/FoxImaAdListeners;",
    name = "adEventListener_delegate\$lambda\$10\$lambda\$9",
    strings = listOf("adEvent"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL)
)

// Hook 2 — FoxImaAdListeners.adsLoadedListener_delegate$lambda$4$lambda$3
object FoxImaAdsLoadedListenerFingerprint : Fingerprint(
    definingClass = "Lcom/fox/android/video/player/api/ima/listeners/FoxImaAdListeners;",
    name = "adsLoadedListener_delegate\$lambda\$4\$lambda\$3",
    strings = listOf("onAdsManagerLoaded"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL)
)

// Hook 3 — FoxPlayer.clearVodAds()
object FoxPlayerClearVodAdsFingerprint : Fingerprint(
    definingClass = "Lcom/fox/android/video/player/FoxPlayer;",
    name = "clearVodAds"
)

// Hook 4 — ImagePauseAds.l(VideoApi, long)
object TubiPauseAdsFingerprint : Fingerprint(
    definingClass = "Lcom/tubitv/features/player/presenters/pauseads/ImagePauseAds;",
    name = "l",
    parameters = listOf(
        "Lcom/tubitv/core/api/models/VideoApi;",
        "J"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

// Hook 5 — FoxImaStreamIdLoader.requestVODDAIUrl
object FoxImaVodStreamRequestFingerprint : Fingerprint(
    definingClass = "Lcom/fox/android/video/player/api/ima/loaders/FoxImaStreamIdLoader;",
    name = "requestVODDAIUrl",
    strings = listOf("requestVODDAIUrl() BEGIN..."),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/Boolean;",
        "Lcom/fox/android/video/player/loaders/ImaStreamIdLoader\$ImaStreamUrlCallback;"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// Hook 6 — FoxImaStreamIdLoader.requestImaStreamId
object FoxImaLiveStreamRequestFingerprint : Fingerprint(
    definingClass = "Lcom/fox/android/video/player/api/ima/loaders/FoxImaStreamIdLoader;",
    name = "requestImaStreamId",
    strings = listOf("requestImaStreamId BEGIN..."),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/Boolean;",
        "Lcom/fox/android/video/player/loaders/ImaStreamIdLoader\$ImaStreamIdCallback;"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// Hook 7 — Po/C$c.shouldInterceptRequest(WebView, WebResourceRequest)
//
// Intercepts WebView XHR/fetch requests and blocks ad domains. Cannot
// intercept media element (<video> src) requests — those are blocked at
// DNS layer via AGH rules.
//
// Class is the TvWebFragment.kt WebViewClient (source-confirmed). R8 renames
// its outer prefix each release: xo (10.20) → yo (10.21) → Po (10.28). NOTE a
// second class (Ml/w1$c, WebViewFragment.kt) also has this exact signature, so
// a pure signature match would be ambiguous — keep the class pinned.
object TubiWebClientInterceptFingerprint : Fingerprint(
    definingClass = "LPo/C\$c;",
    name = "shouldInterceptRequest",
    parameters = listOf(
        "Landroid/webkit/WebView;",
        "Landroid/webkit/WebResourceRequest;"
    ),
    returnType = "Landroid/webkit/WebResourceResponse;",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// Hook 8 — Po/C$c.onPageFinished(WebView, String)
//
// JavaScript fetch override injection. Fires after the SPA at
// ott-androidtv.tubitv.com has fully loaded, before the user selects
// any content. Patches window.fetch in the SPA's JS context to return
// empty responses for ad orchestration endpoints, eliminating the
// initial pre-roll countdown ad that survives Hook 7 (shouldInterceptRequest
// fires for fetch/XHR but the media element video request is exempt from
// that API, so the JS fetch override blocks it at the source — before
// the <video> src is ever set).
//
// Uses window.__tubiAdBlockInstalled guard to ensure idempotency if
// onPageFinished fires multiple times in a session.
object TubiWebClientPageFinishedFingerprint : Fingerprint(
    definingClass = "LPo/C\$c;",
    name = "onPageFinished",
    parameters = listOf(
        "Landroid/webkit/WebView;",
        "Ljava/lang/String;"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// Hook 9 — sf/c.suspendGetAdBreaks(String, String, String, Map, Continuation)
//
// Rainmaker ad-break fetch coroutine (RainmakerAdsFetcher.kt). Returns
// LMm/d; (sealed result wrapper, NetworkResponse.kt) — LMm/d$e for success,
// LMm/d$b subtypes (LMm/d$c / LMm/d$d) for error. The caller (Lrf/a;)
// branches on instance-of LMm/d$e vs LMm/d$b and converges afterward either
// way, so a synchronously-returned error result is sufficient — no
// suspension needed.
//
// R8 renames per release: class qf/c → sf/c; wrapper wm/d → Mm/d (10.28).
object QfcSuspendGetAdBreaksFingerprint : Fingerprint(
    definingClass = "Lsf/c;",
    name = "suspendGetAdBreaks",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;",
        "Lkotlin/coroutines/Continuation;"
    ),
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
