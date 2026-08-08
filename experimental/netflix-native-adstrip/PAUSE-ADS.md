# Netflix PAUSE ads — separate subsystem, own seam — 2026-08-07

Pause ads (the overlay shown when you pause on the ad tier) are **NOT** manifest ad breaks, so the
PROVEN pre-roll/mid-roll kill (`prepareAdBreakStates → metadata.ads=[]`, see ADS-EMPTY-POD-SEAM.md)
does **not** cover them. Pause ads are a client-rendered overlay driven by a GraphQL page.

## Discipline (carried over — learned the hard way, re-confirmed here)
- **One patch, pre-playback/pre-pause. NEVER re-patch live memory in a loop.** The 4s setInterval
  re-patch loop is a known app-corruptor (ADS-EMPTY-POD-SEAM.md §"Why earlier approaches failed").
  My first two pause attempts (`kill-getadbreaks.js`, `kill-pausead.js`) used that loop — wrong.
- **In-heap SOURCE patching DOES work** when applied once, pre-use, on the right chokepoint (proven by
  `prepareAdBreakStates`). Earlier "source patch is inert" conclusion was WRONG — the misses were
  wrong-seam + the re-patch loop, not the technique.
- Target the **data chokepoint** (where the ad data is consumed), not reactive/display accessors.
  `getAdBreaks` (media-events model, reactive) and the `L` render gate (display selector) both failed.
- Oracle: for pause ads the on-screen overlay is a fair oracle (no server empty-fill ambiguity like
  pre-rolls) — but prefer a data check where possible.

