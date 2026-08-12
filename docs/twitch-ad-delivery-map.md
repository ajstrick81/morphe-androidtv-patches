# Twitch Ad Delivery Map

A reference inventory of Twitch's ad-delivery mechanisms, reconstructed from a
static read of the **mobile** Twitch app (`tv.twitch.android.app`, native
ExoPlayer-based player — five `classes*.dex`, manifest confirms the consumer app
with the full broadcast/gamebroadcast feature set).

> **Scope caveat.** This is the *mobile* app, not the Android-TV Starshot WebView
> app the current `twitchatv` patch targets. The mobile player is native (no
> `WebViewClient.shouldInterceptRequest` seam) and its ad surface is richer than
> ATV's. Use this as "what Twitch's full ad system looks like," and as the map to
> work from **if/when the project targets the mobile app**. Everything below is
> derived from string/identifier evidence in the dex — treat class/flow details as
> strong hypotheses to confirm on-device, not disassembled ground truth.

---

## The six delivery paths

Twitch does not have "one ad system." At least six distinct paths coexist, and
manifest scrubbing (what the ATV patch does) only touches **one** of them:

| # | Path | How it arrives | Blockable by manifest scrub? |
|---|------|----------------|------------------------------|
| 1 | **SSAI / SureStream** | Ad segments *stitched into* the HLS media playlist | Yes — this is what the ATV patch blanks |
| 2 | **Client-side video ads (VAST/MAF)** | Native player fetches `edge.ads.twitch.tv/.../ads`, plays via a *separate* `ClientVideoAdPlayer` | **No** — never in the manifest content |
| 3 | **VOD archive midrolls** | `edge.ads.twitch.tv/.../vod-ads`, client-scheduled cadence | No |
| 4 | **Display / in-feed ads (OpenRTB)** | `DisplayAdContainer`, `OpenRtbDisplayAdPod` | No |
| 5 | **Audio ads** | `AudioAd`, `AudioAdsPod`, `AudioVast` | No |
| 6 | **PbYP ("picture-by-picture") midrolls** | Ad plays in a corner while stream continues; `PbypMidrollRequest`, `PbypPresenter` | No |

### 1. SSAI / SureStream (server-stitched)
Same primitives the ATV patch already keys on: `twitch-stitched-ad`, `STITCHED`,
`HAS_SURESTREAM`, quartile beacons (`OnSurestreamAdQuartile`), and a dedicated
beacon sender `SureStreamTrackingApi`. Model: `SureStreamAdMetadata(duration=…)`,
`SureStreamVerification(adId=…)`. The ad video is *baked into the segments*, so
the only content-level defense is blanking/replacing those segments (the current
approach). Stripping signalling tags alone does **not** remove a baked-in SSAI ad.

### 2. Client-side video ads (VAST / Multi-Ad-Format)
The path the ATV approach has **no equivalent for**, and structurally invisible to
manifest scrubbing:

```
HLS media playlist carries X-TV-TWITCH-AD-* / X-TTV-MAF-AD-* metadata tags
        │  (or a PubSub midroll_request / StreamCommercialEvent arrives)
        ▼
AdEdgeApi → GET https://edge.ads.twitch.tv/2018-01-01/ads?…&radsToken=…
        ▼
VAST parse (WrapperAdApi, Creative, CompanionAd, AdVerification)
        ▼
ClientVideoAdPlayer (separate player: CreateAdPlayer, preloadAdPlayer,
        ClientVideoAdPlayerStateProcessor.startAdPlayback)
```

"MAF" = Multi-Ad-Format (`MultiAdFormatRequest`, `MultiAdFormatMetadata`,
`OnMultiAdFormatVideoRequestReturned`, `IS_MAFS`). This is the modern client-side
pod. Because the creative is fetched *out of band* and played by a *second*
player, the content manifest never contains it — so you block it at the **request**
or **signal** layer, not the content layer.

