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

## The reviver / manifest-processing seam — LOCATED 2026-08-07 (offsets in hydrator-dump/big1.bin)

Decoded the manifest transform chain from the dump. Two precise seams, both found:

**Walker — `reviveObject` (`ha`, @77423) + recursive `ka`:**
```js
ha=function(a,b){ null!==a && "object"===typeof a && ka(a,b,void 0,"") }
ka=function(a,b,d,f){                 // a=value, b=reviver, d=parent, f=key
  if(Array.isArray(a)) a.forEach(function(v,i){ v=ka(v,b,a,i); void 0!==v?a[i]=v:delete a[i] });
  else if(object)      for(var k in a){ n=ka(a[k],b,a,k); void 0!==n?a[k]=n:delete a[k] }
  return b.call(d, f, a)              // calls reviver(key,value) per node, this=parent
}
```
Recurses the ENTIRE manifest object → **does visit `adverts→adBreaks[]→ads`**.

**Reviver — `manifestV2V3Reviver` (`la`, @78725):**
```js
la=function(a,b){ return ja.reduce(function(b,d){return d.call(this,a,b)}.bind(this), b) }
```
`ja` = list of `createDuplicatingReviver` (`ba`, @77785) field-renamers (audioTracks↔audio_tracks,
cdnList↔cdnlist, …). NO ad logic — passes `ads` through untouched. Exported in module `a(0)` at
@70215 (`reviveObject`), @70571 (`createDuplicatingReviver`), @70627 (`manifestV2V3Reviver`).

**Chokepoint — processor `b(a,b)` (@~2578970):**
```js
a && "v3"===a.manifestVersion && !a.processed && (
  b.reviveObjectStart=nrdp.mono(),
  "reviver"===u.transformationMethod ? reviveObject(a, manifestV2V3Reviver) : ... )
```
`a` = already-PARSED manifest object; runs ONCE (`!a.processed`), before ad consumption; bracketed by
`mslParseStart/End` → `reviveObjectStart/End`.

### Two delivery seams (both located)
1. **JS reviver seam (post-parse object):** the reviver gets `(key,value)` per node. Force it to return
   `[]` when `key==="ads"` → empties every ad pod (server-no-ad shape), once per manifest, pre-consumption.
   Injection point = `la=function(a,b){…}` @78725. (Byte-patch length constraints TBD — the body is
   `la=function(a,b){return ja.reduce(function(b,d){return d.call(this,a,b)}.bind(this),b)}`; an
   in-place `"ads"===a` guard doesn't fit without displacing bytes — needs a code-cave or the reduce
   seed swap. Design pending.)
2. **Native MSL seam (pre-parse text):** `mslParseStart` precedes reviveObject → the raw decrypted
   manifest JSON text exists at the native MSL-decrypt boundary in libnetflix (frida-17 native-friendly).
   Apply the `ads:[{}]→ads:[]` TEXT transform there. True transport-layer seam, below the JS line,
   analogue of the PV MITM rig. Locating the exact libnetflix decrypt→JSON.parse call is the next native step.

NEXT: pick a seam (JS reviver vs native MSL) and build a SINGLE pre-playback proof-of-kill, verified
against the `ads:[]/ads:[{}]` oracle. No re-patch loops.

## PROOF-OF-KILL — ACHIEVED 2026-08-07 (prepareAdBreakStates seam) ✅

Picked the **JS object seam** (covers both compressed pre-roll path AND uncompressed hydration
mid-roll path — the native MSL/gunzip seam only covers the compressed path, see below).

**Why native MSL was NOT used:** on-device hooks proved (a) libnetflix statically links OpenSSL
(its own `EVP_DecryptUpdate @libnetflix+…`, 12k calls, 0 contained `adBreaks` → manifest is
compressed at that layer); (b) `Manifest(a,b)` = `a.compressed ? JSON.parse(_uncompress(a.manifest,
"gzip",false)) : a.manifest` — so **mid-roll hydration responses take the UNCOMPRESSED path** (already
an object, no gunzip) → a native gunzip-output transform would miss mid-rolls. The JS object layer is
the only convergence point for both.

**The chokepoint:** `AdsState.prepareAdBreakStates(a)` (@4259343 in dump) — iterates every ad break,
called from BOTH `adsState.rebuild()` and `createInnerWorkingPlaygraph()` right before
`createAdsPlaygraph` builds ad segments. Runs on all paths, pre-segment-build.

**The patch (single, pre-playback, length-preserving 128B, NO re-patch loop):** replace the loop body
```
OLD: var f=e.value;f.syncAdStates();f.state.isHydrated&&"viewable"!==f.metadata.source&&f.applyHydration(f.state.hydrationSequenceId)
NEW: var f=e.value;f.metadata&&(f.metadata.ads.length&&(f.__adkill=f.metadata.ads.length),f.metadata.ads=[]);f.syncAdStates();/*...*/
```
→ empties `metadata.ads=[]` for every break so all consumers (segment build, lease, dropped-ads,
duration) see no ads → `emptyAdBreakComplete` → content plays. The `f.__adkill=<count>` stamp is a
self-proof: that property name exists ONLY if a break with `ads.length>0` (a real server ad) was
emptied.

**PROOF (read-only marker monitor, `nfverify/kill-observe.js`; log = `PROOF-prepareAdBreakStates-2026-08-07.log`):**
```
OBS7: KILLMARK=2 rawRealPods=3 ...  <<< patch emptied 2 break(s) that HAD real ads
OBS9: KILLMARK=2 rawRealPods=3 ...
```
- `rawRealPods=3` = server delivered 3 real ad pods (raw-JSON `ads":[{`, data-only signature).
- `KILLMARK=2` (baseline 1 = our own patch source; ≥2 = Hermes interned `__adkill` because we stamped a
  real-ad break) = patch emptied genuinely-populated breaks.
- On-screen: no ads played across many titles + many mid-roll seeks (pre-rolls AND mid-rolls gone).
Server sent real ads → patch emptied them → nothing played. Demonstrated, not inferred.

**Process lesson that made the proof possible:** "did an ad play" is a broken oracle (server empty-fills
~2/3). The `__adkill` self-stamp + `rawRealPods` raw-JSON counter disambiguate our kill from server
empty-fill — essential given the earlier chaos.

### Remaining before shippable
- Delivery is currently a runtime frida-heap source-patch (proof rig). Shippable form = in-process
  native transform applied at appboot load (appboot JS is downloaded + hash-verified → patch in-process
  past the signature; mind `milo_ignore_hash_errors`), OR bake into the clone's gadget bootstrap.
- Ship WITHOUT the `__adkill` stamp (that was proof-only); the production edit is just
  `f.metadata&&(f.metadata.ads=[]);` + padding.
- Verify longevity across app restart / appboot re-download; re-confirm the OLD byte pattern per version.

## Tooling (read-only, in `nfverify/`)
- `capture-real.js` — READ-ONLY periodic heap dumper (overwrites `real_*.bin` each cycle; pull while
  ad is on screen). Markers above.
- `run-diag.py <script> <secs>` — frida-17 attach/resume driver (native-only; no Java/JS bridge).
- Sample ground-truth: `nfverify/realad/SAMPLE_fullbreak.bin` (ads populated),
  `SAMPLE_emptybreak.bin` (ads:[]).

## Discipline going forward
Fixed test protocol; measure against the `ads:[]/ads:[{}]` oracle, not the screen. One pre-playback
patch at a time. Never re-patch live memory in a loop.
