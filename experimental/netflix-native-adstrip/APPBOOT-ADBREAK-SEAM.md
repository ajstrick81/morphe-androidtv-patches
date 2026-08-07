# Netflix appboot — the ad-break resolver (seam found) — 2026-08-07

Dumped the live appboot JS from the running clone's Hermes heap (frida memory dump,
`nfverify/apbdump/apbad_0xb5740000_5533696.bin` = the ad-context range) and located the ad-break
resolver. This is the in-process seam the whole reopening was after.

## The resolver (verbatim from the heap, minified)

`MediaEventsAdBreaksModel` — the source of truth for scheduled ad breaks:

```js
// ctor
function a(d,b){
  this.getMediaEventsCutoffTimestamp=d;
  this.programsModel=b;
  this.poiMap=new Map;
  this.adBreaks=new Map;            // ad-break entries land here (via onAdBreakStart / media events)
  this.normalizedAdBreaks=[];
  this.normalizedAdBreaksDirty=!1
}

// THE accessor the player asks for scheduled breaks:
a.prototype.getAdBreaks=function(){
  var a=this;
  if(this.normalizedAdBreaksDirty){
    var b=this.adBreaks;
    this.normalizedAdBreaks=Array.from(("function"===typeof b.values?b.values.bind(b):h.arrayValues.bind(null,b))())
                                 .map(function(b){return a.toNormalizedAdBreak(b)});
    this.normalizedAdBreaksDirty=!1
  }
  return this.normalizedAdBreaks    // <-- return [] here = no scheduled ad breaks
};
```

And the public getter that fans out to it (CanonicalMediaEventsModel):

```js
Object.defineProperties(a.prototype,{adBreaks:{get:function(){return this.adBreaksModel.getAdBreaks()},...}});
```

## Netflix's OWN no-ad path (predicted by the thesis, confirmed)

There is a built-in ad-DROP subsystem — the client already knows how to run with breaks removed:

```js
// _droppedAds / calculateDroppedAds / adPoliciesManager.setPoliciesForViewable
...this._droppedAds.get(a.viewableId)...map(function(a){return a.dropAdBreak||a.dropAds})...
var l=c.calculateDroppedAds(f,h,n); ... g=l.map(function(a){return a.dropAdBreak||a.dropAds});
h.forEach(function(a,b){ ...a.state.droppedAdIndices = b.dropAdBreak ? (a.metadata.ads.map((_,i)=>i)) : b.dropAds.slice() });
```

`dropAdBreak` / `dropAds` are per-viewable flags the app honors to skip breaks — the same "no-ad path
it runs constantly" (6/7 titles) that motivated reopening Netflix.

## Seam options (in-process, past MSL + both anti-tampers)

- **Seam B (empty the resolver):** force `MediaEventsAdBreaksModel.prototype.getAdBreaks` → `[]`
  (or the `adBreaks` getter → `[]`). Direct Prime-Video `0===t.length` analogue.
- **Seam C (use the built-in drop):** drive `calculateDroppedAds` / `dropAdBreak` so every break is
  dropped — runs Netflix's own removal path.
- **Seam A (data scrub):** empty `this.adBreaks` (the Map) / the media events that populate it before
  `getAdBreaks` normalizes — pure data, orthogonal to code-integrity.

## Weaponization (next design question)

appboot JS is downloaded + hash/signature-verified at rest, so we can't patch it on disk. We already
operate IN-PROCESS past the signature (that's the whole point). Delivery options to evaluate:
1. Native in-process hook that rewrites the decrypted appboot JS text before Hermes compiles it
   (seam-A/B on the source) — must not trip the milo hash (recall `milo_ignore_hash_errors`).
2. Hook the DATA layer feeding `this.adBreaks` (media events / PRS) natively.
3. Runtime JS override via the injected gadget (proof-of-kill first: overwrite `getAdBreaks` in the
   Hermes heap to return []), then port to a shippable native transform.

Proof-of-kill target for the next session: hook/replace `getAdBreaks` to return `[]` during a real
pre-roll and confirm the ad is gone on-device.

## Reproduce
Clone runs past both anti-tampers (DexGuard CertCheck patch + `<queries>` for RJni_SignatureCheck),
logged in. `nfverify/`: `nf-listen2.apk` (gadget+fix), `run-diag.py`, `dump-appboot.js`
(dumps heap ranges via `File` to app files dir → `adb exec-out run-as … cat` to pull),
`ctx.py`/`ctx2.py` (context extractors). Vocabulary counts that led here: `adBreakHydrator`,
`getAdBreakData`, `adBreaksModel`, `dropAdBreak`, `getAdBreaks`.
