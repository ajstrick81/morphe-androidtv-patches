'use strict';
// ADPROBE — JS-VM heap-tap front-end (read-only ad-seam diagnostic)
// =================================================================
// Developed 2026-08-13 while re-cracking Netflix ATV ad delivery after Netflix migrated from the
// old metadata.ads[] manifest-pod model to DYNAMIC server-side ad insertion. The base ADPROBE
// (docs/ADPROBE_DIAGNOSTIC.md) had a NATIVE front-end (Prime Video memcpy GOT hook) and Java
// front-ends (Pluto/Tubi/etc.) but only "recon-stage" notes for Netflix. This is the actual
// Netflix front-end: a QuickJS/Gibbon HEAP TAP. It is app-agnostic for any app whose ad logic
// runs in a JS VM whose source/data strings are resident in readable (rw-) memory.
//
// It is a DIAGNOSTIC, never a patch: it only reads and logs. Load it in the same in-process
// gadget you use for patching; call adProbe(Process.enumerateRanges('rw-')) on a timer.
//
// WHAT MAKES THIS REUSABLE (the "app analysis" improvements over the base scanner):
//  1. KEYWORD TABLE with per-key context window: {k, w, b} — k=needle, w=bytes of context to
//     dump, b=bytes-before to include. w:0 => count-only (cheap presence signal, no dump).
//     Retarget to a new app by swapping the table; the scan/dump logic is unchanged.
//  2. RESIDENCY CLASSIFICATION (code vs data): scan for CODE forms (`==="ad"`, `.initialSegment`,
//     `fn=function`) alongside DATA forms (`"type":"ad"`). If the CODE forms come back with real
//     minified-function context, the builder/consumer is source-resident => patchable in-process.
//     If only JSON data appears, the logic is compiled-away => a source-scan patch is impossible
//     (this is exactly how we proved Netflix's old patch-A anchor was dead and the new playgraph
//     builder was alive).
//  3. SEAM-LOCK: once a mechanism is found, narrow the table to the CONSUMER that branches on the
//     ad discriminator (e.g. isAd getter, SegmentType.ad) to find the exact reroute/kill point,
//     then to the UPSTREAM SOURCE all consumers share (e.g. Netflix `adverts.adBreaks`) — the
//     single edit that closes every downstream path at once.
//  4. LIVE-CAPTURE discipline: on chatty devices the logcat ring buffer rolls fast (Widevine spam
//     on Netflix), so `logcat -d` misses hits. Capture with a live `timeout N adb logcat` window
//     during the actual ad break, and use w:0 count keys to confirm "0 ad segments" post-patch.
//
// NETFLIX RESULT (for reference): this tap traced all ad-break stitchers (applyDaiPrefetch,
// mergeReplacedAds, enrichAds, the VOD StatefulAdBreak map) to ONE upstream source — the
// manifest->adverts normaliser `T.adverts=...{adBreaks:(...?void 0:ba.map(...normalize...))}` —
// which the shipped fix empties (`ba.map` -> `[].map`). See NOTICE + the Netflix memory notes.

function makeAdProbe(log){
  var L = log || function(m){ /* wire to your logger */ };
  function pat(s){ return s.split('').map(function(c){return ('0'+c.charCodeAt(0).toString(16)).slice(-2);}).join(' '); }

  // Default table = the Netflix ad-delivery keyword set. SWAP THIS for a new target app.
  // {k: needle, w: context-window bytes (0 = count-only), b: bytes-before to include}
  var KEYS = [
    // delivery-model discriminators
    {k:'"ads":[{', w:200, b:24},                                 // legacy manifest ad-pod array
    {k:'adInsertionType', w:220, b:120},                         // dynamic(DAI) vs static/manifest
    {k:':ad-0-', w:0, b:0},                                      // stitched ad-segment presence (count)
    {k:'"type":"ad"', w:0, b:0},                                 // playgraph ad segment (count)
    // decisioning
    {k:'clcsInterstitialPlaybackAndPostPlayback', w:200, b:20},  // CLCS interstitial op (UI, not scheduler)
    // upstream source + stitchers (source-residency + seam-lock)
    {k:':ba.map(function(a,b){var c=ea.normalize', w:360, b:60}, // adverts.adBreaks normaliser (the source)
    {k:'return e&&0!==e.size?a.map', w:340, b:60},               // applyDaiPrefetch guard
    {k:'enrichAds=function', w:420, b:20},
    {k:'mergeReplacedAds', w:420, b:40}
  ];

  var seen = {}, pass = 0;
  function adProbe(rs, keys){
    var table = keys || KEYS; pass++; var tot = {};
    for (var pi=0; pi<table.length; pi++){ var K=table[pi]; if(!K.k) continue; var p=pat(K.k);
      for (var i=0;i<rs.length;i++){ var r=rs[i]; if(r.size>128*1024*1024) continue;
        try{ var h=Memory.scanSync(r.base,r.size,p);
          if(h.length) tot[K.k]=(tot[K.k]||0)+h.length;
          for(var j=0;j<h.length&&j<3;j++){ var key=K.k+'@'+h[j].address; if(seen[key]) continue; seen[key]=1;
            if(K.w>0){ var s=null; try{ s=h[j].address.sub(K.b).readCString(K.w); }catch(e){}
              if(s){ s=s.replace(/[^\x20-\x7e]/g,'.'); L('ADPROBE ['+K.k+'] @'+h[j].address+' :: '+s); } }
          }
        }catch(e){}
      }
    }
    var names = Object.keys(tot);
    L('ADPROBE pass'+pass+' counts='+(names.length?JSON.stringify(tot):'{}'));
  }

  return { adProbe: adProbe, KEYS: KEYS, pat: pat };
}

// Node/CommonJS export for host tests; harmless in the Frida gadget (module undefined -> skipped).
if (typeof module !== 'undefined' && module.exports) module.exports = { makeAdProbe: makeAdProbe };
