package ajstrick81.morphe.patches.tubi.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.tubi.shared.Constants

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Suppresses all ad delivery systems in Tubi Android TV.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // Hook 1 — FoxImaAdListeners.adEventListener_delegate$lambda$10$lambda$9
        FoxImaAdEventListenerFingerprint.method.addInstructions(0, "return-void")

        // Hook 2 — FoxImaAdListeners.adsLoadedListener_delegate$lambda$4$lambda$3
        FoxImaAdsLoadedListenerFingerprint.method.addInstructions(0, "return-void")

        // Hook 3 — FoxPlayer.clearVodAds()
        FoxPlayerClearVodAdsFingerprint.method.addInstructions(0, "return-void")

        // Hook 4 — ImagePauseAds.l(VideoApi, long)
        TubiPauseAdsFingerprint.method.addInstructions(0, "return-void")

        // Hook 5 — FoxImaStreamIdLoader.requestVODDAIUrl
        FoxImaVodStreamRequestFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "dai_blocked"
                invoke-interface {p3, v0}, Lcom/fox/android/video/player/loaders/ImaStreamIdLoader${"$"}ImaStreamUrlCallback;->onFailure(Ljava/lang/String;)V
                return-void
            """
        )

        // Hook 6 — FoxImaStreamIdLoader.requestImaStreamId
        FoxImaLiveStreamRequestFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "dai_blocked"
                invoke-interface {p3, v0}, Lcom/fox/android/video/player/loaders/ImaStreamIdLoader${"$"}ImaStreamIdCallback;->onFailure(Ljava/lang/String;)V
                return-void
            """
        )

        // Hook 7 — xo/C$c.shouldInterceptRequest(WebView, WebResourceRequest)
        //
        // WebView-layer ad domain interception. Blocks XHR/fetch requests from
        // the Tubi SPA to ad orchestration endpoints. Cannot intercept media
        // element (<video> src) requests — those require AGH DNS rules.
        TubiWebClientInterceptFingerprint.method.addInstructions(
            0,
            """
                if-eqz p2, :intercept_skip
                invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;
                move-result-object v0
                if-eqz v0, :intercept_skip
                invoke-virtual {v0}, Landroid/net/Uri;->getHost()Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :intercept_skip
                const-string v2, "dai.google.com"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "imasdk.googleapis.com"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "doubleclick.net"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "googletagmanager.com"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "googlesyndication.com"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "adrise.tv"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "ads.production-public.tubi.io"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                const-string v2, "rainmaker.production-public.tubi.io"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v3
                if-nez v3, :intercept_block
                goto :intercept_skip
                :intercept_block
                new-instance v0, Landroid/webkit/WebResourceResponse;
                const-string v1, "text/plain"
                const-string v2, "utf-8"
                const/4 v3, 0x0
                new-array v3, v3, [B
                new-instance v4, Ljava/io/ByteArrayInputStream;
                invoke-direct {v4, v3}, Ljava/io/ByteArrayInputStream;-><init>([B)V
                invoke-direct {v0, v1, v2, v4}, Landroid/webkit/WebResourceResponse;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V
                return-object v0
                :intercept_skip
                nop
            """
        )

        // Hook 8 — xo/C$c.onPageFinished(WebView, String)
        //
        // JavaScript fetch override injected into the SPA after page load.
        // Intercepts fetch() calls to ad orchestration endpoints in JS context.
        TubiWebClientPageFinishedFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "(function(){if(window.__tubiAdBlockInstalled)return;window.__tubiAdBlockInstalled=1;var f=window.fetch;if(!f)return;window.fetch=function(u,o){var s=String(u);if(s.indexOf('rainmaker.production-public.tubi.io')>=0||s.indexOf('ads.production-public.tubi.io')>=0)return Promise.resolve(new Response('',{status:200}));return f.call(this,u,o)}})()"
                const/4 v1, 0x0
                invoke-virtual {p1, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V
            """
        )

        // Hook 9 — qf/c.suspendGetAdBreaks(String, String, String, Map, Continuation)
        //
        // Rainmaker ad-break fetch coroutine. Returns LMm/d; (sealed result),
        // with the caller (Lrf/a;) branching on instance-of LMm/d$e
        // (success) vs LMm/d$b (error) and converging afterward either way.
        //
        // Previous attempt returned COROUTINE_SUSPENDED directly to fake a
        // no-op, which broke the suspend-function contract: nothing ever
        // called continuation.resumeWith(), so the caller's coroutine parked
        // forever (confirmed via logcat: TubiCoroutineGuard MISSING_HANDLER
        // at TubiNewPlayerImpl.kt:102), hanging the entire content pipeline.
        //
        // Fix: skip the network call and the continuation machinery entirely,
        // returning a real, immediate LMm/d$d (generic error) result. The
        // caller takes its existing error branch — the same one a genuine
        // network failure already triggers gracefully — and the coroutine
        // completes normally, with no DNS-layer workaround required.
        QfcSuspendGetAdBreaksFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Ljava/io/IOException;
                const-string v1, "ads_blocked"
                invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
                new-instance v2, LMm/d${"$"}d;
                invoke-direct {v2, v0}, LMm/d${"$"}d;-><init>(Ljava/lang/Throwable;)V
                return-object v2
            """
        )
    }
}
