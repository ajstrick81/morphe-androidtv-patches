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

That makes **method A (proxy + CA + unpinning)** the primary path and **method B
(pcap + TLS keylog)** the fallback when pinning is stubborn.

---

## Prerequisites

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

## Method A — mitmproxy + system CA + Frida unpinning (primary)

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
