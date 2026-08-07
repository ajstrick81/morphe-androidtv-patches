# Netflix ad suppression — the `ads:[]` empty-pod seam (data-grounded) — 2026-08-07

This supersedes the guess-and-check phase. We stopped trusting "did an ad play on screen"
(a broken oracle) and captured the **actual decrypted manifest ad-break data** in-process
during a confirmed live mid-roll. That gave us both a reliable oracle and the correct
suppression transform.

## The broken oracle (why results were "all over the place")

Netflix's server **does not serve an ad on every ad break.** Unpatched baseline testing showed
~2 of 3 titles play the pre-roll *marker* but **no ad** (empty fill). So every earlier
"3 of 4 clean!" result was partly/entirely the server's own random ad-fill, not our patch.
Judging a patch by whether an ad visibly played, at n=3–4, is meaningless. This matches the
long-standing thesis in memory ("the app's own no-ad path runs 6/7 titles").

## Ground truth captured (read-only heap dump during a live mid-roll)

Method: `capture-real.js` (READ-ONLY; reads heap, writes dumps to app files dir, **no patching**).
Clean data markers that are 0 at browse and non-zero only during a real ad:
`adBreakToken, adEventToken, viewableAdBreakIndex, occurredAdBreaks, adBreakHydrated`.
Forced a reliable real ad by **fast-forwarding to a mid-roll** (server empty-fills pre-rolls
too often to be a dependable trigger). Pulled the fresh dump while the ad was on screen.

The decrypted manifest response JSON is plaintext in the Hermes heap (past MSL). Two shapes:

**Real ad served (populated):**
```json
"adverts":{"adBreaks":[{"ads":[{"timedAdEvents":[
  {"event":"adProgress","timeMs":7500,"adEventToken":"BgiJvevcAxLmAp++..."},
  {"event":"adProgress","timeMs":15000,"adEventToken":"BgiJvevcAxLmAmPy..."}],...},{...second ad...}],
  "actionAdBreakEvents":{...},...}]}
```

**Server's own no-ad path (empty):**
```json
"adverts":{"adBreaks":[{"ads":[],"actionAdBreakEvents":{
  "start":{"event":"adBreakStart","adEventToken":"BgiJvevcAxKXAiwx..."},
  "stop":{"event":"adBreakStop","adEventToken":"BgiJvevcAxKXAlnH..."}},...}]}
```

**Oracle:** `"ads":[]` = no ad; `"ads":[{…}]` = real ad. Binary, readable in-process,
independent of what the screen shows.

Sample dumps saved: `nfverify/realad/SAMPLE_fullbreak.bin`, `SAMPLE_emptybreak.bin`.

Also captured: the **hydration request** body (`fetchType:1`, movie `80210920`) carrying the real
`adBreakToken` — confirms mid-rolls go through dynamic hydration (request → response with `ads[]`).

## The correct suppression transform

Rewrite `"ads":[{…}]` → `"ads":[]` inside each ad break of the **decrypted manifest response**,
leaving `actionAdBreakEvents` (start/stop beacon shells) intact. This is **byte-identical to
what Netflix's server returns 2/3 of the time**, so the client's own no-ad path handles it
(`emptyAdBreakComplete` → content plays). No structural mutation, no lie to the server →
**no `tvq-pb-101`.** Direct analogue of AdGuard emptying PV's ad pod / the AmazOff rig.

### Why the earlier approaches failed (now explained)
- `supportsAdBreakHydration=false` (lie to server) → `tvq-pb-101` (server refuses).
- `NullAdPolicy dropAdBreak=true` / `hasAds=false` (remove breaks) → `tvq-pb-101` (playgraph
  expects the breaks it was told about).
- `enableAdPlaygraphs=false` → looked ~3/4 clean but that was mostly server empty-fill noise;
  seek mid-rolls still played.
- **Persistent 4s re-patch loop was corrupting the app** (writing JS source mid-load) → the
  intermittent `tvq-pb-101`. Rule: apply any patch **once, pre-playback**; never hammer live memory.

## The one hard problem left (well-defined)

The `ads[]→[]` transform must hit the JSON **before `JSON.parse`** — post-parse heap edits do
nothing, and MSL encryption blocks a network-layer MITM (unlike PV). Config says the manifest is
parsed with a **reviver** (`transformationMethod:"reviver"`). NEXT: locate the manifest reviver /
MSL-decrypt→parse boundary in the heap (the module that receives the decrypted manifest string and
calls `JSON.parse(text, reviver)`), and apply the transform there — once per playback, reliably.

## Tooling (read-only, in `nfverify/`)
- `capture-real.js` — READ-ONLY periodic heap dumper (overwrites `real_*.bin` each cycle; pull while
  ad is on screen). Markers above.
- `run-diag.py <script> <secs>` — frida-17 attach/resume driver (native-only; no Java/JS bridge).
- Sample ground-truth: `nfverify/realad/SAMPLE_fullbreak.bin` (ads populated),
  `SAMPLE_emptybreak.bin` (ads:[]).

## Discipline going forward
Fixed test protocol; measure against the `ads:[]/ads:[{}]` oracle, not the screen. One pre-playback
patch at a time. Never re-patch live memory in a loop.
