# Handoff — apply the JS-VM ad-seam tap to Prime Video

**Goal:** point the read-only JS-VM heap tap (`adprobe-jsvm-tap.js`) at Prime Video ATV's
**QuickJS** layer to find whether ad-break scheduling/consumption exposes a **JS-side seam** we can
patch in-process — the way it just cracked Netflix's dynamic ad insertion.

This is a **diagnostic** task first (classify, don't patch). Read this whole doc + the referenced
memories before touching the device.

---

## 1. Why this might work on PV (and its one hard limit)

PV's ad pipeline is split across two runtimes (see memory `primevideo-atv-ignite-wamr-architecture`):

| Layer | Runtime | ADPROBE front-end |
|---|---|---|
| Ad **decision** blob (getVideoAds / PRS / adBreaks) | **native WASM** (libignite / WAMR AoT) | native memcpy-GOT hook (existing) |
| Player / **timeline** / ad-break scheduling & consumption | **QuickJS JS** | **this JS-VM tap** |

**Hard limit:** the JS tap reads process `rw-` memory for JS **source/data strings**. It **cannot**
read WASM linear memory, so it will *not* see the native ad-decision blob — that path stays covered
by the native memcpy-GOT front-end and the shipped v1.16.0 strip (memory
`primevideo-atv-got-hook-working`). What the JS tap *can* find is whether the **QuickJS timeline**
schedules/consumes ad breaks with resident source (a JS-side seam), analogous to Netflix's
`adverts.adBreaks` normaliser.

**The decisive question this task answers:** does PV have a resident QuickJS ad-break
builder/consumer (patchable like Netflix's), or is the ad break fully assembled in WASM and only
handed to QuickJS as opaque data? The residency-classification step (below) decides it.

---

## 2. Prereqs / environment

- **Device:** Onn 4K over adb. This session's box was `192.168.12.211:5555`. PV `.mod` clone was
  historically on `.211`/`.211` — **verify the IP** and that the PV clone is installed + logged in
  (memories `pv-netflix-integration-ideas`, `primevideo-atv-resume-reset-repro`).
- **Injection:** PV needs an in-process gadget to run the tap. Use the existing no-root gadget
  recipe (memory `primevideo-atv-frida-gadget-recipe`) — note libignite statically links TLS, so
  hook libc `getaddrinfo`/`connect` if you need network context; for THIS task you only need the
  gadget to load the script and run `adProbe(Process.enumerateRanges('rw-'))` on a timer.
- **The tap:** `experimental/netflix-adprobe/adprobe-jsvm-tap.js`. `makeAdProbe(logFn)` returns
  `{adProbe, KEYS, pat}`. Wire `logFn` to your gadget's logcat writer (Netflix used an
  `__android_log_write` NativeFunction under tag `KILL`).

---

## 3. Procedure (mirror the Netflix method)

**Step A — confirm the QuickJS heap is even scannable.** Add a sanity key for a known PV JS token
(e.g. a player/timeline string) and confirm non-zero hits with resident *source* context. If the
JS heap yields nothing, PV keeps its JS compiled/at-rest and this avenue is closed (stop here).

**Step B — swap the keyword table for PV.** The default `KEYS` in the tap are Netflix's. Replace
with PV ad-scheduling candidates, each `{k, w, b}` (w:0 = count-only). Starting set to try:

```
{k:'getVideoAds', w:220, b:40},        // PV ad-decision entry (memory primevideo-atv-tvshow-getvideoads-mechanism)
{k:'resolveWithAdBreaks', w:260, b:60},// count-gate seam (0===t.length) noted in memory
{k:'adBreaks', w:200, b:60},           // generic ad-break list
{k:'"adBreak', w:200, b:40},
{k:'intraTitlePlaylist', w:220, b:60}, // AmazOff cuepointPlaylist / Remote items (memory amazoff-cuepointplaylist-technique)
{k:'"type":"Remote"', w:120, b:20},    // ad item marker in PV playback resources
{k:'PeriodTailor', w:200, b:60},       // client-side period stitching (memory primevideo-atv-tvshow-ssai-confirmed)
{k:'adInsertionType', w:220, b:80},
{k:':ad-', w:0, b:0},                  // stitched ad-segment presence (count)
{k:'ServerInsertedAdBreak', w:220, b:60} // media3-style SGAI (memory hoodles-skipads-technique)
```

**Step C — residency classification (code vs data).** For each hit, judge the dumped context:
minified **function/code** context (`fn=function`, `===`, property access) ⇒ the builder/consumer
is JS-source-resident ⇒ **patchable in-process**. JSON-**data**-only ⇒ compiled/assembled elsewhere
(likely WASM) ⇒ no JS seam, defer to the native front-end.

**Step D — seam-lock.** If a resident consumer exists, narrow the table to (1) the discriminator
that marks a break/item as an ad (PV's analog of `type:"ad"` / `type:"Remote"`), then (2) the single
**upstream source** all consumers read. On Netflix that collapse (many stitchers → one
`adverts.adBreaks` normaliser → one 2-byte edit) was the whole win; look for the same choke point.

**Step E — live-capture discipline.** Trigger a real PV ad break and capture with a **live**
`timeout N adb logcat -s <TAG>` window (not `logcat -d` — the ring buffer rolls). Use `w:0` count
keys to later confirm "0 ad segments" if/when a patch is applied.

---

## 4. Deliverable of THIS task

A classification verdict + (if a seam exists) the exact resident anchor:
- **JS seam exists** → report the upstream source string + a candidate length-preserving edit
  (Netflix-style), then hand to a patch-design task. Do NOT patch blind — verify on-device against
  a real break, and watch for playback desync (Netflix's DAI-only build caused a "frozen ad frame
  while timeline advances" glitch until the kill moved to the source).
- **No JS seam** → conclude PV ad decisioning is WASM-only; the native memcpy-GOT front-end +
  shipped v1.16.0 strip remain the right tools. Still a useful negative result — record it.

---

## 5. Guardrails

- **Read-only.** This tap never writes memory. Keep it that way for the diagnostic phase.
- PV ads are **already shipped-stripped (v1.16.0)** — this is not fixing a live regression, it's
  building a JS-side option in case PV drifts the way Netflix just did. Low urgency; do it right.
- Don't run stress/auto-nav blind; follow the autotest HARD RULES (memory
  `pv-netflix-integration-ideas`: foreground-guard, verify-before-stress).

---

## 6. Key references

- Tool + methodology: `experimental/netflix-adprobe/adprobe-jsvm-tap.js`, `README.md`
- Base diagnostic: `docs/ADPROBE_DIAGNOSTIC.md` (branch `feat/pluto-adprobe-diagnostic`)
- Netflix worked example (what "good" looks like): memory `netflix-clone-dexguard-certcheck-wall`
- PV architecture / seams: memories `primevideo-atv-ignite-wamr-architecture`,
  `primevideo-atv-tvshow-getvideoads-mechanism`, `primevideo-atv-got-hook-working`,
  `amazoff-cuepointplaylist-technique`, `primevideo-atv-frida-gadget-recipe`
