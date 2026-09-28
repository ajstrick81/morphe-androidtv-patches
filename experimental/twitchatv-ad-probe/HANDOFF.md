# HANDOFF — finish the Twitch ATV ad diagnostic + study the live app

**Goal:** run the diagnostic against the **live** Twitch Android-TV app on a real device,
determine whether ads still play and **why**, and turn the finding into a patch seam.

**This task needs a session with DEVICE ACCESS** — an environment where `adb` reaches the ATV
box (e.g. the user's local machine / Onn 4K over adb). The session that wrote this could not:
it ran in a cloud container with no adb and no route to the LAN device, so it built and
hardened the tooling and left this runbook. **Do not fabricate device output. If you can't
reach a device, say so and stop.**

Repo: `ajstrick81/morphe-androidtv-patches`
Branch: `claude/twitch-ad-blocking-techniques-x0pi28`
Tooling dir: `experimental/twitchatv-ad-probe/`

---

## 1. What is already established (do NOT re-derive)

- **Two different apps.** The **mobile** app (`tv.twitch.android.app`, ~v17+, native player)
  has a rich ad stack. The **ATV "Starshot"** app (`tv.twitch.android.app` **v13.0.0.2**, Fire
  TV/Celadon) is what the patch targets — a **WebView shell**.
- **ATV architecture is proven** (dex strings + JS term-probe + source maps, all this session):
  - `classes.dex` (~2.3 MB) = thin shell `tv.twitch.starshot64.app.StarshotActivity`, which loads
    `file:///android_asset/laserarray/index.html`.
  - `assets/laserarray/**` = a **Next.js browse/UI app** (only `@twitch/core-ui*` packages). **No
    player, no hls.js, no ad code** — confirmed against the original TypeScript in the shipped
    `.js.map` source maps (`sourcesContent`, 893 sources; not obfuscated: `_0x`=0).
  - The **player + hls.js + all ad-delivery logic load from Twitch's network at runtime** into the
    WebView's Chromium renderer. **None of it is in the APK.**
  - Consequence: the **only** static seam is the native `WebViewClient.shouldInterceptRequest`
    hook. There is nothing to patch in the dex or the bundle.
- **Shipping scrubber** = `extensions/.../twitchatv/ads/TwitchAtvWebViewHelper.java`
  (wired by `patches/.../twitchatv/ads/BlockTvAdsPatch.kt`, which wraps `setWebViewClient`):
  - `isWeaverPlaylist(url)` gate = `url.contains(".playlist.ttvnw.net/") && url.contains("/playlist/")`.
  - Re-fetches the playlist via `httpGet`, then `blankAdSegments` rewrites each **non-`live`**
    `#EXTINF` segment URI to a black-TS sentinel (`BlankSegment`), PTS-shifting so the timeline
    advances. **Fails open** (any error → original playlist).
- **Full ad map** (mobile-derived, still the best reference): `docs/twitch-ad-delivery-map.md`.
  Endpoints `edge.ads.twitch.tv/2018-01-01/{ads,vod-ads}`; custom HLS tags `X-TV-TWITCH-AD-*` /
  `X-TTV-MAF-AD-*`; SureStream SSAI; PubSub `midroll_request`.
- **Real ADPROBE** (methodology source): `experimental/netflix-adprobe/adprobe-jsvm-tap.js` (a
  Frida JS-VM heap tap). We ported its 4 ideas — keyword table, residency classification,
  seam-lock, live-capture — into a **WebView front-end** (see §3).

---

## 2. The question to answer

Ads are (reportedly) still playing despite the scrubber. **Why?** Six falsifiable hypotheses;
the tool tags each one in logcat during a break. Exactly one line usually settles it.

| # | Hypothesis | What confirms it in the capture |
|---|-----------|--------------------------------|
| **H1** | **Client-side ad path** (`edge.ads.twitch.tv` / `X-TV-TWITCH-AD` tags) the SSAI scrubber can't see | `MORPHE-ADPROBE !!! H1 …` or a `[AD-DECISION]` URL line |
| **H2** | **Weaver host/format drift** — the live media-playlist URL no longer matches the shipping `isWeaverPlaylist` predicate, so `scrubPlaylist` never runs | `PL … weaverPredicate=false` **while** `stitchedAd>0` → `!!! H2` |
| **H3** | **Discriminator drift** — ad segments no longer titled non-`live`, so `blankAdSegments` mis-classifies | `stitchedAd>0` but `extinfAd=0` → `!!! H3` |
| **H4** | **Fail-open on re-fetch** — `httpGet` now needs auth/headers → non-200 → original ad playlist served | `stitchedAd>0` in a body but the scrubber's `MORPHE-TW-ATV-WV … blanked` never logs for it |
| **H5** | **LL-HLS parts** — ad content via `#EXT-X-PART` partial segments; full-URI blanking misses them | `parts>0` alongside `stitchedAd>0` → `!!! H5` |
| **H6** | **Service-worker / native fetch** — the weaver fetch stops hitting `shouldInterceptRequest` | scrubber never logs, but the ADPROBE in-page tap **does** see the playlist body |

**H2 is the prime suspect** and the cheapest to confirm — check it first.

---

## 3. The tooling (all in `experimental/twitchatv-ad-probe/`)

- **`adprobe-webview-tap.js`** — the ADPROBE WebView front-end. In-page tap (runs inside the
  WebView's V8, injected via `evaluateJavascript`). Hooks fetch/XHR to capture **response
  bodies**, runs the Twitch keyword table with residency classification, and — key for this task —
  `analyzePlaylist()` emits a `MORPHE-ADPROBE PL …` line per media playlist that directly tests
  H1/H2/H3/H5. **Use this one for the diagnosis.**
- **`fetch-xhr-probe.js`** / **`TwitchAtvUrlProbe.java`** — lighter URL-only logger + reference
  injector (shows the one-line `evaluateJavascript` wiring). Superseded by the tap for analysis.
- **`tw-adb-probe.sh`** — adb harness (launch channel, foreground-guard, hold, read logcat
  oracles). Automates the capture window.

---

## 4. Runbook (do this on the device box)

**Step 0 — reach the device.** `adb devices` must list the ATV box. If not, stop and fix that.

**Step 1 — bake the tap into a patched build.** Release WebViews aren't inspectable, so inject
the tap from inside. In `TwitchAtvWebViewHelper.java`, add the contents of
`adprobe-webview-tap.js` as an escaped `String ADPROBE_JS` constant and call it next to the
existing `injectPlaybackFix(view)` in **both** `onPageStarted` and `onPageFinished`:

```java
view.evaluateJavascript(ADPROBE_JS, null);
```

(Optional, enables Chrome DevTools/CDP live inspection too: set `android:debuggable="true"` in the
repackaged manifest — the build is already repackaged by Morphe.)

Rebuild the patch and install the patched APK on the ATV device.

**Step 2 — capture across a real ad break.** Use a channel you expect to serve ads (not a
Turbo/sub-only/ad-free stream). Live ad breaks are non-deterministic — hold and wait.

```bash
adb logcat -c
adb logcat | grep -E 'MORPHE-ADPROBE|MORPHE-TW-ATV-WV'
# in the app: join the ad-heavy channel, let it play, wait for a mid-roll (several minutes)
```

or drive it: `bash experimental/twitchatv-ad-probe/tw-adb-probe.sh <channel> 10`

**Step 3 — read the verdict.** During/after a break, look at the `MORPHE-ADPROBE PL …` lines and
any `!!! H#` tags, and whether `MORPHE-TW-ATV-WV … blanked` fired. Map to the H-table above.

**Step 4 — paste back** the `MORPHE-ADPROBE` / `MORPHE-TW-ATV-WV` block (a break's worth) for
analysis, or diagnose in-session using the H-table.

---

## 5. From diagnosis → door (per outcome)

- **H2 (predicate drift):** widen/replace `isWeaverPlaylist` to match the live host/format the
  capture shows. Cheapest possible fix — a string predicate.
- **H3 (discriminator drift):** update `blankAdSegments` to the new ad marker (e.g. `DATERANGE
  CLASS="twitch-stitched-ad"` instead of / in addition to the `!= "live"` title test).
- **H1 (client-side pod):** new seam — signal-strip `X-TV-TWITCH-AD-*` tags from the playlist
  and/or empty the `edge.ads.twitch.tv` response (see the "knocking out midrolls" section of
  `docs/twitch-ad-delivery-map.md`).
- **H4 (fail-open):** make `httpGet` forward the WebView's headers/cookies, or rewrite the body
  in-flight instead of re-fetching (the MITM-proxy fallback noted in `TwitchAtvWebViewHelper`'s
  header comment).
- **H5 (LL-HLS parts):** extend blanking to `#EXT-X-PART` URIs, not just full-segment URIs.
- **H6 (service worker):** move interception below the WebView layer (MITM proxy fallback).

---

## 6. Guardrails

- **Read-only diagnostic.** The tap and probes only read + log. Keep them so during diagnosis.
- **Autotest HARD RULES** (from the Netflix harness): foreground-guard before every input; no
  blind directional navigation; verify playback started before stressing.
- **Don't invent device output.** Every `PL …` / `!!! H#` line must come from a real logcat.
- **"No client-side ad in N minutes" is evidence, not proof** — live breaks are random; re-run
  longer / on a heavier-ad channel before concluding pure-SSAI.

---

## 7. Branch state

Branch `claude/twitch-ad-blocking-techniques-x0pi28`, all documentation/experimental — no
shipping behavior changed, no PR:
`TwitchAtvWebViewHelper` fallback note · `docs/twitch-ad-delivery-map.md` · ATV architecture
finding · `fetch-xhr-probe.js` + `TwitchAtvUrlProbe.java` · `tw-adb-probe.sh` ·
`adprobe-webview-tap.js` (+ this handoff). Start by reading `docs/twitch-ad-delivery-map.md`
and `TwitchAtvWebViewHelper.java`, then run §4.
