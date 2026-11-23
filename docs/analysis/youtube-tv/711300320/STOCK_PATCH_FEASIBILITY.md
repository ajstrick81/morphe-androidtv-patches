# Patching the STOCK YouTube ATV APK to inject an ad-block userscript

Goal: reproduce TizenTube's *result* (JS ad-block injected into the leanback app)
by **patching the stock Google APK** — no engine fork, no Chromium rebuild. Earlier
notes assumed this was likely infeasible; **investigation of the stock binaries
proved otherwise.** The injection seam already exists in the shipped app.

## The seam (all present in stock 7.11.300)

The stock build is upstream Chromium/Cobalt, so it already contains the machinery
TizenTube added a *caller* for — we just add the caller.

| Piece | Status in stock APK | Evidence |
|-------|--------------------|----------|
| `components/js_injection` (document-start injection) | Compiled into `libchrobalt.so` | strings: `js_injection.mojom.JsCommunication`, `WebViewDocumentStartJavascriptChanged` |
| `WebContents.evaluateJavaScript` native | Registered & live | `GEN_JNI` native `…WebContentsImpl_evaluateJavaScript(J,Object,Object)V` (public static); `onEvaluateJavaScriptResult` callback intact in `WebContentsImpl.smali` |
| Handle to the live page | Already exposed | `CobaltActivity.getActiveWebContents()` (returns `WebContentsImpl`, from `Shell.mWebContents`) |
| Native WebContents pointer | Public field | `WebContentsImpl.b : J` |

The Java *wrapper* for `evaluateJavaScript` was tree-shaken (nothing called it),
but the **native method is still registered and callable** — we invoke `GEN_JNI`
directly.

## The injection primitive (smali, no native changes)

```
# pseudo-smali
invoke-virtual {activity}, Ldev/cobalt/coat/CobaltActivity;->getActiveWebContents()
    -> WebContents (a WebContentsImpl)
# native ptr:
iget v_ptr, wc, Lcobalt/org/chromium/content/browser/webcontents/WebContentsImpl;->b:J
# call native eval (callback = null):
invoke-static {v_ptr, v_modjs, null},
  Lorg/jni_zero/GEN_JNI;->cobalt_org_chromium_content_browser_webcontents_WebContentsImpl_evaluateJavaScript(JLjava/lang/Object;Ljava/lang/Object;)V
```

`evaluateJavaScript` runs in the page's **main world** (not isolated) — exactly
what's needed so our hooks replace the page's own `JSON.parse` / `fetch` and see
the InnerTube responses.

## Trigger: when to fire the injection

The hooks must be installed **before the first InnerTube player-response is
parsed**. The home feed loads first; the player-response arrives when a video
starts — so injecting on first page settle is early enough, but earlier is safer.
Options, best first:

1. **`WebContentsObserver` callback** (stock has `WebContentsObserverProxy`): fire
   the injector on navigation-committed / `didFinishLoad` for the main frame, and
   re-fire on every navigation so SPA route changes stay covered.
2. **Lifecycle hook in `CobaltActivity`** (e.g. after `getActiveWebContents()`
   becomes non-null post-load) — simpler, one-shot; may miss later navigations.
3. **Guarded polling runnable** — dumb but robust: re-inject every N ms until a
   sentinel (`window.__ttc_injected`) is set.

Because the mod re-hooks `fetch`/`JSON.parse` idempotently (guard flag), harmless
to inject more than once.

## The mod payload (`MOD_JS`)

Two ways to supply the ad-block script:
- **Self-contained (recommended for v1):** embed a compact userscript as a smali
  string constant / APK asset. It installs `fetch` + `JSON.parse` hooks that strip
  `adPlacements`, `playerAds`, `adSlots`, and `…/get_midroll_info` from InnerTube
  `player`/`next` responses. No network, fully offline, update-proof.
- **Loader (later):** small bootstrap that fetches a hosted userscript (SponsorBlock
  / DeArrow need network anyway). Mirrors TizenTube's model.

The ad-strip logic is well-trodden (same technique as the phone-app JSON patches):
delete the ad keys from the parsed player response before returning it to the app.
`googlevideo.com` video + Widevine license traffic are never touched, so playback
is unaffected (see `NATIVE_ANALYSIS.md` traffic separation).

## Patch build pipeline

1. `apktool d base.apk` (full, with sources).
2. Add the injector: either a new small smali class or inline in `CobaltActivity`;
   wire the trigger (observer hook).
3. Add `MOD_JS` as an asset or string.
4. `apktool b`, `zipalign -p 4`, re-sign **base + `armeabi_v7a` split** with one key
   (the app requires the split: `splits.required=true`,
   `requiredSplitTypes="base__abi"`).
5. Install the split set together (`adb install-multiple base.apk split.apk`).

## Risks / unknowns to validate on-device

- **Obfuscation drift:** field `b`/method letters (`u()`,`v()`) are R8-renamed and
  will differ across app versions — the patch must be re-derived per version
  (anchor on the stable `getActiveWebContents()` + the GEN_JNI native name, which
  are not renamed).
- **Injection world/timing:** confirm `evaluateJavaScript` lands in the main world
  early enough to hook the first player-response (adjust trigger if a pre-roll
  slips through).
- **DroidGuard / integrity:** sideloaded + resigned is expected; watch for any
  server-side attestation gating playback (not observed so far).
- **Source stamp:** invalidated by resign — fine for sideload.

## Verdict

**Feasible via smali patch alone.** The stock app ships the injection capability;
we add ~a few smali instructions to call it plus a JS payload. No fork, no native
rebuild — a genuine APK patch, which is exactly the project's wheelhouse.

### Suggested v1 milestone
Minimal: inject a one-line sentinel script via the primitive above and confirm on
a device that `window.__ttc_injected` appears in the leanback app. That proves the
seam end-to-end; the ad-strip payload is then a JS problem, not an APK one.
