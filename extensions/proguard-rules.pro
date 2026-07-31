# ProGuard rules for Prime Video ATV extensions.
#
# All three methods are called directly from patched smali via invoke-static.
# Without these rules R8 may inline or remove them since they appear
# unreferenced from the extension module's own code graph.
#
# Note: MetricsTransporter.transmit() hook uses pure inline smali to
# construct UploadResult directly — no extension method needed there.

-keep class ajstrick81.morphe.extension.primevideo.ads.SkipAdsPatch {
    public static *** skipAllMedia3AdGroups(com.google.common.collect.ImmutableMap);
    public static *** skipAllExo2AdGroups(com.google.common.collect.ImmutableMap);
    public static *** enforceAdBlock(com.android.volley.Request);
}

# Prime Video native ad-strip loader — called directly from injected smali
# in Application.onCreate via invoke-static {}. Without this rule R8 sees it
# as unreferenced (nothing in the extension's own code graph calls it) and
# would strip or rename it, breaking the loadLibrary("pvhook") call.
-keep class ajstrick81.morphe.extension.primevideo.nativehook.NativeHookLoader {
    public static void load();
}
# Peacock — existing entry
# emptyAdPlaybackState is called reflectively by the Sky SDK layer patches.
-keep class ajstrick81.morphe.extension.peacock.ads.SkipAdsPatch {
    public static *** emptyAdPlaybackState(java.lang.Object);
}

# Peacock — Layer 6: OkHttp ad CDN interceptor
# AdBlockInterceptor is instantiated by PeacockAdPatchHelper at runtime.
# Keeping the class and no-arg constructor prevents R8 from stripping it.
-keep class ajstrick81.morphe.extension.peacock.ads.AdBlockInterceptor {
    public <init>();
}

# Peacock — Layer 6: method-replacement wrapper
# PeacockAdPatchHelper.buildOkHttpClient() is called directly from injected
# smali via invoke-static {}. R8 must not rename or remove this method.
# OkHttpWorkaroundInterceptor is also instantiated here — kept via its own
# existing rule elsewhere; confirm it has one if the build strips it.
#
# Layer 9: addAdBlockInterceptor(OkHttpClient.Builder) is likewise called
# only from injected smali (in NativeNetworkApi.<init>), so it must be kept
# explicitly too — without this R8 sees it as unreferenced and would strip
# or rename it, breaking the Sky SDK addon-client interception at runtime.
-keep class ajstrick81.morphe.extension.peacock.ads.PeacockAdPatchHelper {
    public static okhttp3.OkHttpClient buildOkHttpClient();
    public static okhttp3.OkHttpClient$Builder addAdBlockInterceptor(okhttp3.OkHttpClient$Builder);
}
# Layer 7 — WebView shouldInterceptRequest wrapper
# wrapClient() returns a named WrappedClient instance (not an anonymous
# class — ART's verifier rejected an anonymous WebViewClient subtype here
# after extendWith()'s raw dex merge, see PeacockWebViewHelper.java). Keep
# both the entry point and the named subclass intact so R8 cannot merge,
# inline, or otherwise re-collapse it back into the unverifiable shape.
-keep class ajstrick81.morphe.extension.peacock.ads.PeacockWebViewHelper {
    public static android.webkit.WebViewClient wrapClient(android.webkit.WebViewClient);
}
-keep class ajstrick81.morphe.extension.peacock.ads.PeacockWebViewHelper$WrappedClient {
    <init>(android.webkit.WebViewClient);
    *;
}

# MLB At Bat — ad-break overlay helper. Called directly from injected smali
# via invoke-static {} in Lb6/h$d;.b(), Lb6/h$i;.onAdBreakStarted()/onAdBreakEnded().
-keep class ajstrick81.morphe.extension.mlbtv.ads.AdBreakOverlayHelper {
    public static void registerAdViewGroup(android.view.ViewGroup);
    public static void showOverlay();
    public static void hideOverlay();
}

# MLB At Bat — Patch 6: HLS manifest ad-segment stripper. Called directly
# from injected smali via invoke-static {} in Lr5/a;.f(Lq5/i;)J, right after
# the network response InputStream is obtained.
-keep class ajstrick81.morphe.extension.mlbtv.ads.MlbManifestRewriter {
    public static java.io.InputStream wrap(java.lang.Object, java.io.InputStream);
}

# Pluto DASH period strip — skipAdsPatch Hook 5 (see SkipAdsPatch.kt).
# PlutoDashManifestProbe.stripAdPeriods(DashManifest) is called only from injected
# smali (DashManifestParser.parse return), so R8 sees it as unreferenced and would
# strip or rename it. The media3 classes it references are provided by the app at
# runtime (compileOnly).
-keep class ajstrick81.morphe.extension.pluto.ads.PlutoDashManifestProbe {
    public static androidx.media3.exoplayer.dash.manifest.DashManifest stripAdPeriods(androidx.media3.exoplayer.dash.manifest.DashManifest);
}
-dontwarn androidx.media3.exoplayer.dash.manifest.**
