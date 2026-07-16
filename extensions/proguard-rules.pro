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

# Prime Video (phone) — D-pad player controls.
# install() is called only from injected smali (PlayerActivity.onResume), so R8
# sees it as unreferenced and would strip or rename it. The DpadWindowCallback
# subclass is instantiated at runtime and implements the framework Window.Callback
# interface — keep it (and its constructor) intact as a named class so R8 cannot
# inline, rename, or re-collapse it into an unverifiable shape after the raw dex
# merge (same rationale as PeacockWebViewHelper$WrappedClient above).
-keep class ajstrick81.morphe.extension.primevideophone.controls.DpadPlayerControls {
    public static void install(android.app.Activity);
}
-keep class ajstrick81.morphe.extension.primevideophone.controls.DpadPlayerControls$DpadWindowCallback {
    <init>(android.view.Window$Callback, android.app.Activity);
    *;
}

# Prime Video (phone) — reduce-UI-zoom density override.
# DensityHelper.wrap() is called only from injected smali
# (AppCompatActivity.attachBaseContext), so R8 would otherwise strip/rename it.
-keep class ajstrick81.morphe.extension.primevideophone.display.DensityHelper {
    public static android.content.Context wrap(android.content.Context);
}

# Prime Video (phone) — Morphe settings.
# initialize() is called only from injected smali (the hijacked GoogleApiActivity's
# onCreate), so R8 sees it as unreferenced. Settings' accessors are called from the
# other extension classes, but keep them explicitly so the keys/defaults survive.
-keep class ajstrick81.morphe.extension.primevideophone.settings.SettingsActivityHook {
    public static void initialize(android.app.Activity);
}
-keep class ajstrick81.morphe.extension.primevideophone.settings.Settings {
    public static *;
}
# MorphePreferenceFragment is re-instantiated BY NAME by the framework after a
# configuration change, so it needs its no-arg constructor kept — R8 cannot see
# that reflective construction.
-keep class ajstrick81.morphe.extension.primevideophone.settings.MorphePreferenceFragment {
    <init>();
    *;
}

# MLB At Bat — ad-break overlay helper. Called directly from injected smali
# via invoke-static {} in Lb6/h$d;.b(), Lb6/h$i;.onAdBreakStarted()/onAdBreakEnded().
-keep class ajstrick81.morphe.extension.mlbtv.ads.AdBreakOverlayHelper {
    public static void registerAdViewGroup(android.view.ViewGroup);
    public static void showOverlay();
    public static void hideOverlay();
}