### 3–6
- **VOD midrolls:** `edge.ads.twitch.tv/2018-01-01/vod-ads`, cadence configurable
  client-side (`vodArchiveMidrollFrequencyMinutes`,
  `vodArchiveMidrollBreakLengthSeconds`, `VodMidrollType`).
- **Display / in-feed:** `DisplayAdContainer`, `OpenRtbDisplayAdPod`,
  `BrowseDisplayAdResponse`, `InFeedAdImpressionTrackingInfo`.
- **Audio:** `AudioAd`, `AudioAdsPod`, `AudioVast`.
- **PbYP:** `PbypMidrollRequest`, `X-NET-LIVE-VIDEO-METADATA-PBYP-*` tags.

---

## Signalling layer — *how a midroll is triggered*

Three trigger surfaces feed the client-side ad paths. This is where midrolls are
most cleanly "knocked out" (see strategy section):

1. **PubSub push.** `ChannelAdsPubSubEvent` with a `MidrollRequestType`;
   `StreamUpdatePubSubEvent$StreamCommercialEvent`; payload keys `midroll_request`,
   `run_commercial`, `usage_commercial`. The server *tells* the client to run a
   midroll — independent of manifest stitching.
2. **Custom HLS metadata tags.** The media playlist carries out-of-band ad-pod
   descriptors the native player reads to fire a client-side fetch
   (`HlsMidrollRequest`). Full tag inventory below.
3. **Commercial settings / capability flags.** `CommercialSettingsModel`,
   `DisablePrerollsAbility`, `getPrerollsDisabled`, `hasDisablePrerollsAbilityAccess`,
   `ForcePrerollsChanged`. Note the **app already contains a native "prerolls
   disabled" capability** (the Turbo/sub perk) — a lever worth studying before
   writing any stripping code.

### Custom HLS ad-metadata tags (the client-side trigger payload)
Present in the media playlist to drive the native ad pod. Stripping these is the
"neutralize the signal" seam:

```
X-TV-TWITCH-AD-COMMERCIAL-ID        X-TTV-MAF-AD-COMMERCIAL-ID
X-TV-TWITCH-AD-ROLL-TYPE            X-TTV-MAF-AD-DECISION
X-TV-TWITCH-AD-AD-FORMAT            X-TTV-MAF-AD-PRIMARY-POD
X-TV-TWITCH-AD-AD-SESSION-ID        X-TTV-MAF-AD-AD-SESSION-ID
X-TV-TWITCH-AD-POD-LENGTH           X-TTV-MAF-AD-RADS-TOKEN
X-TV-TWITCH-AD-POD-POSITION         X-TTV-MAF-AD-SDA-SEQUENCE-LENGTH
X-TV-TWITCH-AD-POD-FILLED-DURATION
X-TV-TWITCH-AD-URL                  X-NET-LIVE-VIDEO-METADATA-TYPE
X-TV-TWITCH-AD-CLICK-TRACKING-URL   X-NET-LIVE-VIDEO-METADATA-PBYP-COMMERCIAL-ID
X-TV-TWITCH-AD-QUARTILE             X-NET-LIVE-VIDEO-METADATA-PBYP-JITTER-BUCKETS
X-TV-TWITCH-AD-ADVERIFICATIONS      X-NET-LIVE-VIDEO-METADATA-PBYP-JITTER-TIME
X-TV-TWITCH-AD-RADS-TOKEN           X-NET-LIVE-VIDEO-METADATA-PBYP-WARMUP-TIME
X-TV-TWITCH-AD-CREATIVE-ID          X-NET-LIVE-VIDEO-METADATA-PBYP-WEIGHTED-BUCKETS
X-TV-TWITCH-AD-{ADVERTISER,ORDER,LINE-ITEM}-ID
X-TV-TWITCH-AD-DSA-*                (Digital Services Act disclosure metadata)
```

---

## Endpoint / host inventory

