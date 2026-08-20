# SCTE-35 & Live-TV Ad Signaling — Reference

A foundation doc for the streaming-TV ad work (YouTube TV and any other
SSAI-delivered live service). It records **how the ad seam is signaled**, why
that seam exists at all, where the signal survives to the client, and — in
growth-mindset terms — **which "walls" are real and which are assumptions.**

This is a research/architecture reference, not a patch. It exists so we pick the
right strategy *before* touching bytecode, because everything branches on one
empirical fact (see [The one question that decides everything](#the-one-question-that-decides-everything)).

Primary source: *"The Essential Guide to SCTE-35"* (Bitmovin/VidTech, 2026
update), cross-checked against the SCTE-35 spec (ANSI/SCTE 35, last revised
2023-11-30) and SSAI vendor docs (AWS MediaTailor, Unified Streaming,
Broadpeak).

---

## TL;DR

- **SCTE-35 is a *signal*, not a payload.** It carries a tiny binary
  `splice_info_section` alongside the video that says *"a boundary is coming —
  when, what kind, how long."* It never carries the ad itself. That separation
  is the entire reason a network can control regional/targeted delivery.
- **Why Chicago ≠ Florida:** the feed carries **Distributor Placement
  Opportunity** markers — the network explicitly vacates a slot and hands the
  seam to the distributor (YouTube TV) to fill per region. Without this signal a
  distributor would have no addressable slot to swap on.
- **Why emergency/blackout override works:** the same rails. The stream is never
  an opaque blob — it's a sequence of **signaled, bounded, individually
  addressable intervals.** SCTE-35 is the index into them. (The user's intuition
  was exactly right on both counts.)
- **The signal is transformed, not hidden.** Binary cue in MPEG-TS → **readable
  manifest text** in HLS (`#EXT-X-DATERANGE`, `#EXT-X-CUE-OUT/IN`, …) or a DASH
  `<EventStream>` Period. In HLS/DASH the ad boundary is *very often plain text
  in the manifest the client downloads.*
- **SSAI defeats naive ad-blocking** (ad segments = program segments: same
  codec, same CDN, same session — no ad host to null out) **but the seam stays
  labeled**, because the player needs those labels to render countdowns and
  enforce no-skip. The metadata that *enforces* the ad is the metadata that
  *reveals* it.

---

## What SCTE-35 is

**SCTE** = Society of Cable and Telecommunications Engineers. **SCTE-35** (first
published 2001 as *"Digital Program Insertion Cueing Message for Cable"*; the
*"for Cable"* was later dropped as it spread to OTT) is the core signaling
standard for **advertising and program control**.

It carries a compact binary message — the `splice_info_section` — **in-band**
(interleaved with the A/V), whose only job is to mark boundaries: national/local
ad breaks, program intro/outro, chapters, blackouts, and live-overrun
extensions. Originally these were literal cue *tones* that dedicated cable
headend hardware picked up to fire local ad insertion. Modern streaming carries
the same message in an MPEG-TS PID and then **translates it into manifest
metadata** for HLS/DASH.

---

## The message: commands + descriptors

The `splice_info_section` wraps a **splice command**. Five exist; two matter:

| Command | Role |
|---|---|
| `splice_insert` | Legacy but still common. Explicit: *"splice out at PTS X, break lasts N seconds, splice back in."* Frame-accurate timing/duration carried directly. |
| `time_signal` | The modern default. Just marks *"a signaled point at PTS X."* Meaningless alone — its meaning comes from attached **segmentation descriptors**. |

Other commands (`splice_null`, `splice_schedule`, `bandwidth_reservation`) are
legacy/niche.

### Segmentation descriptors — where the meaning lives

A `time_signal` carries one or more `segmentation_descriptor`s, and the
`segmentation_type_id` is the vocabulary. From the Bitmovin guide's table
(matches the SCTE-35 spec):

| type_id (start/end) | Name | Meaning for us |
|---|---|---|
| `0x10` / `0x11` | **PROGRAM** | Program start/end. Binge auto-advance; Live-to-VOD edit points. |
| `0x20` / `0x21` | CHAPTER | Chapter boundaries. |
| `0x22` / `0x23` | BREAK | Generic break. |
| `0x30` / `0x31` | **PROVIDER_ADVERTISEMENT** | The **network's** ad (NBC national spots). |
| `0x32` / `0x33` | **DISTRIBUTOR_ADVERTISEMENT** | The **distributor's** ad. |
| `0x34` / `0x35` | **PROVIDER_PLACEMENT_OPPORTUNITY** | A slot the network says *may* be filled. |
| `0x36` / `0x37` | **DISTRIBUTOR_PLACEMENT_OPPORTUNITY** | **The seam handed to YouTube TV — its 2–3 local minutes/hour. This is the Chicago-vs-Florida mechanism.** |
| `0x40` / `0x41` | UNSCHEDULED_EVENT | e.g. unplanned break (injury timeout). |
| `0x42` / `0x43` | ALTERNATE_CONTENT_OPPORTUNITY | Swap in alternate content. |
| `0x44` / `0x45` | PROVIDER_AD_BLOCK | Block of provider ads. |
| `0x46` / `0x47` | DISTRIBUTOR_AD_BLOCK | Block of distributor ads. |
| `0x50` / `0x51` | NETWORK | Network join/leave — blackouts, EAS-style overrides. |

Each descriptor also carries:

- **`segmentation_upid`** — a unique program/asset ID (the UPID) tying the cue to
  a specific asset/campaign.
- **`segmentation_event_id`** — matches a start marker to its end marker.
- **`segmentation_duration`** — how long the break runs.
- **Delivery-restriction flags** — `web_delivery_allowed_flag`,
  `no_regional_blackout_flag`, `device_restrictions`. Machine-readable rules:
  *"may/may not be delivered on web / in this region / to this device class."*

These four (`upid`, `type`, `event_id`, `duration`) are exactly what an Ad
Decision Server / SSAI system reads to map a cue to a campaign, enforce
blackouts, and report consistently.

---

## The user's two intuitions — both confirmed

**"Chicago ads have no relevance to Florida — they must use SCTE for that."**
Correct. Regional/targeted delivery is *only* possible because the feed carries
**Distributor Placement Opportunity** (`0x36/0x37`) markers. The network
contractually + technically vacates the slot; the distributor's ad system fills
it using the viewer's region as a parameter. No signal → no addressable seam →
distributor is stuck rebroadcasting the national ad byte-for-byte.

**"In an emergency all content would have to be accessible to modify delivery."**
Correct, and it's the cleanest proof the stream is *not* an opaque blob.
Blackouts (`NETWORK`, restriction flags) and EAS overrides ride the same rails: a
signaled, bounded interval where the normal feed is replaced by alternate
content, then resumed. A regional sports blackout and an emergency override are
the *same mechanism*. The design requirement you described — everything
addressable at the seam — is precisely what SCTE-35 satisfies.

---

## How the signal reaches the client (and where we can see it)

The marker is **transformed** as it moves downstream. Each transform is an
observation point:

```
[Contribution/backhaul]   MPEG-TS: SCTE-35 binary in its own PID (SRT/Zixi/satellite/fiber)
        │  packager parses the PID
        ▼
[HLS manifest]            #EXT-X-DATERANGE (ID + start + hex raw bytes)   ← readable text
                          #EXT-X-CUE-OUT:<dur> / #EXT-X-CUE-IN
                          #EXT-OATCLS-SCTE35 / #EXT-X-SCTE35 / #EXT-X-SPLICEPOINT-SCTE35
                                                              (base64 raw bytes)
[DASH manifest]           new <Period> with <EventStream schemeIdUri="urn:scte:scte35:2014:xml+bin">
                          → <Event duration=…><Signal><Binary>…base64…</Binary></Signal>
```

### HLS carriers (any subset may be present)
- **`#EXT-X-DATERANGE`** — HLS-spec standard; ID, start timestamp, hex-encoded raw
  SCTE-35 bytes. What AWS MediaTailor writes.
- **`#EXT-X-CUE-OUT:<duration>` / `#EXT-X-CUE-IN`** — human-readable break
  in/out with duration.
- **`#EXT-OATCLS-SCTE35` / `#EXT-X-SCTE35` / `#EXT-X-SPLICEPOINT-SCTE35`** —
  base64 of the original binary trigger.

Real manifest excerpt (from the guide):
```
#EXTINF:4.0,
2021-07/video/hls/360/seg_18188.ts
#EXT-X-CUE-OUT:120.000
…ad segments…
#EXTINF:4.0,
2021-07/video/hls/360/seg_18218.ts
#EXT-X-CUE-IN
```
The segments between `CUE-OUT` and `CUE-IN` are the ad pod. **That range is
self-labeling.**

### DASH carrier
SCTE breaks become **new `<Period>`s** in the `.mpd`, each with an
`<EventStream>` carrying the base64 binary. An ad break is literally a distinct
Period you can identify.

---

## SSAI vs SGAI — and why blocking fails but filtering might not

**SSAI (Server-Side Ad Insertion)** — the dominant model, and the wall:
```
Encoder inserts SCTE-35 → origin/packager → SSAI server sees the cue
   → SSAI calls Ad Decision Server (VAST/VMAP, with viewer region/device/session)
   → ADS returns a per-viewer ad pod
   → SSAI transcodes ads to match the stream, STITCHES them into THIS viewer's manifest
   → client fetches one seamless, personalized manifest
```
Consequences:
- **Session-based & per-viewer** — each session gets a unique manifest; region is
  a parameter in the ad call, not a property of the video. *This is why Chicago ≠
  Florida.* Program segments are shared; only ad-pod segments differ.
- **Ad segments are indistinguishable by transport** — same codec, same CDN, same
  session as content. **There is no separate ad host to block.** Classic
  client-side ad-blocking has no target.

**SGAI (Server-Guided Ad Insertion)** — newer, explicitly named in the 2026
guide. The server *guides* the client to the ad opportunity (via interstitial
signaling, e.g. HLS Interstitials / DASH) rather than pre-welding every byte.
This is more client-cooperative → potentially **more** for a client patch to act
on. Worth checking whether YouTube TV uses any SGAI-style interstitial signaling.

### The growth-mindset reframe
SSAI looks impenetrable *if the goal is "block the ad request."* It isn't the
goal. **The seam is still labeled** — SSAI systems (MediaTailor et al.) write the
boundaries *back into the delivered manifest* so the player can show countdowns
and enforce no-skip. So the strategy shifts from *block* to:

1. **Manifest-level filtering** — intercept the stitched HLS/DASH manifest, read
   the `EXT-X-DATERANGE` / `CUE-OUT..CUE-IN` / DASH ad-Period boundaries, and drop
   the ad segment range. Most promising; the boundary is self-labeling. Fragile
   only if the manifest is signed / integrity-checked.
2. **Enforcement neutering** — the SCTE cue is what flips the player into "can't
   seek / can't fast-forward." Patch the *handler*, not the bytes, to restore
   seek across the marked interval.
3. **Detect-and-seek** — parse the cue, know the break is `[T, T+duration]`,
   auto-seek past it. Only works where the stream is seekable (DVR), not at pure
   live edge.

**Bonus signal from the guide, directly on-point for us:** ad-break markers are
already used as **edit points to auto-remove ads for ad-free subscribers in
Live-to-VOD** workflows. The industry itself uses SCTE-35 boundaries to *cut*
ads — that's precisely technique #1, validated as a normal operation.

---

## The one question that decides everything

> **When YouTube TV's SSAI stitches the stream, does it leave the SCTE-35
> boundaries in the delivered manifest (as MediaTailor does), or strip them after
> the cloud consumes them?**

- **Survive** → techniques #1/#2 are live. We read the seam and filter or neuter.
- **Stripped** → we're pushed to heuristic ad-vs-content detection (much harder).

**How to answer it (empirical, one capture):** grab one live YouTube TV manifest
and grep for `EXT-X-DATERANGE`, `CUE-OUT`, `SCTE35`, `OATCLS`, or DASH
`emsg` / `EventStream` / extra `<Period>`s. Also check whether the manifest/segments
are **signed or integrity-checked** (decides #1's fragility) and what streaming
format is in use (HLS vs DASH).

Everything downstream — which smali/native handler we target, whether we filter
or neuter — branches on this single observation.

---

## Next steps

1. **Capture a live YouTube TV manifest** (HAR / proxy) and run the grep above →
   answer the decision question.
2. **Identify the format & integrity posture** (HLS vs DASH; signed manifest?).
3. **Locate the cue-parsing / no-skip enforcement handler** in the app
   (ExoPlayer stock HLS/DASH stack vs a custom media source — cf. the Twitch
   blueprint, where a *native* IVS source was the wall).
4. Pick technique #1 vs #2 based on 1–3, then prototype.

---

## Sources

- *The Essential Guide to SCTE-35* — Bitmovin/VidTech, 2026 update (primary; PDF
  in project uploads).
- ANSI/SCTE 35 specification (rev. 2023-11-30).
- AWS Elemental MediaTailor, Unified Streaming, Broadpeak SSAI docs (SCTE-35 →
  manifest mapping, per-viewer stitching).
- Internal: [`docs/TWITCH_AD_SUPPRESSION_BLUEPRINT.md`](TWITCH_AD_SUPPRESSION_BLUEPRINT.md)
  — prior SSAI wall (native media source) and the manifest-vs-native distinction.
