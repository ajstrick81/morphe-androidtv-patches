# Twitch ATV — fetch/XHR URL probe (experimental diagnostic)

Observation-only shim to learn **how the Twitch Android-TV (Starshot) app actually
delivers ads at runtime**, since static analysis of the APK can't — the player and
all ad logic load from Twitch's network into the WebView (see
`docs/twitch-ad-delivery-map.md`).

**This is not part of the shipping patch.** Nothing here changes behavior. It wraps
`fetch` / `XMLHttpRequest.open` / `navigator.sendBeacon`, classifies and logs each
URL, and keeps a 200-event ring buffer. It never blocks, rewrites, or delays a
request.

## The question it answers

During a **mid-roll**, does the ATV player fetch a client-side ad decision —

```
https://edge.ads.twitch.tv/2018-01-01/ads?...
```

— or do ads only ever appear **stitched into the weaver `.m3u8` playlist** (SSAI)?
The shipping patch's playlist-scrubber handles the SSAI path. If an `AD-DECISION`
line ever appears in the log, some ads are arriving on a path the scrubber cannot
see, and a second seam (block/empty that request) would be needed.

## Files

- **`fetch-xhr-probe.js`** — the readable shim. Edit this one.
- **`TwitchAtvUrlProbe.java`** — reference helper showing how to inject it via the
  same `evaluateJavascript` seam `TwitchAtvWebViewHelper` uses for `PLAYBACK_FIX_JS`.
  Carries a minified inline copy of the JS; keep the two in sync.
- **`tw-adb-probe.sh`** — adb harness that automates a capture session (the Twitch
  analog of `experimental/netflix-native-adstrip/autotest/nf-autotest.sh`). Launches
  the app on a channel, foreground-guards, holds through a watch window, and reads
  the logcat oracles. See below.

## Automated capture: `tw-adb-probe.sh`

Modeled on the Netflix `nf-autotest.sh` harness — same idioms (`K`/`FG`/`POS`/
`guard`), same two-oracle approach — adapted for Twitch:

- **SSAI oracle (shipping, no wiring needed):** counts `MORPHE-TW-ATV-WV … blanked`
  lines from the scrubber in `TwitchAtvWebViewHelper` — i.e. SSAI/SureStream ads it
  neutralized.
- **Client-side oracle (needs the shim wired):** counts `MORPHE-PROBE [AD-DECISION|
  AD-VOD|AD-3P]` lines — a client-side `edge.ads.twitch.tv` fetch the scrubber
  cannot see. This is the smoking gun the whole probe exists to find.

```bash
# on a machine with adb connected to the ATV device, running a Morphe-patched Twitch
bash tw-adb-probe.sh <channel> [watch_minutes]
bash tw-adb-probe.sh somechannel 8
```

Two things to know before trusting a run:

1. **Live ad breaks are non-deterministic.** Unlike a Netflix VOD you can't seek to
   a mid-roll, so the harness just holds on the channel and samples. Use a channel
   you expect to serve ads (not Turbo/sub-only/ad-free), and run long. "No
   client-side ads in 8 min" is evidence, not proof — re-run longer to build
   confidence.
2. **Client-side detection requires the shim.** If `tw-adb-probe.sh` reports
   *"fetch/XHR probe present: no"*, only the SSAI oracle is live and the verdict is
   inconclusive on client-side ads. Wire `TwitchAtvUrlProbe.inject(view)` (above),
   rebuild, and re-run.

### What is NOT ported from the Netflix tooling
The Netflix probe also had `frida/dump_appboot.js`, which dumps Netflix's encrypted
in-process player bundle. **That does not apply to Twitch ATV** — this session
proved the ATV player isn't in the APK at all (it loads from the network into the
WebView; see `docs/twitch-ad-delivery-map.md`). There is no in-process bundle to
dump, so the runtime `fetch`/XHR observation *is* the equivalent capability.

## How to run a capture session

1. Temporarily add one line to `TwitchAtvWebViewHelper.WrappedClient`
   (in `onPageStarted` **and** `onPageFinished`, next to `injectPlaybackFix(view)`):

   ```java
   TwitchAtvUrlProbe.inject(view);
   ```

   (Copy `TwitchAtvUrlProbe.java` into the extension package for the build, or
   inline `PROBE_JS` into a local method. Remove after the investigation — this is
   not meant to ship.)

2. Build the patched app, install on the ATV device, and watch:

   ```
   adb logcat | grep MORPHE-PROBE
   ```

   You should see `MORPHE-PROBE installed …` once per page load, then `PLAYLIST`
   lines during playback.

3. Start a stream and **wait for a mid-roll** (or force a pre-roll by joining a
   fresh channel). Watch which buckets appear:

   | Log bucket | Meaning |
   |------------|---------|
   | `AD-DECISION` | **client-side** ad pod fetch (`edge.ads.twitch.tv/.../ads`) — the smoking gun |
   | `AD-VOD` | VOD mid-roll decision (`/vod-ads`) |
   | `AD-3P` | third-party ad/verification (Amazon, IAS, Google) |
   | `PLAYLIST` | HLS media playlist (`.m3u8` / weaver) — SSAI stitch target |
   | `TRACKING` | spade / quartile / pixel beacons |
   | `GQL`, `SEGMENT-OR-CDN`, `other` | counted only, not printed (noise) |

