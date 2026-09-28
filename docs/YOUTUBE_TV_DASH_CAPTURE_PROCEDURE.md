# YouTube TV — On-Device DASH / Ad-Signal Capture Procedure

The single measurement that sets the ceiling for the whole ad-suppression effort:
**of the ads YouTube TV plays, how many are client-fetched (removable by emptying
the `AdSlotRenderer` / `CuePoint` accessor) versus server-welded (SSAI) into the
stream (not removable in bytecode)?**

This procedure captures a live YouTube TV session so we can answer it with
evidence instead of assumption. Pair it with
[`YOUTUBE_TV_SCTE35_REANALYSIS.md`](YOUTUBE_TV_SCTE35_REANALYSIS.md) (what to look
for) and [`SCTE35_AD_SIGNALING_REFERENCE.md`](SCTE35_AD_SIGNALING_REFERENCE.md)
(what the markers mean).

> **Scope / ethics:** capture **your own** authenticated session on hardware you
> own, for interoperability/ad-research on content you're entitled to watch. Don't
> redistribute captured segments or credentials. Keys logged here decrypt *your*
> session only.

---

## What we're trying to observe

| Signal | Where it appears | Tells us |
|---|---|---|
| **DASH `<Period>` ad breaks** with `<EventStream schemeIdUri="urn:scte:scte35:…">` | `.mpd` manifest | Server-signaled SCTE-35 seam survived to the client → filterable at manifest level. |
| **`AdSlotRenderer` / `CuePoint` / `InstreamAdBreak`** payloads | InnerTube `player`/ad-break responses (JSON/protobuf) | Client-guided (SGAI) fill → the "empty the accessor" lever works. |
| **Welded ad segments** | media segments inside a normal Period, no separate ad Period | Pure SSAI → bytecode can't remove these bytes (residual wall). |
| **Distinct ad CDN hosts / URL patterns** | request logs | Whether ad fill is even separable by origin. |

The ratio of the middle two rows to the last row **is the answer.**

---

## The Cronet problem (read first)

YouTube's networking is **Cronet** (native Chromium, `libcronet…so` in the v7a
split), *not* Java `OkHttp`. Consequences:

- **Java/VM hooks miss the traffic.** OkHttp/HttpURLConnection interceptors and
  most Frida-on-Java network hooks will not see the manifest/segment/player-response
  fetches — they happen below the VM.
- **You must intercept at the TLS/network layer** (system proxy + user CA), and
  YouTube pins, so expect to defeat pinning too.

But there's a better insight for **our** case: the
[reanalysis](YOUTUBE_TV_SCTE35_REANALYSIS.md) found the manifest + ad-decisioning
pipeline is **Java (`androidx.media3`) in bytecode.** So we don't have to break
TLS at all — we can read the `.mpd` and the `adSlotRenderer`/`cuepoint` payloads
**from inside the app, after Cronet decrypts them.** That's what makes a
**rootless** capture possible on an unrooted Onn.

---

## Prior art — we already proved rootless capture on the Onn

The Onn TV is **not rooted**, and it doesn't need to be. Two rootless methods are
already established in this repo — reuse them, don't reinvent:

- **`testing/pluto-runbook.md`** — rootless MITM: patch the app's
  `network_security_config.xml` to add `<certificates src="user"
  overridePins="true">`, then MITM the manifest with an HTTPS-filtering proxy
  (AdGuard/mitmproxy). The Onn shows an *"Allow debugging?"* prompt on first
  `adb connect` — accept it. (Caveat there: Pluto has **no** active pinning;
  YouTube TV **does**, so budget for the `overridePins`/gadget step.)
- **`experimental/netflix-native-adstrip/frida/README.md`** + **`HANDOFF.md`** —
  rootless **frida-gadget**: inject `lib/armeabi-v7a/libgadget.so` +
  `libgadget.config.so` into the APK, add `System.loadLibrary("gadget")`, re-sign,
  capture in-process. No root, no CA, and it can unpin from inside.

## Choosing a method (rootless first)

