# ADPROBE — JS-VM heap-tap front-end (Netflix) + portability notes

This directory preserves the **app-analysis improvements** developed while re-cracking Netflix
Android-TV ad delivery on 2026-08-13. It extends the base `adprobe` diagnostic
(`docs/ADPROBE_DIAGNOSTIC.md`, branch `feat/pluto-adprobe-diagnostic`), which had a **native**
front-end (Prime Video memcpy GOT hook) and **Java** front-ends (Pluto/Tubi/…) but only
"recon-stage" notes for JS-VM apps like Netflix.

`adprobe-jsvm-tap.js` is that missing **JS-VM front-end**: a read-only QuickJS/Gibbon heap tap.
It is a diagnostic, never a patch — it reads and logs only.

## What's new here (the reusable improvements)

1. **Keyword table with per-key context window** `{k, w, b}` — retarget to a new app by swapping
   the table, not the logic. `w:0` = cheap count-only presence signal.
2. **Residency classification (code vs data)** — scan CODE forms (`==="ad"`, `.initialSegment`,
   `fn=function`) beside DATA forms (`"type":"ad"`). Resident code context ⇒ builder/consumer is
   patchable in-process; data-only ⇒ compiled-away ⇒ source-scan patch impossible. This is how we
   proved Netflix's old patch-A anchor was dead but the new playgraph builder was alive.
3. **Seam-lock** — narrow from "which mechanism" → the consumer that branches on the ad
   discriminator → the single **upstream source** all consumers share (Netflix: `adverts.adBreaks`).
   One edit at the source closes every downstream path.
4. **Live-capture discipline** — chatty devices roll the logcat ring buffer fast (Widevine spam),
   so `logcat -d` misses hits; capture with a live `timeout N adb logcat` window during the actual
   ad break, and use `w:0` count keys to confirm "0 ad segments" after a patch.

## Netflix result (what it found)

Ads migrated from the legacy `metadata.ads[]` manifest-pod model to **dynamic server-side
insertion**: ad videos stitched as `type:"ad"` playgraph viewables (`s0:ad-0-x → padend →
content`), decisioned via the `clcsInterstitialPlaybackAndPostPlayback` GraphQL op (which turned
out to be **UI only**). All stitchers (`applyDaiPrefetch`, `mergeReplacedAds`, `enrichAds`, the VOD
`StatefulAdBreak` map) read one upstream source — the manifest→adverts normaliser
`T.adverts=…{adBreaks:(…?void 0:ba.map(…normalize…))}`. The shipped fix empties it at the source
(`ba.map` → `[].map`). See repo `NOTICE` and the Netflix ad-strip memory notes.

## Portability to Prime Video

PV's ad decisioning is **native** (libignite / WAMR AoT over downloaded WASM), so the base
ADPROBE already targets it with a native memcpy GOT hook — that stays the right front-end for the
WASM ad path. **However**, PV's player/timeline runs in a **QuickJS** layer (see memory
`primevideo-atv-ignite-wamr-architecture`), and the shipped v1.16.0 strip works via a GOT hook on
`libignite` memcpy. The JS-VM tap here is worth trying against PV's **QuickJS heap** to see whether
the timeline/ad-break scheduling (as opposed to the WASM ad-decision blob) exposes resident
source/data strings — the same residency-classification step decides if a JS-side seam exists
alongside the native one. Status: **not yet run against PV** — candidate follow-up.