4. Pull the structured buffer instead of scraping logcat, if you prefer:

   ```java
   webView.evaluateJavascript("window.__morpheAdProbe.dump()", value -> Log.i("PROBE", value));
   webView.evaluateJavascript("window.__morpheAdProbe.summary()", value -> Log.i("PROBE", value));
   ```

## Reading the result

- **Only `PLAYLIST` (+ `TRACKING`) around ad breaks, never `AD-DECISION`/`AD-VOD`**
  → ATV ads are pure SSAI. The current scrubber covers the whole surface; nothing
  to add.
- **`AD-DECISION` or `AD-VOD` fires at an ad break** → there is a client-side path
  the scrubber misses. Capture the full URL + response, then evaluate the
  signal-level seams in `docs/twitch-ad-delivery-map.md` (strip the trigger / empty
  the decision response).

## `adprobe-webview-tap.js` — the ADPROBE front-end for Twitch

This is the Twitch port of **ADPROBE** (`experimental/netflix-adprobe/adprobe-jsvm-tap.js`),
following its four reusable ideas exactly:

1. **Swappable `{k,w,b}` keyword table** — retarget by editing the table, not the logic.
2. **Residency classification (code vs data)** — is the ad discriminator live *code*
   (patchable in-process) or only *data*?
3. **Seam-lock** — collapse many consumers to the single upstream source.
4. **Live-capture discipline** — sample during a real break; `w:0` count keys confirm
   "0 ad segments" after a patch.

### Why it's a new *front-end*, not a copy
ADPROBE's original front-end is a **Frida `rw-` heap scan of the app's own process**,
because Netflix/PV run their JS VM (QuickJS) inside that process. **Twitch ATV doesn't** —
the player (hls.js) runs in the system WebView's **Chromium renderer**, a separate sandboxed
process loaded from the network (proven: the APK ships no player/ad code). A Frida scan of the
Twitch app process would scan the empty shell. So this is a fifth ADPROBE front-end — an
**in-page WebView tap**, injected via the same `evaluateJavascript` seam as `PLAYBACK_FIX_JS`.
It runs *inside* the V8 VM and reads live objects + fetched bodies directly, so it needs no
memory scanning.

### What its residency step will (almost certainly) conclude
In-page JS can't read the cross-origin hls.js bundle *source*, but it can read the *data* the
player fetched (m3u8 bodies) and *live object* state. So for Twitch the ad discriminators
(`twitch-stitched-ad`, EXTINF title, `DATERANGE CLASS`) show up as **`playlist-data`
residency** — which is ADPROBE independently re-deriving *why the seam must be the network
request layer* (`shouldInterceptRequest`), exactly where the shipping scrubber lives. That's
the same move that proved Netflix's old anchor dead: residency tells you which layer is
patchable.

### The decisive extra target
The table includes `X-TV-TWITCH-AD`, `X-TTV-MAF-AD`, and `edge.ads.twitch.tv`. If any of these
ever appear in an ATV body, the tap prints `MORPHE-ADPROBE SEAM: client-side ad source present`
— i.e. a second ad source the playlist scrubber cannot see. That is the seam-lock payoff.

### Run it
Inject the same way as the URL probe (add to `onPageFinished`, or paste via a debug hook):

```java
// after injectPlaybackFix(view):
view.evaluateJavascript(/* contents of adprobe-webview-tap.js */, null);
```

Then, during a stream (ideally across an ad break):

```
adb logcat | grep MORPHE-ADPROBE
```

You'll see per-pass `counts=` / `residency=` lines every 15 s, plus context dumps for the
non-count keys. Or drive passes on demand:

```java
webView.evaluateJavascript("window.__morpheAdprobe.pass()", cb);   // one pass, returns JSON
webView.evaluateJavascript("window.__morpheAdprobe.dump()", cb);   // last hits
```

`adprobe-webview-tap.js` **supersedes** `fetch-xhr-probe.js` for analysis — it captures response
*bodies* and classifies residency, where the URL shim only logged request URLs. Keep the URL
shim for a quick "what hosts does it hit" pass; use the ADPROBE tap for the real seam work.

## Notes / caveats

- WebView mirrors `console.*` to logcat by default; if a build's `WebChromeClient`
  suppresses it, use the `evaluateJavascript` pull path instead.
- URLs are truncated to 300 chars in the buffer to bound memory; logcat shows the
  same truncation. Raise `MAX` / the slice length in the JS if you need full query
  strings.
- Injection is idempotent (`window.__morpheAdProbe` guard), so calling it on every
  page event is safe, exactly like `injectPlaybackFix`.