| # | Method | Root? | Sees payload? | Best for |
|---|---|---|---|---|
| **0** | **In-app instrumentation ("tap") patch** (extends the gadget/RE toolchain) | ❌ no | ✅ full (post-decrypt, in-process) | **Recommended.** We own the APK anyway; log the exact objects we care about. |
| A | mitmproxy + **user**-CA `overridePins` + gadget unpinning (repackaged APK) | ❌ no | ✅ full | Proven on the Onn (Pluto runbook); use for wire-format flows. |
| A′ | mitmproxy + **system**-CA + Frida | ✅ yes | ✅ full | Only if you already have a rooted box/AVD. |
| B | pcap + TLS keylog | ✅ yes (for keylog hook) | ✅ full | Fallback when pinning is stubborn. |
| C | Router/pcap, **no** decryption | ❌ no | ❌ hosts/sizes only | Coarse complement (ad-CDN volume). |

**On the unrooted Onn, use Method 0 or A.** Both are rootless. Method 0 needs no
CA and its tap code is a first draft of the suppression patch; Method A is the
already-proven MITM path. Methods A′/B require root and are here only for
completeness.

> ⚠️ **Two Onn walls we already hit (from the Netflix `HANDOFF.md`) — expect them
> for YouTube TV too:**
> 1. **Preinstalled system app → can't replace on non-root.** If YouTube TV ships
>    baked into the Onn image, an in-place re-signed install fails
>    (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — retained data keeps the stock signer).
>    Fix that worked before: a **package-rename clone** installed *alongside* stock,
>    so there's no signer/system collision. Check first:
>    `adb shell pm list packages -s | grep -iE "youtube|unplugged"` (‑s = system).
> 2. **Integrity / DRM after re-sign.** Netflix needed a DexGuard `CertCheck`
>    bypass; YouTube TV enforces **Play Integrity** + **Widevine**, so a re-signed
>    build may refuse login or drop to L3/SD. Mitigate with a throwaway account,
>    expect SD, and verify playback *before* trusting the capture. If integrity
>    blocks playback outright, that's itself a finding → fall back to Method C plus
>    on-screen ground truth.

---

## Method 0 — In-app instrumentation tap (rootless, recommended)

Idea: add a tiny debug patch that logs, to `logcat`/a file, the three things the
capture is meant to measure — the **DASH manifest**, the **ad-slot/cuepoint
payloads**, and **ad-break lifecycle events** — read straight from the Java
objects the reanalysis located. No proxy, no CA, no pinning.

**Hook points** (pin exact obfuscated methods in the disassembly pass; anchor on
the strings already found):

| What to log | Anchor (from reanalysis) | dex |
|---|---|---|
| Raw `.mpd` / manifest bytes | `MediaPushMediaSource` / media3 DASH manifest parser input | classes3 |
| SCTE-35 cues as parsed | `application/x-scte35` handler, ExoPlayer scte35 command `toString()` | classes2/3 |
| Ad-slot selection | `SelectionResult{adSlotRenderers=…}`, `createAdBreakRequestSlotFromCuePoint()` | classes3 |
| Ad breaks built | `InstreamAdBreak: [breakType, adBreakIndex, offset, originalVideoId]` | classes/classes3 |
| Fulfillment status | `nativeOnAdBreakFulfillmentStatusChanged` (log the call + args) | native bridge |

