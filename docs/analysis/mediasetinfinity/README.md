# Mediaset Infinity Android TV — recon notes (8.1.13)

Package `it.mediaset.infinitytv`, versionCode `24121109`, APKPure XAPK (base +
`config.armeabi_v7a` + language/density splits). Autopsied 2026-11-14 with jadx,
baksmali and androguard. **No device test yet.**

## What the APK is

A thin, un-obfuscated Kotlin shell, `it.mediaset.mediasetplay.ctv.*` (~130
classes). Everything else in the two dex files is library code: media3/ExoPlayer,
leanback, Sentry, okhttp, Gson, coroutines.

- `BaseActivity` builds one `WebView`, loads `assets/index.html` under
  `https://local.mediasetplay.mediaset.it/index.html`, and that page pulls the
  real app from
  `https://static1.mediasetplay.mediaset.it/app/ctv-lng/androidtv/bootstrap.js`
  (`BuildConfig.WEB_APP_URL` is the same path's `index.html`).
- Two JS bridges are attached:
  - `ISAndroidTVBridge` → `CTVJavascriptBridge`: device/display/network info,
    Widevine info, lifecycle and back/menu handlers, `getAdvertisingIdInfo`.
  - `ISExoPlayerBridge` → `Media3JavascriptBridge`: a bare media3 player the web
    app drives (`prepare(url, mime, startWindow, startPosition, drmScheme,
    licenseUri, …)`, `prepareWithParams`, `seekTo`, `setPlayWhenReady`, `stop`,
    track selection, subtitles) and that reports state back through
    `setListener` callbacks.
- `BaseActivity.getClient()` returns the only app `WebViewClient`
  (`BaseActivity$getClient$1`). It overrides `shouldInterceptRequest` to serve
  bundled assets through two `WebViewAssetLoader`s and otherwise defers to the
  network.

## Where the ads are

Not in the APK. There is no Google IMA, FreeWheel, Smartclip or other ad SDK, no
ad classes and no ad strings. The only ad-adjacent code is `AdvertisingIdHelper`
(hands the Google/Amazon advertising ID to JS) and the `AD_ID` permission.
`assets/bumper.mp4` is the brand bumper.

Ad decisioning, scheduling and the ad UI therefore live in the remote `ctv-lng`
web app. Mediaset's ad platform is FreeWheel (Mediaset/FreeWheel/Comcast
Technology Solutions announcement, December 2018; Publitalia '80 still cites
FreeWheel in 2024 material). **The web bundle was not reachable from the
analysis environment, so the ad hosts and the web app's failure handling are not
confirmed for 8.1.13.**

## Seam chosen

`BaseActivity$getClient$1.shouldInterceptRequest(WebView, WebResourceRequest)`:
every XHR, fetch and script request the web app makes passes through it. The
patch prepends a call to
`MediasetWebViewHelper.intercept(request)` and returns its result when non-null.

Blocked (empty 204):

| Match | Why |
|---|---|
| `*.fwmrm.net` with path `/ad/…` | FreeWheel ad request (`/ad/g/1`, `/ad/p/1`) and beacons (`/ad/l/1`). SDK script paths are left alone. |
| `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `2mdn.net` | Google ad decision / creative hosts, in case a break is filled that way. |

Not blocked on purpose: the FreeWheel and IMA SDK scripts (so the web app's
"ad request failed → play content" path runs instead of a missing-SDK error),
and all analytics.

## Why this differs from RaiPlay

RaiPlay 5.x has a native ad bridge (`NativePlaybackRequestParser.parseAdContext`
→ Smartclip), so returning `null` there selects the app's own ad-free path.
Mediaset has no native ad code at all, so the equivalent decision point is in
remote JS and can only be reached from the shell through its network traffic
(or, later, by injecting JS).

## Open questions for a device test

1. Which hosts does the web app really call for ads? Run
   `adb shell setprop log.tag.MORPHE-MSI DEBUG`, restart the app, play a VOD
   title and a live channel, and collect `adb logcat -s MORPHE-MSI`.
2. Does content start when the ad request is answered empty, or does the web app
   wait / show an ad-blocker message? If it gates, the next step is the opt-in
   **Enable WebView debugging** patch plus `chrome://inspect` to read the
   player's ad module.
3. Are live channels (and any VOD) server-stitched? If so that is a mask/slate
   job, not removal.
4. Are ad creatives played through `ISExoPlayerBridge.prepare(...)` or an HTML
   `<video>`? Only matters if a second, native layer is needed.
