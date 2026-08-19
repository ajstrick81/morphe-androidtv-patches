# TizenTube Cobalt — How It Works (and how it relates to our target)

Reference: `github.com/reisxd/TizenTubeCobalt` (engine) + `github.com/reisxd/TizenTube`
(the mod userscript). Studied because it solves the exact problem our
`NATIVE_ANALYSIS.md` landed on — stripping ads from the Cobalt leanback app —
and does it **the third way** we identified (in-app JS, no external proxy).

## Architecture

TizenTube Cobalt is **not an APK patch**. It is a **full source fork of
Chromium-based Cobalt** ("Chrobalt", the same engine family as the stock YouTube
ATV app's `libchrobalt.so`) that is **built from source** into its own app for
Android TV / Google TV, Tizen, and webOS.

It keeps the stock start URL (`https://www.youtube.com/tv`) and strips ads
**inside the running leanback app** via two additions on top of upstream Cobalt:

1. **A document-start JS injection hook.** `Shell::RegisterInjectedJavaScript()`
   (`cobalt/shell/browser/shell.cc`) calls
   `js_communication_host_->AddDocumentStartJavaScript(script, {"*"})` —
   Chromium's `components/js_injection` (the same API Android WebView exposes as
   `addDocumentStartJavaScript`). This runs a userscript **before** the page's own
   JS on every origin. The mod hooks `JSON.parse` / `fetch` to delete
   `adPlacements` / `playerAds` / ad-break metadata from the InnerTube
   player-response, and adds SponsorBlock / DeArrow.
2. **A native bridge for the mod** — `h5vcc_tizentube` (Mojo interface
   `cobalt/browser/h5vcc_tizentube/`), exposing to JS:
   `InstallAppFromURL`, `GetVersion`, `GetArchitecture`, `GetBrandAndModel`,
   `SetFrameRate`, `SetUserAgent`. Lets the userscript self-update, spoof UA,
   set frame rate, etc.

The actual ad-block/SponsorBlock/DeArrow logic lives in the **separate
`reisxd/TizenTube` userscript repo**, not in the engine fork. The engine fork's
job is just to *inject* it and give it native powers.

### Why this is clean
- Video (`googlevideo.com`) and Widevine DRM are never touched — the mod only
  edits the JSON metadata the app parses, so playback/licensing are unaffected
  (matches the traffic-separation finding in `NATIVE_ANALYSIS.md`).
- No proxy, no DNS, no MITM CA. Fully on-device.
- Survives the app "updating" because the app is the leanback *web* app served
  live — the mod re-applies on every load.

## How this maps onto our target (stock YouTube ATV 7.11.300)

Our `base.apk` + `libchrobalt.so` is the **stock Google** Cobalt build. Crucially:
- It has **no** `AddDocumentStartJavaScript` mod hook wired up, and **no**
  `h5vcc_tizentube` bridge. Those are TizenTube's source-level additions.
- So the TizenTube result **cannot be reproduced by smali/APK patching** of the
  stock app — the injection lives in native `libchrobalt.so`, which we'd have to
  rebuild. This is the wall: TizenTube didn't patch the APK, they **rebuilt the
  engine**.

### Two honest paths to "TizenTube on our device"

| Path | What it is | Effort | Notes |
|------|-----------|--------|-------|
| **A. Build TizenTube Cobalt from source** | Clone the fork, set up the Chromium/Cobalt build toolchain (depot_tools, GN/Ninja), build the Android TV APK target; optionally customize the injected userscript | **Large** — full Chromium-scale build (big toolchain, long compile) | The intended path. Yields a working, self-contained ad-free leanback app. Our repo would host the build recipe + any customizations. |
| **B. APK-patch the stock app to inject the mod** | Add a document-start JS injection to the stock Cobalt without full rebuild | **Very hard / likely infeasible** | Stock `libchrobalt.so` doesn't expose an injection seam we can reach from smali; would require native binary patching of the engine. Not the way TizenTube went, for good reason. |

**Recommendation:** if the goal is the TizenTube outcome, **Path A** is the real
route — adopt/customize the source fork and build it, rather than trying to graft
JS injection onto the stock APK. Our analysis of the stock app is still valuable:
it confirms the engine family matches, the URL guard is permissive, and the
traffic separation that makes the JS-injection approach safe.

## Open decisions (for the user)
- Do we want to **build/customize the TizenTube fork** (Path A), or
- Explore whether a **lighter customization** (e.g. our own userscript injected
  by the fork's existing hook) meets the goal, or
- Something else (rebrand, feature additions, target a specific device)?