## Subsystem map (from the appboot heap dump, nfverify/apbdump + pausead-live.bin)
- GraphQL: `usePauseAdDEPPDataQuery` / `usePauseAdDataRewriteQuery` → `pinotPlaymodePausePageV2` /
  `pinotPausedPlaybackPage`, InlineFragments on **`PinotPlaymodePauseAdPage`** (ad) vs
  **`PinotPlaymodePauseNoAdPage`** (server's own no-ad path).
- Render gate (display, NOT the chokepoint): `function L(e){return "PinotPlaymodePauseAdPage"===
  e?.pinotPlaymodePausePageV2?.__typename ? that : void 0}` — patched to `return void 0` once,
  overlay STILL showed → there is another path / L already-compiled / not the real gate.
- State machine (apbad_0xc9d80000): `function pe(){return !(!Z && ("PinotPlaymodePauseNoAdPage"!==A ||
  "adOpportunity"!==te) && "adError"!==te)}` where `A`=__typename, `te`=state
  ("adOpportunity"/"adError"/…). Branches the ad vs no-ad flow — candidate chokepoint.
- Component: `t.PauseAd=function(e){…}`, `onPauseAdLoaded`, capability `pauseAdsEnabled`
  (requirements: `isAdsUser` + fastProperty `enablePauseAds` + feature gate).
- Capabilities also expose `pinotPauseAdBoxshot` entity (EPISODE/MOVIE) — the boxshot render.

## ✅ PAUSE-AD KILL — ACHIEVED 2026-08-07 (z() displayAd chokepoint)

The render gate `L` was the wrong layer (patching it did nothing — pause ads run through a
**redux-saga**, not the React selector). Real chokepoint = the ad-opportunity fetcher generator
`z()` (`R=mark(z)`, dump @146768 in apbad_0xc9d80000):
```js
c=I.sent;                                   // GraphQL pinotPausedPlaybackPage response
f=null==(e=getDataFragment(c))?void 0:e.displayAd;   // f = the ad payload
if(f){ /* build {opportunityToken,url,adEvents...} */ } else return;   // no displayAd -> return undefined
// consumer j(): z() -> if(!(e=l.sent)){bail}  -> reads e.opportunityToken/e.url -> presents overlay
```
**Patch (single-shot, pre-pause, length-preserving, NO loop):** turn the ternary value
`e.displayAd` into `void 0`:
```
void 0:e.displayAd   ->   void 0:void 0        (11 bytes each; unique anchor, count=1)
```
→ `f` is always undefined → `z()` returns undefined → saga `j()` bails → **no pause-ad overlay.**
Tool: `nfverify/kill-pausead2.js` (polls only until it applies ONCE, then stops — discipline honored).

**On-device (2026-08-07, logged-in clone):** pause ad overlay showed pre-patch, and was GONE after the
patch on the same title/session. Applied once, no re-patch loop, no crash, playback stable.

### Verified same session (user-reported on-device)
- Pause-ad overlay: **GONE** (confirmed — mechanism + before/after). ✅
- Resume: starts **exactly** where left off → patch does not corrupt playback state. ✅
- Pre-rolls / mid-roll (FF) ads: **none observed** across several titles/seeks — ENCOURAGING but
  NOT proof this run: the manifest pre/mid-roll kill (`prepareAdBreakStates`, ADS-EMPTY-POD-SEAM.md)
  was NOT loaded this session; only the pause patch was. So "no pre/mid-roll" here is most likely the
  server's ~2/3 empty-fill (broken oracle). Pre/mid-roll IS separately PROVEN (KILLMARK) but via its
  own patch run. Confirm together with the data oracle.

## ✅✅ COMBINED PROOF — BOTH kills, one session, data-oracle-verified (2026-08-07)

Ran `nfverify/combined-kill.js` (both patches single-shot + read-only oracle) on the logged-in clone;
brute-forced many titles / mid-roll seeks / pauses. Log: `PROOF-combined-both-kills-2026-08-07.log`.
Oracle proof (baseline KILLMARK=1 = patch source; ≥2 = a REAL server ad break stamped+emptied):
```
OBS24-35: KILLMARK=2                 <- mid-roll real-ad break emptied (uncompressed hydration object)
OBS34-35: rawRealPods=1              <- raw compressed-path real pod (pre-roll) present
OBS25/29/30/31/34/36: rawDisplayAd=1..2  <- server delivered pause ads
```
User on-screen, same session: **"No pre rolls, no mid rolls firing, and no ads on pause."**
→ server delivered all three ad types (pre-roll raw pod, mid-roll object, pause displayAd) and NONE
played. Both kills demonstrated against DATA, not just screen. `rawRealPods=0` most cycles is expected
(mid-rolls arrive as parsed objects, no raw text → proven via KILLMARK=2).

## NEXT: make it SHIPPABLE (both proven; delivery is the remaining work)
- Both kills are frida proof-rigs (runtime heap patches, re-applied each launch). Shippable = one
  in-process native transform applied at appboot load (past the hash check; mind
  `milo_ignore_hash_errors`), or baked into the clone bootstrap. Drop the `__adkill` proof stamp for
  production (edit becomes just `f.metadata&&(f.metadata.ads=[]);`).
- Re-confirm both OLD byte patterns per Netflix version bump; verify longevity across app restart /
  appboot re-download.

## (done) brute-force testing (with the DATA oracle) + combine both kills
- Apply BOTH patches in one session (prepareAdBreakStates + pause z()) and brute-force many titles /
  many mid-roll seeks / many pauses.
- Measure against DATA, not the screen: for pre/mid-roll use `__adkill`/`rawRealPods`; for pause add
  a read-only stamp (log when `z()` saw a real `displayAd` we voided) so "server sent an ad → we
  suppressed it" is demonstrated.
- Watch for: any `tvq-pb-*` playback errors, resume-position regressions, longevity across restarts.
- Then: shippable delivery (in-process native transform at appboot load, past the hash check — mind
  `milo_ignore_hash_errors`), dropping the proof stamps.

## (superseded) earlier NEXT: find the consumption chokepoint + one clean patch
Study the dump for where the pause-page result is turned into "show ad" (the `te`/`A` assignment, the
`onPauseAdLoaded` trigger, or the data-rewrite query result), apply ONE pre-pause source patch that
forces the no-ad branch (e.g. treat as `PinotPlaymodePauseNoAdPage` / no `adOpportunity`), verify the
overlay is gone. Live dump captured: `nfverify/pausead-live.bin` (+ apbdump ranges).