| Host / path | Role |
|-------------|------|
| `edge.ads.twitch.tv/2018-01-01/ads` | Live client-side ad decisioning (VAST/MAF) |
| `edge.ads.twitch.tv/2018-01-01/vod-ads` | VOD midroll decisioning |
| `aax.*.amazon-adsystem.com` | Amazon ad demand |
| `fw.adsafeprotected.com/rjss/st/` | IAS viewability verification |
| `pagead2.googlesyndication.com` | Google verification / gen_204 |
| `SPADE_URL` (`x_untrusted_minute-watched_spade`) | Twitch analytics/telemetry beacon bus |
| `*.playlist.ttvnw.net` (video-weaver) | HLS media playlist (SSAI stitch target) |

---

## Knocking out midrolls — strategy notes

The project has already learned (Prime Video, Netflix) that there are **two
fundamentally different levers**, and the right one depends on *where the ad video
lives*:

- **Content-level (strip/blank the ad video).** Required when the creative is
  *inside* the stream you're playing — i.e. **SSAI/SureStream (path 1)**. The ATV
  patch's segment-blanking is exactly this. It's unavoidable for baked-in ads and
  it's the expensive path (PTS re-stitching, stall recovery).

- **Signal-level (neutralize the trigger so no ad is ever fetched).** Available
  when the ad is fetched *out of band* by the client — **paths 2/3/6**. This is the
  same shape as the Netflix/Prime ad-break-*state* neutralization: you don't touch
  ad content, you remove the instruction that starts the ad break. For Twitch
  midrolls there are three candidate seams, cheapest first:

  1. **Strip the `X-TV-TWITCH-AD-*` / `X-TTV-MAF-AD-*` metadata tags** from the
     media playlist before the player parses it. No commercial-id / decision / URL
     → `HlsMidrollRequest` has nothing to act on → no `edge.ads.twitch.tv` fetch.
     Cheap, content-preserving, and (unlike SSAI blanking) leaves the video
     timeline completely untouched. **This is the most promising "another way."**
  2. **Drop the PubSub `midroll_request` / `StreamCommercialEvent`** before it
     reaches the ad coordinator — kills server-pushed midrolls that don't ride the
     manifest.
  3. **Blackhole `edge.ads.twitch.tv`** (empty/`204` the `ads` + `vod-ads`
     responses). Coarsest but simplest; the VAST parser already has a documented
     "No Ads VAST response" path, so an empty decision should degrade gracefully to
     "no ad." Watch for retry/timeout stalls.

  The elegant version combines #1 with a graceful "no-fill" response so the ad
  coordinator believes the break ran and completes cleanly — mirroring why the ATV
  patch *blanks* rather than *deletes* (let the state machine finish).

### Why this matters vs. the current approach
On the mobile app, **manifest scrubbing alone covers only path 1.** A real
midroll on mobile is very likely a client-side MAF pod (path 2), which slips past
segment blanking entirely. Signal-stripping (#1 above) is both *cheaper* and
*covers the path SSAI blanking can't* — the opposite of the ATV situation, where
SSAI is the whole game.

---

## Actionable next checks (on-device, not static)

1. **Does the ATV Starshot app ever hit `edge.ads.twitch.tv/2018-01-01/ads`?** The
   ATV patch assumes prerolls arrive *stitched* (handles `ROLL-TYPE="PREROLL"` in
   the manifest). If ATV also fetches client-side ads, some are slipping past the
   scrubber. One packet capture answers this.
2. **On mobile, capture a real midroll's media playlist** and confirm the
   `X-TV-TWITCH-AD-*` tags appear inline — that validates the tag-strip seam before
   any code is written.
3. **Probe `DisablePrerollsAbility`** — if the native flag can be forced on, that
   may suppress prerolls with zero manifest surgery.

> Build reactively: confirm each seam against a captured request before
> implementing. This doc is the map, not the territory.
