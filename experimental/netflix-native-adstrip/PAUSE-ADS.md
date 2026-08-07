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

## NEXT (this session): find the consumption chokepoint + one clean patch
Study the dump for where the pause-page result is turned into "show ad" (the `te`/`A` assignment, the
`onPauseAdLoaded` trigger, or the data-rewrite query result), apply ONE pre-pause source patch that
forces the no-ad branch (e.g. treat as `PinotPlaymodePauseNoAdPage` / no `adOpportunity`), verify the
overlay is gone. Live dump captured: `nfverify/pausead-live.bin` (+ apbdump ranges).
