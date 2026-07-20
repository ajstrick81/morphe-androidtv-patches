# Prime Video — DNS Ad-Suppression Layer

This is the **DNS half of a dual-layer ad-suppression strategy** for Prime Video
on Android TV. It is the companion to the bytecode patch (`skipAds`), not a
replacement for it.

## Why two layers?

Prime Video chooses an ad-delivery architecture **server-side, per session**
(keyed on device type, title, account, region, and A/B bucket). There are two
"roads", and they require different tools:

| Road | How the ad arrives | Reached by |
|---|---|---|
| **Java Pipeline** | Ad break signalled to the client as a media3 `AdPlaybackState` ad group | **Bytecode patch** (`skipAllMedia3AdGroups`) |
| **Native Pipeline** | Ad stitched server-side (SGAI) and delivered through Amazon's **native** pipeline over **native libcurl** | **DNS blocking** (this list) |

The bytecode patch operates at the Java layer and **cannot see** native-road
traffic — on-device logcat confirmed Prime Video's ad/media/API plane runs
through native `libcurl` (`DOWNLOADER`) and a native media pipeline
(`NativeMediaPipelineBackend`), not Java `Volley`/`DefaultHttpDataSource`.

DNS blocking works **below HTTP**, so it reaches that native traffic just as
well as anything. Conversely, DNS is **host-level only** — it cannot block an ad
*path* on a dual-use host (e.g. `atv-ps.amazon.com/cdp/getVideoAds`, which also
serves the Widevine license). That path is exactly what the patch handles.

**Each layer covers the road the other cannot. Run both.**

## What this list is calibrated for

Every rule is tagged with the capture that confirmed it (`[ATV]`, `[PC]`,
`[PIH]`, `[PCAP]` = TLS SNI from packet captures, `[v3.6]`, `[UNV]` =
unverified). It is deliberately **more conservative
on CDNs than the older v3.6 list**, because our packet/HAR evidence proved v3.6
would block real movie content:

- v3.6 null-routed `*vod-dash-pv-ta-amazon.akamaized.net` and
  `*.vod-dash.main.amazon.pv-cdn.net`. Our PC capture caught those exact hosts
  **serving the movie**. CDN shard assignment is non-deterministic, so host/
  prefix blocking on the shared VOD CDNs will eventually break playback. They are
  now in **safe harbor**, and the only way to strip ad *segments* from a shared
  CDN reliably is at the path/manifest layer (the patch), not DNS.
- `s0s7.api.amazonvideo.com` returned HTTP 304 as a live playback API in our
  capture, so it is no longer blocked by default.

## Tiers

1. **Safe harbor** (`@@ ... $important`) — content/DRM/playback/subtitles. Always
   on, always wins.
2. **Tier 1** — high-confidence pure ad hosts. Recommended default.
3. **Tier 2** — aggressive/opt-in, **commented out**. Real content-risk or
   unverified. Enable one at a time and watch for breakage.
4. **Telemetry** — privacy only; does not affect ads.
5. **General trackers** — non-Prime mobile ad/analytics SDKs seen in-session.
   A convenience baseline only; see "Pair with a general list" below.

## Pair with a general list (important)

This list is intentionally **scoped to Prime Video**. The third-party trackers
that show up alongside it (Google beacons, Facebook, Glance, Kochava, MoEngage,
etc.) are generic and are far better handled by a **maintained general
blocklist** that stays current. If you run only this list, add one beside it:

- **HaGeZi Multi** (recommended balance) or **Multi Pro** —
  `https://github.com/hagezi/dns-blocklists`

Section 5 of `prime-video.txt` is only a small snapshot for users who can't add
a general list; it is not a substitute for one, and it deliberately omits hosts
that are risky to block (NTP time sync, Amazon device gateways).

## Usage

### AdGuard Home (the list's native format)
1. **Filters → DNS blocklists → Add blocklist → Add a custom list.**
2. Paste the raw URL of `dns/prime-video.txt` (or upload its contents).
3. Ensure your default blocking mode returns `0.0.0.0`/refused (the `$dnsrewrite`
   rules force this anyway, which gives a clean fast-fail instead of NXDOMAIN
   retry storms).

### Pi-hole
Pi-hole does not parse `$dnsrewrite`/`$important`. Two options:
- **Adlist (ABP mode):** enable Pi-hole's ABP-style adlist support, then the
  plain `||host^` rules are honored; strip the `$...` modifiers.
- **Regex/exact:** translate the key rules manually, e.g.
  - Block: `amazon-adsystem.com` and the Akamai **HD** ad hosts
    (`avoddashs3ww-a.akamaihd.net`, `aivottevtad-a.akamaihd.net`, `*evtad*.akamaihd.net`).
  - Allowlist (critical): **`aiv-delivery.net` — all regions** (this is the SGAI
    *session* host, contacted for every title; blocking any region, e.g.
    `api.eu-west-1.aiv-delivery.net` for EU or `api.us-east-1.aiv-delivery.net` for
    NA, **stops playback entirely** — issue #58). Also: `atv-ps.amazon.com`,
    `vod-dash.main.amazon.pv-cdn.net`, `vod-dash-pv-ta-amazon.akamaized.net`,
    `aux.pv-cdn.net` (subtitles + trickplay), `aiv-cdn.net`,
    `images-na.ssl-images-amazon.com`, `m.media-amazon.com`

> ⚠️ **Do not block `aiv-delivery.net`.** Earlier versions of this guide (and the
> v3.6 list) listed it under *Block* — that is what breaks playback. It is now a
> permanent allow. If ads still play after allowing it, they are SSAI-baked
> (server-stitched); DNS cannot remove those — use the bytecode patch or the
> phone-app route.

> The `threeplr*`/`nit*` prefix rules need wildcard/regex support; in Pi-hole use
> a regex blacklist: `^(threeplr|nit)[a-z0-9.-]*\.api\.amazonvideo\.com$`.

## Verifying it works

- **DNS side:** watch your resolver log during playback. You want
  `amazon-adsystem.com` and the `*evtad*.akamaihd.net` ad hosts **blocked**, and the
  content/session hosts **allowed** — `amazonvideo.com`, `*.pv-cdn.net`, and
  crucially **`aiv-delivery.net` (all regions)**. `aiv-delivery.net` is the SGAI
  *session* host contacted for every title: if it shows **blocked**, playback will
  not start (issue #58).
- **If a title stalls / black-screens and `aiv-delivery.net` (e.g.
  `api.eu-west-1.aiv-delivery.net`) shows blocked** even though this list allows it:
  a rule from **another subscribed blocklist is overriding the allow**. Fix the
  precedence — put the safe-harbor allows in **User Rules**, which outrank every
  subscribed list:
  - **AdGuard Home:** *Filters → Custom filtering rules*, paste
    `@@||aiv-delivery.net^$important` (and the other `@@…$important` lines).
  - **Pi-hole:** add `aiv-delivery.net` (and the other critical hosts above) to the
    **Allowlist**, which always wins over adlists.
- **Patch side:** with DNS blocking **off**, `logcat | grep -i skipads` during an
  ad title tells you whether the Java-road hooks are firing.