**Build/run (rootless, ADB sideload):**
```bash
# 0. Onn: Settings → System → About → tap Build 7×; enable ADB debugging.
adb connect <ONN_IP>:5555
adb shell pm list packages | grep -iE "youtube|unplugged"   # confirm package id

# 1. Patch: inject the tap (ReVanced-style, this repo's toolchain) OR, quick-n-dirty,
#    baksmali → add Log.i(...) at the anchors → smali → rebuild.
# 2. Re-sign ALL splits with the SAME key (base + armeabi_v7a + dpi/lang splits):
apksigner sign --ks debug.ks base.apk
apksigner sign --ks debug.ks split_config.armeabi_v7a.apk
# 3. Install the whole split set atomically:
adb install-multiple base.apk split_config.armeabi_v7a.apk <other-splits...>

# 4. Capture the tap while you watch a live channel + an ad break:
adb logcat -v time | grep -E "SCTE|AdSlot|CuePoint|InstreamAdBreak|MPD|Fulfillment" \
  | tee onn_tap_$(date +%s).log
```
Because the tap runs *inside* the decrypted pipeline, the log lines already
contain the manifest XML and the ad payloads — feed them straight into
[Decoding the SCTE-35](#decoding-the-scte-35-you-capture) and the
[decision table](#decision-table-what-the-capture-proves). No TLS work at all.

> This is also why Method 0 is efficient: the "tap" patch **is** the scaffold for
> the suppression patch — once it shows us the accessor contents, emptying that
> same accessor is the fix.

---

## Prerequisites (Methods A / A′ / B)

- **Rooted Android TV device or emulator** (rooted AVD `x86_64`, or a rooted
  physical ADB-enabled ATV box). Root is needed to install a **system** CA and to
  run Frida for unpinning.
- Host tools: `adb`, `mitmproxy` (or Charles), `frida` + `frida-server` matching
  device arch, `ffprobe`/`ffmpeg` (segment inspection), `python3`.
- A SCTE-35 binary decoder for the base64/hex payloads:
  [`threefive`](https://github.com/futzu/threefive) (`pip install threefive`) or
  the Bitmovin payload parser referenced in the guide.
- The two splits already analyzed, for cross-referencing class names:
  `base.apk`, `split_config.armeabi_v7a.apk`.

---

## Method A — mitmproxy + user-CA `overridePins` + gadget unpinning (rootless)

*Rootless variant (recommended for the Onn):* instead of a system CA, patch the
app's `network_security_config.xml` to trust user certs and override pins
(`<certificates src="user" overridePins="true">`, per the Pluto runbook), install
the mitmproxy CA to the **user** store, and — because YouTube pins in native
Cronet — also run the gadget unpinning script in-process. Steps 1/4/5 below apply
unchanged; swap the system-CA step (2–3) for the user-CA + `overridePins` patch.

*Root variant (only if you have a rooted box):*

### 1. Point the device at the proxy
```bash
# host: start mitmproxy with a capture script (below) writing a flow file
mitmweb --listen-port 8080 -s scte_tap.py -w yttv_flows.mitm

# device: route traffic through host:8080
adb shell settings put global http_proxy <HOST_IP>:8080
# (or set the Wi-Fi proxy in ATV settings; some ATV builds ignore the global one)
```

### 2. Install mitmproxy's CA as a **system** cert (root)
```bash
# mitm CA lives at ~/.mitmproxy/mitmproxy-ca-cert.cer
HASH=$(openssl x509 -inform PEM -subject_hash_old -in ~/.mitmproxy/mitmproxy-ca-cert.cer | head -1)
cp ~/.mitmproxy/mitmproxy-ca-cert.cer ${HASH}.0
adb root && adb remount
adb push ${HASH}.0 /system/etc/security/cacerts/
adb shell chmod 644 /system/etc/security/cacerts/${HASH}.0
adb reboot
```
(User-store certs are ignored by apps targeting modern API levels — it must be in
the **system** store.)

### 3. Defeat certificate pinning with Frida
```bash
# device: run frida-server (matching arch: v7a build → armeabi-v7a server)
adb push frida-server /data/local/tmp/ && adb shell "chmod 755 /data/local/tmp/frida-server"
adb shell "/data/local/tmp/frida-server &"

# host: launch YouTube TV under a pinning-bypass script
frida -U -f com.google.android.youtube.tv -l unpin.js
```
Start from a maintained universal unpinning script (e.g. httptoolkit's
`frida-android-unpinning`). Cronet's verifier is native, so ensure the script
covers **Cronet / BoringSSL** (`SSL_CTX_set_custom_verify` / `X509_verify_cert`),
not just Java `TrustManager`. If pinning holds, fall through to **Method B**.

> Package id note: confirm the id on the device — `adb shell pm list packages | grep -iE "youtube|unplugged"`. Android TV YouTube TV is commonly `com.google.android.youtube.tv`; verify before targeting.

### 4. The capture/triage script (`scte_tap.py`)
Flags the flows that matter so you don't scrub thousands by hand:
```python
# mitmproxy -s scte_tap.py
from mitmproxy import http

def response(flow: http.HTTPFlow):
    url = flow.request.pretty_url
    ct  = flow.response.headers.get("content-type", "")
    body = flow.response.get_text(strict=False) or ""

    # DASH manifests
    if url.endswith(".mpd") or "dash" in ct or "<MPD" in body[:200]:
        n_ad = body.count("urn:scte:scte35")
        print(f"[MPD] {url}  scte35_periods={n_ad}")
        if n_ad:
            open("mpd_%d.xml" % flow.id.__hash__(), "w").write(body)

    # InnerTube player / ad-break responses (client-guided fill)
    if "youtubei" in url and ("/player" in url or "ad_break" in url or "get_ads" in url):
        hits = [k for k in ("adSlotRenderer","cuepoint","instreamAdBreak",
                            "adPlacementRenderer","playerAds") if k in body]
        if hits:
            print(f"[ADS] {url}  keys={hits}")
            open("ads_%d.json" % flow.id.__hash__(), "w").write(body)

    # Candidate ad media segments (heuristic on host/path)
    if any(h in url for h in ("googlevideo.com","doubleclick","/videoplayback")) \
       and ("ctier=" in url or "&oad" in url or "/ad/" in url):
        print(f"[SEG?] {url[:120]}")
```

### 5. Watch a real ad break and log ground truth
- Play a live channel; **wall-clock note** every ad break (start/end, how many
  spots, whether the seek bar locks).
- Correlate those timestamps against the `[MPD]` / `[ADS]` / `[SEG?]` console
  lines. This is what maps *"an ad played"* to *which mechanism delivered it.*

---

## Method B — pcap + TLS keylog (fallback when pinning wins)
If unpinning Cronet is too costly, capture ciphertext and decrypt with the
session keys instead of MITM:
```bash
# device: capture on the wire
adb shell "tcpdump -i any -s0 -w /sdcard/yttv.pcap" &
# export TLS secrets — needs a Cronet/BoringSSL SSLKEYLOGFILE hook via Frida
#   (hook SSL_CTX_new → SSL_CTX_set_keylog_callback, append lines to a file)
adb pull /sdcard/yttv.pcap ; adb pull /data/local/tmp/keylog.txt
wireshark -o tls.keylog_file:keylog.txt yttv.pcap   # filter: http2 / http3(QUIC)
```
Note YouTube uses **HTTP/3 (QUIC)** heavily — make sure the keylog covers QUIC or
force HTTP/2 (`Cronet_ForceHttpEngineInFallback`-style flags / block UDP 443) so
Wireshark can decrypt.

---

## Decoding the SCTE-35 you capture
For each `<EventStream>`/`#EXT-X-DATERANGE` payload:
```bash
python3 -c "import threefive, sys; threefive.decode(sys.argv[1])" "<base64-or-hex>"
```
Read off `command` (`time_signal` vs `splice_insert`), and the
`segmentation_type_id` (map via the reference doc's table — e.g. `0x36/0x37`
Distributor Placement Opportunity = the regional slot), plus
`segmentation_duration`. This confirms *what kind* of break it is and whether it's
the distributor seam we expect on YouTube TV.

---

## Decision table (what the capture proves)

| Observation | Conclusion | Action |
|---|---|---|
| Ads correlate with **`adSlotRenderer`/`cuepoint`** responses; no separate welded ad Period | Client-guided fill | ✅ "empty the accessor" (lever #1–3) should remove them — proceed to patch. |
| Ads appear as **separate DASH ad `<Period>`s** with SCTE `EventStream` | Manifest-signaled, still client-visible | ✅ Manifest/timeline filtering viable; also confirm accessor lever. |
| Ads are **welded segments inside the content Period**, no client ad payload | Pure SSAI | ⚠️ Residual wall — bytecode can't remove; document as the floor. |
| Mixed | Both | Quantify the ratio → that ratio is the realistic ad-reduction ceiling. |

Record the ratio and the decoded break types in the reanalysis doc's "Next steps
→ on-device capture" item; that closes the one open empirical question.

---

## RESULT — on-device capture completed 2026-08-21 (Onn 4K, `.211`)

**Verdict: row 3 (pure SSAI) — the residual wall. Client-side ad-reduction
ceiling = 0% via any signal-based lever.** Captured a full live news commercial
break through the MITM seam (`MainActivity.h()→0` + mitmproxy CA baked into
`assets/ssl/certs/8bbe0e8d.0`), decoding both youtubei and googlevideo. Addon:
[`experimental/youtubetv-scte35/`] + scratch `ytv_scte35.py`. Every candidate
carriage path was checked and came back **negative**, even mid-break:

| Layer inspected | What it carries | SCTE-35 / ad signal? |
|---|---|---|
| `youtubei/v1/player/heartbeat` `playerCueRangeSet` | `EMBARGO` only, 100% of samples | **None** (0 non-EMBARGO cues all session) |
| UMP part `31 LIVE_METADATA` | media-timeline timestamps + timescale (1e6) | None |
| UMP part `42 NEXT_REQUEST_POLICY` | next-request tokens, itag, mime | None |
| UMP parts `47 / 52 / 53` (unnamed) | static timing cfg / rotating seq token / buffer-duration hints | None |
| **in-media `emsg` box** (scheme `http://youtube.com/streaming/metadata/segment/…`) | `Sequence-Number`, `Ingestion-Walltime-Us`, `Target-Duration-Us`, `Streamable`, `First-Frame-Time-Us`, `Crypto-Period` | **None** — no ad/cue/splice/discontinuity key |
| `video_id` / `itag` across the break | `dq416d40FWQ`, itag 381+412 unchanged on entry **and** exit | No stream switch — single contiguous SSAI splice |

There is **no standard SCTE-35** (`urn:scte:scte35`) anywhere and **no proprietary
ad marker** in YouTube's own `emsg` metadata scheme. The ad is byte-indistinguishable
from content at every client-observable layer (same id/itag/fmt, contiguous
Sequence-Number, `acont=primary`, no ad-decision call). Unlike Paramount/MLB DAI
(which expose pod-serving URLs) or Disney/Pluto (client ad-cue accessor), YTTV
leanback exposes **no boundary oracle** — so segment blank/replace has no trigger
to fire on. Signal-based suppression is ruled out.

**Client-type spoof `TVHTML5_UNPLUGGED → WEB` — TESTED 2026-08-21, FAILED.**
Rewrote `context.client.clientName` on the playback-setup calls (`/next`,
`/player/get_drm_license`) via the live proxy while leaving `browse` intact. The
rewrite fired cleanly, but the leanback SABR pipeline **cannot consume a `WEB`
player response**: `/next` entered a retry storm (10× re-POST) and playback stalled
on a spinner. Crucially, **no `doubleclick`/`googleads` ad-decision call and no
`adPlacements`/`playerAds` ever appeared** — the downgrade does not unlock a
host-blockable client-side ad flow, it just breaks playback. Reverted by relaunching
without `--proxy`.

**Net: every client-side avenue is now closed.** YTTV leanback live = hardest-class
SSAI. Reduction is not achievable by any signal, manifest, accessor, host-block, or
client-spoof lever available to us. The floor is 100% of the served ad load. The
only theoretical residual (heavy, out-of-scope) would be **content-analysis blind
skip** (black-frame/silence detection) — but live can't seek past the live edge,
so even that is inert. Recommend documenting YTTV leanback as **not addressable**
and closing the effort.

---

## Credits & sources
- **SCTE-35 signaling foundation:** *"The Essential Guide to SCTE-35"* by
  **Andy Francis** (Technical Content Lead) and **Alex Zambelli** (Director of
  Product, Encoding) — **Bitmovin / VidTech**, 2026 update. Their detailed
  command/descriptor breakdown, HLS/DASH manifest examples, and SSAI/SGAI framing
  are the basis for what this capture looks for and how we decode it. Full credit
  to their work.
- SCTE-35 spec: ANSI/SCTE 35 (rev. 2023-11-30).
- Tooling: `threefive` (SCTE-35 decoder), `mitmproxy`, Frida, Wireshark.
- Internal: [`YOUTUBE_TV_SCTE35_REANALYSIS.md`](YOUTUBE_TV_SCTE35_REANALYSIS.md),
  [`SCTE35_AD_SIGNALING_REFERENCE.md`](SCTE35_AD_SIGNALING_REFERENCE.md).
- Prior rootless-Onn capture playbooks (reused here):
  [`../testing/pluto-runbook.md`](../testing/pluto-runbook.md) (user-CA
  `overridePins` MITM),
  [`../experimental/netflix-native-adstrip/frida/README.md`](../experimental/netflix-native-adstrip/frida/README.md)
  (frida-gadget), and
  [`../experimental/netflix-native-adstrip/HANDOFF.md`](../experimental/netflix-native-adstrip/HANDOFF.md)
  (system-app / package-rename-clone wall).
