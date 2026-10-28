# Twitch Android TV — ad-free playback token (experimental)

How the TV patch gets an **ad-free live stream** instead of blanking stitched ads,
and what to do when Twitch changes something.

Measured **2026-10-26** against `usher.ttvnw.net` / `*.playlist.ttvnw.net` (DE).
Codes live in
[`TwitchAtvWebViewHelper`](../extensions/extension/src/main/java/ajstrick81/morphe/extension/twitchatv/ads/TwitchAtvWebViewHelper.java)
(`isUsherMaster`, `adFreeMaster`, `fetchAdFreeMaster`, `parseAccessToken`, `withToken`, `httpPost`).

---

## The idea

Twitch stitches its SSAI ads into the **`site`** player context only. The *same*
channel requested as **`popout`** (or `mobile_web`, `embed`, `autoplay`, or any
mobile platform) comes back as a **clean playlist**: no
`#EXT-X-DATERANGE ... CLASS="twitch-stitched-ad"` pod and no ad segments. There is
nothing to hide, so nothing has to be blanked.

This is the same *playerType spoofing* approach TwitchAdSolutions' `vaft` uses
(`ForceAccessTokenPlayerType = 'popout'`, backup chain `site → popout →
mobile_web → embed`). It is not segment filtering — the stream simply arrives
without ads.

The TV app asks usher for its own (ad-laden) master playlist. We intercept that
request and answer it with a master obtained under a `popout` token. The variant
URLs are self-contained and signed, so hls.js plays them; the app cannot tell the
difference.

---

## Measurements (2026-10-26)

First window of the media playlist for `riotgames`, one fresh token per row:

| platform / playerType | `twitch-stitched-ad` | `live` segments |
| --- | --- | --- |
| `web` / **`site`** | 1 (30.2 s preroll) | 0 |
| `web` / **`popout`** | 0 | 15 |
| `web` / `mobile_web` | 0 | 15 |
| `web` / `embed` | 0 | 15 |
| `web` / `autoplay` | 0 | 15 (360p only) |
| `android`, `ios`, `android_tv`, `ios_tv` / `site` | 0 | 15 |

Master playlists for `site` and `popout` are **structurally identical**: same 6
variants, same `#EXT-X-MEDIA` groups (source/720p60/720p30/480p30/360p30/audio),
same resolutions up to 1920x1080. Only the ad pods differ — which is why swapping
the response is safe for the player.

The `site` preroll is what caused the 20–30 s black screen at stream start
(upstream issue #253): the blanking path can only replace ad segments with black,
and during a preroll the entire playlist window *is* the ad. With a `popout` token
there is no ad window at all.

---

## Code path

```
shouldInterceptRequest(url)
├─ url contains morphe.invalid          → blank TS for blanked ad segments
├─ isUsherMaster(url)                   → adFreeMaster(url)   (host == usher.ttvnw.net,
│    │                                                         path /api/channel/hls/*.m3u8)
│    ├─ channelFromUsher(url)           → "<login>" (strict [A-Za-z0-9_]{1,64})
│    ├─ worker thread, whole chain capped at AD_FREE_BUDGET_MS (3 s):
│    │  for playerType in {popout, mobile_web}
│    │    ├─ POST https://gql.twitch.tv/gql   (Client-ID: public web client)
│    │    │     streamPlaybackAccessToken(channelName, platform=web,
│    │    │                                 playerBackend=mediaplayer, playerType)
│    │    │     → parseAccessToken(): org.json, data.streamPlaybackAccessToken.{value,signature}
│    │    └─ GET  the app's own usher URL with ONLY sig + token replaced (withToken):
│    │          every other param the app sent (supported_codecs, platform, …) is kept
│    └─ body contains #EXT-X-STREAM-INF      → return it as the master playlist
└─ isWeaverPlaylist(url)                → scrubPlaylist(url)  (blanking fallback)
```

Every step **fails open**: missing token, non-200, no `#EXT-X-STREAM-INF`, weird
channel name, or the 3 s budget running out → `null` → the app's own response is
used, and the weaver blanking handles whatever ads are stitched.

Caveats on that guarantee:
- While it tries, the app's master-playlist request is **held** (`shouldInterceptRequest`
  runs off the UI thread but the WebView waits for its answer). Normally that is one
  GQL + one usher round-trip; the hard cap is 3 s, after which playback proceeds as before.
- `#EXT-X-STREAM-INF` only proves the body *looks like* a master playlist, not that
  its variants play or are ad-free. If `popout` returns a valid but ad-bearing master,
  `mobile_web` is not tried; the weaver blanking still handles the ads.

There is no cache: the master is requested once per player session, and caching a
short-lived token would risk serving an expired playlist.

---

## Known limits

- **Mid-rolls are unverified.** The table above covers the session start only. If
  a mid-roll is still stitched in the `popout` context, the weaver blanking path
  catches it (black, as before) — no regression, just no improvement there.
- **Client-ID.** The GQL call uses the public web client ID
  (`kimne78kx3ncx6brgo4mv6wki5h1ko`), the same one TTV LOL PRO and vaft rely on.
  Twitch can rate-limit or invalidate it; the patch then falls back to blanking.
- **ToS.** Requesting a different player context is the standard ad-blocker trick,
  but it is a grey area — same category as VPN-to-Albania.
- **Quality** should be unchanged: the swapped request keeps every parameter the app
  sent (codec / capability hints) and replaces only `sig` and `token`; in the
  2026-10-26 measurements source/1080p60 was available. Not yet confirmed on-device
  after that change. `autoplay` is deliberately
  *not* in the chain: it is 360p only. Add it at the end of
  `AD_FREE_PLAYER_TYPES` if you ever need a last resort.

---

## Debugging

Log tag: `MORPHE-TW-ATV-WV`.

| Line | Meaning |
| --- | --- |
| `ad-free master playlist via playerType=popout for <channel>` | token swap worked |
| `gql: no token for <channel> (<type>)` | GQL refused → fallback |
| `blanked stitched-ad segments in weaver playlist` | fallback path was used |
| `gql request failed: …` | network/HTTP error → fallback |

On a device with ADB:

```
adb logcat -s MORPHE-TW-ATV-WV
```

No ADB? The practical test is behavioural: with the swap working there should be no
preroll black window; if you see the ~30 s black window again, the swap is failing
(or timed out after 3 s) and the blanking fallback took over.

---

## Re-checking after a Twitch change

```bash
# 1. ad-laden (site) vs ad-free (popout) for any live channel
#    see the helper scripts used for the table above; the essentials are:
#    POST gql.twitch.tv/gql  (Client-ID: kimne78kx3ncx6brgo4mv6wki5h1ko)
#      query{streamPlaybackAccessToken(channelName:"<login>",
#        params:{platform:"web",playerBackend:"mediaplayer",playerType:"popout"})
#        {value signature}}
# 2. GET usher.ttvnw.net/api/channel/hls/<login>.m3u8?…sig=…&token=…
# 3. GET the first variant, then look for "twitch-stitched-ad"
```

If `popout` starts returning ad tags, try `mobile_web`, `embed`, or
`platform: "android"` and reorder `AD_FREE_PLAYER_TYPES`.
