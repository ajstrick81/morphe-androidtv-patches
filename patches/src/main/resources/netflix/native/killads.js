'use strict';
// Netflix ad-kill (self-contained, single-shot each, NO re-patch loop) + read-only monitor.
//  (A)   manifest pre/mid-roll (legacy model): AdsState hydration -> empty metadata.ads=[]
//  (ADV) dynamic ad-insertion master kill: empty adverts.adBreaks at its single manifest source
//  (DAI) dynamic ad-insertion belt: applyDaiPrefetch returns content unchanged when ads present
//  (MASTER) 13.0.1+ re-anchor: getAdMetadata -> empty ad-break list (if(0)+else b=[]); crash-free
//  (B)   pause overlay: z() saga -> force f=e.displayAd to void 0 -> no ad opportunity
//  (FP/GAID) privacy opt-ins (default OFF); (HH/MHU) household-prompt opt-in (default OFF)
//  MONITOR (read-only): KILLMARK(__adkill)/rawRealPods = legacy manifest ad kills; rawDisplayAd =
//  server pause ad delivered; BOOKMARK = distinct resume positions seen (resume/bookmark intact).
var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('KILL');
function L(m){ logw(6, TAG, Memory.allocUtf8String(m)); }
function pat(s){ return s.split('').map(function(c){return ('0'+c.charCodeAt(0).toString(16)).slice(-2);}).join(' '); }
function bytesOf(s){ var b=[]; for (var i=0;i<s.length;i++) b.push(s.charCodeAt(i)); return b; }

// ---------- (A) manifest patch — legacy metadata.ads[] pod model ----------
// Kept for anyone still served the old model. Newer appboots deliver dynamic ads (see ADV/DAI);
// on those this anchor simply isn't present and patchA no-ops harmlessly.
var A_OLD='var f=e.value;f.syncAdStates();f.state.isHydrated&&"viewable"!==f.metadata.source&&f.applyHydration(f.state.hydrationSequenceId)';
var A_NEW='var f=e.value;f.metadata&&(f.metadata.ads.length&&(f.__adkill=f.metadata.ads.length),f.metadata.ads=[]);f.syncAdStates();/*...*/';
var aDone=false;
function patchA(rs){ if(aDone)return; if(A_NEW.length!==A_OLD.length){L('A length mismatch — ABORT');aDone=true;return;}
  var p=pat(A_OLD);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){Memory.protect(h[j].address,A_NEW.length,'rw-');h[j].address.writeByteArray(bytesOf(A_NEW));aDone=true;L('PATCH A: prepareAdBreakStates @'+h[j].address);}}catch(e){}}
}
// ---------- (ADV) empty adverts.adBreaks at its single source — the master ad kill ----------
// Netflix migrated clone ads to DYNAMIC server-side insertion — ad videos stitched as separate
// "playgraph" viewables (type:"ad", s0:ad-0-x -> padend -> content). ALL ad-break stitchers
// (applyDaiPrefetch, mergeReplacedAds, enrichAds, the VOD StatefulAdBreak map) read ONE upstream
// source: the manifest->adverts normaliser builds
//   T.adverts=__assign(__assign({},T.manifest.adverts),{adBreaks:(null===(ba=T.manifest.adverts.
//     adBreaks)||void 0===ba ? void 0 : ba.map(function(a,b){var c=ea.normalize(a.location...)})) })
// Change `ba.map` -> `[].map` (2 bytes, length-preserving): when ads ARE present this yields an
// EMPTY array, so every downstream `.adverts.adBreaks.length` is 0 and the ad-break orchestrator
// hits its `else return` (no StatefulAdBreaks built, enrichers get nothing) — one edit closes all
// ad paths at the source. The no-ads branch (ternary `void 0`) is untouched -> no crash on ad-free
// content. On-device verified (v13.0.1): pre-roll + mid-roll gone across movies, no crash.
var ADV_ANCHOR=':ba.map(function(a,b){var c=ea.normalize';  // unique source site
var ADV_OFF=1, ADV_EXP='ba';
var advDone=false;
function patchADV(rs){ if(advDone)return; var p=pat(ADV_ANCHOR);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(ADV_OFF);var cur=null;try{cur=t.readCString(2);}catch(e){}if(cur!==ADV_EXP)continue;Memory.protect(t,2,'rw-');t.writeByteArray([0x5b,0x5d]);advDone=true;L('PATCH ADV: adverts.adBreaks source ba.map->[].map (empty all ad breaks) @'+t);}}catch(e){}}
}
// ---------- (ADVw) wildcard-tolerant ADV fallback (2026-09-13, experimental — #166) ----------
// The adverts.adBreaks normaliser re-minified alongside getAdMetadata, so the exact ADV anchor
// (:ba.map(...ea.normalize) scan-misses. Closing this matters: getAdMetadata's NEW middle branch
// `else if(a.adverts&&a.adverts.adBreaks.length)b=a.adverts.adBreaks.map(...)` rebuilds ad breaks
// from adverts.adBreaks when M1 forces if(0) — emptying adverts.adBreaks at the normaliser makes
// that guard false so the branch is skipped (-> else b=[]). Re-anchor by the STABLE tokens
// (.map(function( ... .normalize) and wildcard the churny map-source var. Only the ORIGINAL
// `<2char>.map->[].map` shape is safe to write length-preservingly; any other layout -> dump, no write.
var advwDone=false, advDumped=false;
function patchADVw(rs){ if(advDone||advwDone)return;
  // Anchor on the ad-normaliser's DISTINCTIVE callback shape: <var>.map(function(a,b){var
  // The adverts.adBreaks normaliser is `la.map(function(a,b){var c=ma.normalize(a.locationMs),
  // e=(0,v.getAdBreakDuration)(a.ads)...` — arity (a,b). The earlier false positive was the
  // normalizedPrograms getter `.map(function(c){return a.toNormalizedProgram(c)` — arity (c), and it
  // only matched because the old disambiguator keyed on ".normalize" (also in toNormalizedProgram).
  // Fix (2026-09-14, from adam8833's #166 dumps): anchor on `.map(function(a,b){var ` (excludes the
  // (c) getter) AND require a `locationMs`/`getAdBreakDuration` token in the window (ad-break-only).
  // <var> = the 2 bytes immediately before `.map`.
  var mp=pat('.map(function(a,b){var ');
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,mp);
      for(var j=0;j<h.length;j++){ var mAddr=h[j].address, vAddr=mAddr.sub(2); // 2-char var before .map
        var v0=-1,v1=-1;try{v0=vAddr.readU8();v1=vAddr.add(1).readU8();}catch(e){continue;}
        if(!isAlpha(v0)||!isAlpha(v1))continue;
        var fwd=null;try{fwd=mAddr.readCString(180);}catch(e){}
        if(fwd==null||(fwd.indexOf('locationMs')<0&&fwd.indexOf('getAdBreakDuration')<0))continue;
        Memory.protect(vAddr,2,'rw-');vAddr.writeByteArray([0x5b,0x5d]);advwDone=true;
        L('PATCH ADVw: adverts.adBreaks source "'+String.fromCharCode(v0)+String.fromCharCode(v1)+'".map->[].map (empty all ad breaks, rename-tolerant) @'+vAddr);
        return;
      }
    }catch(e){}}
  // not found -> dump the normaliser region once (anchored on the ad-only locationMs) for re-anchor.
  if(!advDumped){ var mk='locationMs',mkp=pat(mk),hit=0;
    for(var i2=0;i2<rs.length&&hit<2;i2++){var r2=rs[i2];if(r2.size>128*1024*1024)continue;
      try{var h2=Memory.scanSync(r2.base,r2.size,mkp);for(var j2=0;j2<h2.length&&hit<2;j2++){var a2=h2[j2].address,ctx=null;try{ctx=a2.sub(180).readCString(260);}catch(e){}if(ctx==null||ctx.indexOf('.map(function')<0)continue;hit++;advDumped=true;L('ADVw DUMP@'+a2+' ctx='+JSON.stringify(ctx));}}catch(e){}}
  }
}
// ---------- (DAI) dynamic ad-insertion bypass — belt-and-suspenders alongside ADV ----------
//   a.prototype.applyDaiPrefetch=function(a,b){ b=this.getDaiPrefetcher(b);
//     var e=(b==null)?void 0:b.getAds();
//     return e&&0!==e.size ? a.map(function(a){...map break->ad if adBreakTriggerId in e...}) : a }
// `a` = the content breaks; the map INSERTS the prefetched DAI ads. Flipping the guard
// `0!==e.size` -> `0===e.size` (one byte, `!`->`=`) makes it return `a` UNCHANGED whenever ads
// ARE present -> no DAI ads stitched. Length-preserving, verify-before-write.
var DAI_ANCHOR='return e&&0!==e.size?a.map';   // '!' at index 11 (return e&&0[!]==e.size...)
var DAI_OFF=11, DAI_EXP='!';
var daiDone=false;
function patchDAI(rs){ if(daiDone)return; var p=pat(DAI_ANCHOR);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(DAI_OFF);var cur=null;try{cur=t.readCString(1);}catch(e){}if(cur!==DAI_EXP)continue;Memory.protect(t,1,'rw-');t.writeByteArray([0x3d]);daiDone=true;L('PATCH DAI: applyDaiPrefetch 0!==->0=== (bypass DAI ad stitch) @'+t);}}catch(e){}}
}
// ---------- (A2) legacy metadata.ads presentation gate (2026-08-14 re-anchor) ----------
// Netflix moved the legacy pre/mid-roll off the compiled-away prepareAdBreakStates hydration
// reducer (which killed patch-A's source anchor -> A=false) but the ad DATA still lands in each
// StatefulAdBreak's `metadata.ads`. The single source-resident gate that turns that data into
// PRESENTABLE ad objects is `_syncAdsLength` (the `ads` getter delegates to it):
//   {..e=this._statefulAdBreak;e.syncAdStates();
//    var f=null!==(b=null===(a=e.metadata.ads)||void 0===a?void 0:a.length)&&void 0!==b?b:0;
//    this._ads.length=f;for(a=0;a<f;a++)(c=this._ads)[a]||(c[a]=new n.AseAd(e,a,this.config));..}
// Flipping the read `e.metadata.ads` -> `e.metadata.axs` (nonexistent prop) makes a=undefined ->
// f=0 -> _ads emptied -> zero AseAd objects instantiated for EVERY break, whatever manifest path
// populated metadata.ads. Length-preserving (3 bytes), verify-before-write. Covers the leaky
// legacy titles that bypass adverts.adBreaks (ADV) entirely.
var A2_ANCHOR='e.metadata.ads)||void 0===a?void 0:a.length)&&void 0!==b?b:0;this._ads.length';
var A2_OFF=11, A2_EXP='ads';   // 'ads' at index 11 of the anchor (e.metadata.[ads])
var a2Done=false;
function patchA2(rs){ if(a2Done)return; var p=pat(A2_ANCHOR);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(A2_OFF);var cur=null;try{cur=t.readCString(3);}catch(e){}if(cur!==A2_EXP)continue;Memory.protect(t,3,'rw-');t.writeByteArray([0x61,0x78,0x73]);a2Done=true;L('PATCH A2: _syncAdsLength metadata.ads->axs (empty legacy ad pods) @'+t);}}catch(e){}}
}
// ---------- (MASTER) getAdMetadata source kill — 13.0.1+ re-anchor (2026-08-24, STABLE v2) ----------
// On 13.0.1 build 25028 the ad path was REFACTORED: A2/ADV/DAI source strings drifted (their
// patchers scan-miss and no-op). ALL ad breaks now flow through ONE builder:
//   a.prototype.getAdMetadata=function(a){ var b,c,d=this.mediaEventsManager.getAds(a);
//     if(d){ var e=...; b=d.map(function(a){return new r.StatefulAdBreak(a,...)}) } else return;
//     b=this.adBreakHydrator.enrichAds(b); b=this.adErrorHandler.enrichAds(a,b);
//     b=this.enrichAdsWithEmbeddedExtension(a,...); return ...b... }
// v1 (SHIPPED-CRASH, reverted): flipped only if(d)->if(0), which hits `else return;` -> returns
// UNDEFINED even for ad-titles while getAds() still returned real ads -> app-state inconsistency
// -> CRASH on Back/teardown. Fix: return a VALID EMPTY ARRAY, never undefined. TWO length-preserving
// edits, both verify-before-write:
//   M1: if(d) -> if(0)         (skip building real StatefulAdBreaks)
//   M2: else return; -> else b=[]  ;   (b becomes []; every enrichAds() is array.map over [] -> [];
//       enrichAdsWithEmbeddedExtension iterates [] -> attaches nothing -> function returns valid [])
// Source-dumped + both bodies confirmed 2026-08-24 (experimental/netflix-native-adstrip/REANCHOR_13.0.1.md).
// Supersedes drifted A2/ADV/DAI on 13.0.1+; older anchors kept (scan-miss here, cover pre-refactor).
var M1_ANCHOR='getAds(a);if(d){var e=', M1_OFF=13, M1_EXP='d';         // the 'd' inside if(d)
var M2_ANCHOR='else return;b=this.adBreakHydrator', M2_OLD='else return;', M2_NEW='else b=[]  ;'; // 12->12
var m1Done=false, m2Done=false, masterDone=false;
function patchMASTER(rs){ if(masterDone)return;
  if(!m1Done){ var p1=pat(M1_ANCHOR);
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,p1);for(var j=0;j<h.length;j++){var t=h[j].address.add(M1_OFF);var cur=null;try{cur=t.readCString(1);}catch(e){}if(cur!==M1_EXP)continue;Memory.protect(t,1,'rw-');t.writeByteArray([0x30]);m1Done=true;L('PATCH M1: getAdMetadata if(d)->if(0) @'+t);}}catch(e){}}
  }
  if(!m2Done){ var p2=pat(M2_ANCHOR);
    for(var i2=0;i2<rs.length;i2++){var r2=rs[i2];if(r2.size>128*1024*1024)continue;
      try{var h2=Memory.scanSync(r2.base,r2.size,p2);for(var j2=0;j2<h2.length;j2++){var t2=h2[j2].address;var cur2=null;try{cur2=t2.readCString(M2_OLD.length);}catch(e){}if(cur2!==M2_OLD)continue;Memory.protect(t2,M2_NEW.length,'rw-');t2.writeByteArray(bytesOf(M2_NEW));m2Done=true;L('PATCH M2: getAdMetadata else return;->else b=[] (valid empty, no crash) @'+t2);}}catch(e){}}
  }
  if(m1Done&&m2Done){ masterDone=true; L('PATCH MASTER: getAdMetadata neutralised (empty ad-break list) — stable v2'); }
}
// Tight early MASTER scanner — win the FIRST-TITLE race. getAdMetadata builds ALL breaks (pre+mid)
// for a title in ONE call at playback start; the 2s apply() poll can land AFTER that call on a fast
// start, so the first title keeps its ads. This 120ms loop lands MASTER within ~120ms of the source
// materialising (~+25s), before the first getAdMetadata runs. Write-once (masterDone guard) — stops
// as soon as both edits land. Mirrors fastHH.
var _fastMn=0;
function fastMASTER(){ if(masterDone)return; if(hhYield()){ setTimeout(fastMASTER,250); return; } _fastMn++;
  try{ var _rm=Process.enumerateRanges('rw-'); patchMASTER(_rm); if(!masterDone) patchMASTERw(_rm); }catch(e){}
  if(masterDone){ L('fastMASTER: applied by pass '+_fastMn+(mwDone?' (wildcard fallback)':'')); return; }
  if(_fastMn<500) setTimeout(fastMASTER, 120);   // ~60s of tight scanning
}
// ---------- (MASTERw) wildcard-tolerant MASTER fallback (2026-09-13, experimental — issue #166) --
// Most Netflix "server-side" ad drift is just a re-minify that RENAMES single-char locals, which
// makes the exact-string MASTER anchors scan-miss and the kill silently no-op (#166: KILLMARK=0
// while rawRealPods>0). This fallback re-matches getAdMetadata by its STABLE API tokens (getAds /
// adBreakHydrator) and WILDCARDS the churny locals, so a pure rename no longer breaks us. Additive:
// only runs when the exact patchMASTER missed (masterDone=false), so builds where the exact anchors
// still hit are unchanged. Reads the REAL accumulator var before writing (never hardcodes 'b') to
// avoid the v1 wrong-var crash. Length-preserving, verify-before-write.
function isAlpha(cc){ return (cc>=97&&cc<=122)||(cc>=65&&cc<=90); }   // a-z A-Z (minified local)
var mwDone=false, mwDumped=false;
// ATOMIC: locate BOTH the M1 (if-guard) and M2 (else-return) edit sites BEFORE writing EITHER, so we
// can never leave M1 flipped without M2 (that is the v1 `if(0)...else return;`->undefined crash).
// If M1 is found but M2 is not, we write NOTHING (getAdMetadata stays intact = crash-safe; A2/DAI
// still provide ad coverage) and dump the region after M1 ONCE so M2 can be re-anchored precisely.
function patchMASTERw(rs){ if(masterDone||mwDone)return;
  // --- locate M1: getAds( <arg> );if( <cond> ){var  -> the <cond> byte (offset 13) ---
  var p1=pat('getAds(')+' ?? '+pat(');if(')+' ?? '+pat('){var '), m1off=13;
  var m1addr=null,m1cc=-1;
  for(var i=0;i<rs.length&&!m1addr;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p1);for(var j=0;j<h.length;j++){var t=h[j].address.add(m1off);var cc=-1;try{cc=t.readU8();}catch(e){}if(!isAlpha(cc))continue;m1addr=t;m1cc=cc;break;}}catch(e){}}
  if(!m1addr)return;   // getAdMetadata body not resident yet — try again next pass
  // --- locate M2: else return; [gap] <acc> =this.adBreakHydrator ---
  // The accumulator sits just before '=this.adBreakHydrator'. Older builds had NO gap
  // (else return;b=this...); the 2026-09 build inserted a newline (else return;\nb=this...). Try
  // gap sizes 1 and 2 so both layouts match; <acc> is the alpha byte right before '=this.'.
  // Overwrite the 12-byte 'else return;' with 'else <acc>=[]  ;' so the else path yields a VALID
  // empty array (never undefined) that flows through the enrich chain -> returns [] (no v1 crash).
  var m2addr=null,m2vc=-1;
  var gaps=[1,2];
  for(var gi=0;gi<gaps.length&&!m2addr;gi++){ var gap=gaps[gi];
    var p2=pat('else return;'); for(var gg=0;gg<gap;gg++) p2+=' ??'; p2+=' '+pat('=this.adBreakHydrator');
    var accOff=12+gap-1;   // the <acc> byte = last wildcard, immediately before '=this.'
    for(var i2=0;i2<rs.length&&!m2addr;i2++){var r2=rs[i2];if(r2.size>128*1024*1024)continue;
      try{var h2=Memory.scanSync(r2.base,r2.size,p2);for(var j2=0;j2<h2.length;j2++){var vc=-1;try{vc=h2[j2].address.add(accOff).readU8();}catch(e){}if(!isAlpha(vc))continue;m2addr=h2[j2].address;m2vc=vc;break;}}catch(e){}}
  }
  if(m2addr){
    var vch=String.fromCharCode(m2vc), neu='else '+vch+'=[]  ;';
    if(neu.length===12){
      Memory.protect(m1addr,1,'rw-');m1addr.writeByteArray([0x30]);
      Memory.protect(m2addr,12,'rw-');m2addr.writeByteArray(bytesOf(neu));
      mwDone=true;masterDone=true;
      L('PATCH MASTERw: getAdMetadata if('+String.fromCharCode(m1cc)+')->if(0) @'+m1addr+' + else return;->else '+vch+'=[] @'+m2addr+' (atomic, rename-tolerant) — string-drift recovered');
    }
    return;
  }
  // M1 found, M2 not -> CRASH-SAFE: write nothing; dump the region after M1 once for re-anchor.
  if(!mwDumped){ mwDumped=true; var ctx=null; try{ctx=m1addr.sub(24).readCString(700);}catch(e){}
    L('MASTERw M2-MISS (no write, crash-safe) dump@'+m1addr+' cond="'+String.fromCharCode(m1cc)+'" ctx='+JSON.stringify(ctx)); }
}
// ---------- (MASTERdump) read-only recon: dump CURRENT getAdMetadata body on drift ----------
// If BOTH the exact and wildcard MASTER matches miss, the drift is a real REFACTOR, not a rename —
// we need the new source to re-anchor. This dumps ~500 chars from the getAdMetadata token to logcat
// ONCE so the new body can be read off-device. Runs only if masterDone stays false ~34s in (ads
// would already be leaking by then). Read-only; no writes.
var _mdumpN=0, mdumpDone=false;
function dumpMASTER(){ if(masterDone||mdumpDone)return; _mdumpN++;
  if(_mdumpN<17){ setTimeout(dumpMASTER,2000); return; }   // wait ~34s for appboot JS to materialise
  var rs=Process.enumerateRanges('rw-'), mk='getAdMetadata', mp=pat(mk), hits=0;
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,mp);for(var j=0;j<h.length&&hits<4;j++){var addr=h[j].address,ctx=null;try{ctx=addr.readCString(520);}catch(e){}if(ctx==null||ctx.indexOf(mk)<0)continue;hits++;L('MASTER-DUMP hit@'+addr+' ctx='+JSON.stringify(ctx));}}catch(e){}}
  mdumpDone=true; L('MASTER-DUMP: '+hits+' getAdMetadata source hit(s) — re-anchor from these bodies (or 0 = token itself renamed)');
}
// ---------- (B) pause patch ----------
var B_ANCHOR='void 0:e.displayAd',B_OLD='e.displayAd',B_NEW='void 0     ';
var bDone=false;
function patchB(rs){ if(bDone)return; var p=pat(B_ANCHOR);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(7);var cur=null;try{cur=t.readCString(B_OLD.length);}catch(e){}if(cur!==B_OLD)continue;Memory.protect(t,B_NEW.length,'rw-');t.writeByteArray(bytesOf(B_NEW));bDone=true;L('PATCH B: pause z() displayAd->void0 @'+t);}}catch(e){}}
}
// ---------- (FP) local network fingerprint minimization (OPT-IN; default OFF) ----------
// Blanks ip/ipv6/mac/ssid in the c() interface map that feeds the nrdp device signal
// (u.ifList). ONLY changes what is REPORTED to Netflix, not the device's actual
// networking (keeps ifname/type). Does NOT stop the server-side "traveling" prompt
// (that's driven by your public IP). The "Minimize Network Fingerprint" opt-in Morphe
// patch flips FP_ENABLED to true in this bundled script.
var FP_ENABLED=false;
var FP_ANCH='ip:e.ipAddress,ipv6:i(e),mac:e.macAddress,name:e.ifname,ssid:e.ssid';
var FP_NEW ='ip:""         ,ipv6:[]  ,mac:""          ,name:e.ifname,ssid:""    ';
var fpDone=false;
function patchFP(rs){ if(fpDone||!FP_ENABLED)return; if(FP_NEW.length!==FP_ANCH.length){L('FP length mismatch — ABORT');fpDone=true;return;}
  var p=pat(FP_ANCH);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){Memory.protect(h[j].address,FP_NEW.length,'rw-');h[j].address.writeByteArray(bytesOf(FP_NEW));fpDone=true;L('PATCH FP: blank ip/ipv6/mac/ssid @'+h[j].address);}}catch(e){}}
}
// ---------- (GAID) advertising-ID (GAID) minimization (OPT-IN; default OFF) ----------
// nrdp.device.getSystemValue("advertisingIdDetails", function(e){
//   var t=!!e&&"advertisingId"in e, a=(null==e?void 0:e.advertisingId)||"",
//       o="true"===...limitAdTracking, i=o?"":a;
//   logEvent("AdvertisingIdCollectionEvent","netflix",{advertisingId:i,...}) })
// We overwrite the property read `e.advertisingId` (15 bytes) with `void 0` (pad to
// 15) so a="" -> i="" and the actual GAID is never reported in the collection event.
// Length-preserving; keeps advertisingIdSupported/limitAdTracking booleans truthful.
// Folded under the "Minimize Network Fingerprint" opt-in (flips GAID_ENABLED true).
var GAID_ENABLED=false;
var GAID_ANCH='null==e?void 0:e.advertisingId)';
var GAID_OFF=15;                 // offset of 'e.advertisingId' within anchor ("null==e?void 0:")
var GAID_OLD='e.advertisingId';
var GAID_NEW='void 0         ';  // 15 chars: 'void 0' + 9 spaces
var gaidDone=false;
function patchGAID(rs){ if(gaidDone||!GAID_ENABLED)return; if(GAID_NEW.length!==GAID_OLD.length){L('GAID length mismatch — ABORT');gaidDone=true;return;}
  var p=pat(GAID_ANCH);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(GAID_OFF);var cur=null;try{cur=t.readCString(GAID_OLD.length);}catch(e){}if(cur!==GAID_OLD)continue;Memory.protect(t,GAID_NEW.length,'rw-');t.writeByteArray(bytesOf(GAID_NEW));gaidDone=true;L('PATCH GAID: e.advertisingId->void0 @'+t);}}catch(e){}}
}
// ---------- (HH) household / "traveling" misdetection + ownership prompt suppression (OPT-IN) ----------
// Default OFF; the "Suppress Household Prompt" Morphe patch flips HH_ENABLED to true.
// The account-sharing redux-saga stores the server's misdetection challenge, then dispatches
//   put(setAccountSharingFlags({isActiveMisdetectionSession:!0}))  to ACTIVATE the challenge.
// The consumer hook computes  "ready"===status && (y=f, w=h)  ->  {misdetectionAvailable:y,
// netflixHouseholdAvailable:w}, where f=isActiveMisdetectionSession ("you're traveling") and
// h=isNetflixHouseholdAvailable ("This is my account / Create an account" ownership screen — a
// SEPARATE gate). We flip BOTH dispatch flags !0 -> !1 (WRITE side) AND force the render outputs
// f/h -> 0 (READ side); all length-preserving 1-byte edits. On a fresh launch these land (+~11s)
// before the gate materialises (~+30s) so the app proceeds with no prompt.
// HONEST SCOPE: client-side prompt suppression only; household detection is server-side/public-IP.
// CREDIT: CLCS-interstitial enforcement seam identified with reference to Nikflix
// (github.com/YidirK/Nikflix, GPL-3.0) — author YidirK and API-block contributor Buckibarnes17
// (github.com/Buckibarnes17), credited in the Nikflix v2.0.1 release notes. No code shared —
// independent native-app implementation (appboot-heap byte edit, not fetch/XHR override). See NOTICE.
var HH_ENABLED=false;
var HH_ANCHORS=[
  {a:'setAccountSharingFlags)({isActiveMisdetectionSession:!0', chr:'0', to:'1', lbl:'flag:isActiveMisdetectionSession'},
  {a:'setAccountSharingFlags)({isNetflixHouseholdAvailable:!0', chr:'0', to:'1', lbl:'flag:isNetflixHouseholdAvailable'},
  {a:'(y=null!=f&&f,', off:12, chr:'f', to:'0', lbl:'render:misdetectionAvailable(y)'},
  {a:'w=null!=h&&h)',  off:11, chr:'h', to:'0', lbl:'render:netflixHouseholdAvailable(w)'}
];
var hhFlipped={},hhDone=false;
function patchHH(rs){ if(hhDone||!HH_ENABLED)return;
  for(var ai=0;ai<HH_ANCHORS.length;ai++){ var AN=HH_ANCHORS[ai]; if(hhFlipped[AN.lbl])continue;
    var p=pat(AN.a),off=(AN.off!=null?AN.off:AN.a.length-1),exp=(AN.chr||'0'),nb=(AN.to||'1').charCodeAt(0);
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(off);var cur=null;try{cur=t.readCString(1);}catch(e){}if(cur!==exp)continue;Memory.protect(t,1,'rw-');t.writeByteArray([nb]);hhFlipped[AN.lbl]=true;L('PATCH HH: '+AN.lbl+' '+exp+'->'+(AN.to||'1')+' @'+t);}}catch(e){}}
  }
  // Completion = the household READ gate flipped (the operative gate for the ownership screen).
  if(hhFlipped['render:netflixHouseholdAvailable(w)'])hhDone=true;
}
// Aggressive early HH patcher: the appboot source materialises ~20-30s in. This tight 100ms loop
// (no gibbon gate, no initial delay) flips the anchors + neuters the MHU screens the instant they
// appear, to win the parse race by minimising scan latency.
// PRIORITY GATE (2026-08-30): fastHH's per-pass 16-MHU Memory.scanSync sweep and fastMASTER's
// getAdMetadata kill compete on the ONE JS thread. On-device a household build let a PRE-ROLL slip
// because fastHH starved fastMASTER (MASTER landed +38s vs ~+25s clean). So we YIELD: don't run the
// heavy HH sweep until masterDone (the pre-roll kill) lands — the household gate doesn't mount until
// ~+30-44s and the getAdMetadata source appears ~+25s, so deferring the HH scan the few seconds until
// MASTER lands costs no household runway. Safety fallback: after ~30s of waiting, proceed regardless
// (if the MASTER anchor is absent this launch there are no ads to protect, and HH is time-critical).
var _fastHHn=0, _fastHHwait=0;
function fastHH(){ if(!HH_ENABLED||hhDone)return;
  if(!masterDone && _fastHHwait<300){ _fastHHwait++; setTimeout(fastHH,100); return; }   // yield to fastMASTER
  if(_fastHHwait && _fastHHn===0) L('fastHH: released after '+_fastHHwait+' waits (masterDone='+masterDone+')');
  _fastHHn++;
  try{ var _r=Process.enumerateRanges('rw-'); patchHH(_r); neuterMhuRenders(_r); }catch(e){}
  if(hhDone){ L('fastHH: all HH anchors flipped by pass '+_fastHHn); return; }
  if(_fastHHn<450) setTimeout(fastHH, 100);   // ~45s of tight scanning
}
// ---------- (MHU) household-screen render neuter — belt-and-suspenders (follows HH opt-in) ----------
// LAYER 2 of household suppression. LAYER 1 (HH_ANCHORS/fastHH) neuters the DECISION so the app
// PROCEEDS — the actual fix. This layer is a fallback: if the decision flip loses the ~30s race,
// null the household screens' render() so the prompt shows BLANK instead of the ownership UI.
// Each CLCS screen is registered as <PATTERN>,render:function(e){<DECLS>\nreturn(0,u.j)(...)}; we
// overwrite <DECLS> (just after '{' up to the first newline) with 'return null;' + spaces, leaving
// the original '\nreturn(...)' as valid unreachable code -> render() returns null, module parses.
// Newline located at RUNTIME (resilient across the household screens). Household (Mhu*) patterns
// only — the profile picker ("who's watching") is a different, non-Mhu screen and is never touched.
var MHU_ENABLED=HH_ENABLED;   // ships with the "Suppress Household Prompt" opt-in (flips HH_ENABLED).
var MHU_PATS=[
  'MHU_RESOLUTION_LANDING','MHU_MANAGE_HOUSEHOLD',
  'MHU_SET_PRIMARY_HOUSEHOLD_CONTEXT','MHU_SET_PRIMARY_HOUSEHOLD_EMAIL_CONFIRM',
  'MHU_SET_PRIMARY_HOUSEHOLD_TEXT_CONFIRM','MHU_SET_PRIMARY_HOUSEHOLD_VERIFY',
  'MHU_SET_PRIMARY_HOUSEHOLD_LATER','MHU_SET_PRIMARY_HOUSEHOLD_SUCCESS',
  'MHU_CREATE_ACCOUNT_PARTNER','MHU_CREATE_ACCOUNT_RENDEZVOUS_CONTEXT',
  'MHU_CREATE_ACCOUNT_RENDEZVOUS_EMAIL','MHU_CREATE_ACCOUNT_RENDEZVOUS_EMAIL_CONFIRM',
  'MHU_CREATE_ACCOUNT_RENDEZVOUS_PHONE','MHU_CREATE_ACCOUNT_RENDEZVOUS_PHONE_CONFIRM',
  'MHU_VERIFY_TRAVEL','MHU_CHALLENGE_ERROR'
];
var MHU_PREFIX='return null;';        // 12 bytes; padded with spaces to the decls length
var mhuDoneSet={}, mhuHits=0;
function neuterMhuRenders(rs){ if(!MHU_ENABLED)return;
  for(var pi=0;pi<MHU_PATS.length;pi++){ var nm=MHU_PATS[pi]; if(mhuDoneSet[nm])continue;
    var anchor=nm+',render:function(e){', p=pat(anchor);
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,p);
        for(var j=0;j<h.length;j++){
          var body=h[j].address.add(anchor.length);          // first byte after '{'
          var head=null; try{head=body.readCString(4);}catch(e){}
          if(head==='retu'){ mhuDoneSet[nm]=true; continue; } // already neutered
          if(head!=='var '){ continue; }                      // unexpected layout — abort this hit
          var bytes=null; try{bytes=body.readByteArray(400);}catch(e){continue;}
          var u8=new Uint8Array(bytes), nlIdx=-1;
          for(var k=0;k<u8.length;k++){ if(u8[k]===0x0a){ nlIdx=k; break; } }
          if(nlIdx<MHU_PREFIX.length){ continue; }            // no newline in range / decls too short
          var repl=MHU_PREFIX; while(repl.length<nlIdx) repl+=' ';
          Memory.protect(body,nlIdx,'rw-'); body.writeByteArray(bytesOf(repl));
          mhuDoneSet[nm]=true; mhuHits++;
          L('PATCH MHU: '+nm+' render->null ('+nlIdx+'B decls) @'+body);
        }
      }catch(e){}
    }
  }
}

// ---------- (CLCS) EXPERIMENTAL runtime interstitial-enforcement neuter — Nikflix seam ----------
// Nikflix (web) shows household enforcement is a PER-/watch RUNTIME GraphQL call, not a boot flag:
//   op "CLCSInterstitialPlaybackAndPostPlayback" @ web.prod.cloud.netflix.com/graphql -> they return
//   {"data":{}} and the prompt never fires. On ATV the net stack is native (Gibbon), not fetch/XHR,
//   so we can't override fetch. Instead we target the JS that CONSUMES the CLCS response, the same
//   memory-scan+byte-edit way fastMASTER neuters getAdMetadata — a race-free play-time seam that
//   beats the ~30s appboot HH/MHU flag race. See killads.js:175 credit + NOTICE.
//
// STATUS: the ATV consumer string is UNKNOWN. clcsProbe (read-only) locates every heap hit of the
// op name and dumps the surrounding source to logcat so we can read the real anchor off-device on
// .211. Once identified, add the exact {a,off,chr,to,lbl} to CLCS_ANCHORS and the neuter goes live.
// Gated behind HH_ENABLED (household opt-in) so nothing runs unless the user enabled suppression.
// Nikflix's web op is PascalCase CLCSInterstitialPlaybackAndPostPlayback, but prior ATV heap recon
// (2026-08-13) found the ATV JS heap carries the camelCase `clcsInterstitialPlaybackAndPostPlayback`
// and — crucially — the HOUSEHOLD gate is a DIFFERENT op: `clcsInterstitialProfileGate`
// (QueryType.ProfileGate), which also drives legit profile-select, so any neuter must discriminate
// the MHU/household variant (see neuterMhuRenders). We probe the whole family + the ProfileGate router
// site so the on-device dump shows exactly which consumer string to anchor.
// FOCUSED probe (2026-08-30): the 4-marker sweep already told us the only JS-SOURCE anchor is
// `pushClcsInterstitialGate` (0x50c/0x53); the op names live only in network/telemetry buffers.
// Narrow to that one marker and dump WIDE context (before+after) to capture the router push fn AND
// its `d(e,t)` builder, so we can design a discriminating length-preserving neuter of the
// ProfileGate/MHU branch without touching generic profile-select.
var CLCS_MARKERS=[
  'pushClcsInterstitialGate'                       // router push seam — the JS-source anchor
];
// CLCS gate-dispatcher neuter (2026-08-30, from focused probe @0x5334054e). The gate router matches
// the server interstitial `type` against case labels; household ownership arrives as type
// "clcsInterstitialGate" and is pushed to the CLCS_INTERSTITIAL_GATE screen via pushClcsInterstitialGate.
// We flip the LEADING 'c' of that ONE case string literal ("clcsInterstitialGate"->"xlcsInterstitialGate",
// 1 byte, length-preserving) so the type never matches -> falls through to default = NO navigation, no
// household screen. Scope is precise: the sibling label "clcsInterstitialPreProfileGate" shares the body
// so it still fires via its OWN label, and "who's watching" profile-select is a DIFFERENT case
// (profileSelectionTransitionAnimation) — neither is touched. Write-once via apply() (NO scan loop, so
// no fastMASTER starvation). NOTE: also drops other clcsInterstitialGate-typed interstitials
// (price-change/upsell — desired); verify no load-bearing gate (maturity/PIN) shares the type on-device.
var CLCS_ANCHORS=[
  {a:'case"clcsInterstitialGate":case"clcsInterstitialPreProfileGate"', off:5, chr:'c', to:'x',
   lbl:'dispatch:clcsInterstitialGate-case-break'},
  // 2026-09-24 (.210, live challenge): the HOUSEHOLD prompt raised at profile select is fetched with
  // QueryType.ProfileGate, and the fetch builder labels it componentName:"clcsInterstitialPreProfileGate"
  // (not "clcsInterstitialGate"), so the label above never saw it. Break this label too, same 1-byte flip.
  {a:'case"clcsInterstitialPreProfileGate":', off:5, chr:'c', to:'x',
   lbl:'dispatch:clcsInterstitialPreProfileGate-case-break'}
];
var clcsFlipped={},clcsDone=false;
function patchCLCS(rs){ if(clcsDone||!HH_ENABLED)return; if(!CLCS_ANCHORS.length)return;
  var all=true;
  for(var ai=0;ai<CLCS_ANCHORS.length;ai++){ var AN=CLCS_ANCHORS[ai]; if(clcsFlipped[AN.lbl])continue; all=false;
    var p=pat(AN.a),off=(AN.off!=null?AN.off:AN.a.length-1),exp=(AN.chr||'0'),nb=(AN.to||'1').charCodeAt(0);
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){var t=h[j].address.add(off);var cur=null;try{cur=t.readCString(1);}catch(e){}if(cur!==exp)continue;Memory.protect(t,1,'rw-');t.writeByteArray([nb]);clcsFlipped[AN.lbl]=true;L('PATCH CLCS: '+AN.lbl+' '+exp+'->'+(AN.to||'1')+' @'+t);}}catch(e){}}
  }
  if(all)clcsDone=true;
}
// fastCLCS (2026-09-24): race-win for the gate dispatcher. On .210 both labels were broken at +55s via
// apply() (which runs ~15 full scans per pass) and the household gate STILL routed — the ProfileGate
// interstitial is fetched + pushed while the profile screen loads, before apply() lands. This loop scans
// ONE combined pattern every 150ms from launch and flips both case-label bytes the instant the dispatcher
// source is resident, so V8 compiles the already-broken labels on the first call. Write-once.
var FC_ANCHOR='case"clcsInterstitialGate":case"clcsInterstitialPreProfileGate":', FC_OFFS=[5,32];
// ROUTE 2 (2026-09-24): clicking PLAY re-fetches an interstitial (the ATV twin of Nikflix's
// CLCSInterstitialPlaybackAndPostPlayback) and doRouteOnFetchedInterstitial pushes the household gate
// DIRECTLY (pushClcsInterstitialGate / replaceWithClcsInterstitialGate), never touching the dispatcher.
// Its first guard is `...fetchDgsData"}),!v){p.next=24;break}` = "no interstitial -> exit to playback".
// Flip the var byte to '0' (`!v` -> `!0`, always true) so every fetched playback interstitial is
// treated as absent -> straight to playback. Same effect as Nikflix returning {data:{}}.
// Anchored on Netflix's own debug string (stable across re-minify); verify var+`){` before writing.
// NOTE: also skips UMS notices fetched at playback (payment-hold etc.) — acceptable for this opt-in.
var FR_ANCHOR='doRouteOnFetchedInterstitial:fetchDgsData"}),!';
var frDone=false;
var _fcN=0, _fcT0=Date.now();
// Cold-start priority (2026-09-24): on a cold boot every full-memory scanner (fastMASTER, apply, observe)
// queued ahead of fastCLCS and its 2nd pass slipped to +52s — after the profile gate had already routed.
// While the household dispatcher is still unpatched (household opt-in only, max 30s), the other scanners
// step aside so fastCLCS owns the JS thread. Nothing can play before profile select, so the ad-kill loses
// no coverage; the 30s cap bounds the delay if the dispatcher anchor ever drifts.
function hhYield(){ return HH_ENABLED && !clcsDone && (Date.now()-_fcT0)<30000; }
function fastCLCS(){ if(!HH_ENABLED||(clcsDone&&frDone))return; _fcN++;
  var rs=Process.enumerateRanges('rw-');
  if(!clcsDone){ var p=pat(FC_ANCHOR), hit=0;
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){ var a=h[j].address;
        for(var k=0;k<FC_OFFS.length;k++){ var t=a.add(FC_OFFS[k]); if(t.readU8()!==0x63)continue; Memory.protect(t,1,'rw-'); t.writeU8(0x78); }
        hit++; }}catch(e){}}
    if(hit){ clcsDone=true; for(var ai=0;ai<CLCS_ANCHORS.length;ai++) clcsFlipped[CLCS_ANCHORS[ai].lbl]=true;
      L('PATCH CLCS(fast): both gate case-labels broken x'+hit+' at +'+(Date.now()-_fcT0)+'ms (pass '+_fcN+')'); }
  }
  if(!frDone){ var p2=pat(FR_ANCHOR), hit2=0;
    for(var i2=0;i2<rs.length;i2++){var r2=rs[i2];if(r2.size>128*1024*1024)continue;
      try{var h2=Memory.scanSync(r2.base,r2.size,p2);for(var j2=0;j2<h2.length;j2++){ var t2=h2[j2].address.add(FR_ANCHOR.length);
        var vc=t2.readU8(), c1=t2.add(1).readU8(), c2=t2.add(2).readU8();
        if(vc===0x30){ hit2++; continue; }                       // already '0'
        if(!isAlpha(vc)||c1!==0x29||c2!==0x7b) continue;         // expect <var>){
        Memory.protect(t2,1,'rw-'); t2.writeU8(0x30); hit2++;
        L('PATCH CLCS(route2): doRouteOnFetchedInterstitial !'+String.fromCharCode(vc)+'->!0 (playback interstitial -> exit) @'+t2); }}catch(e){}}
    if(hit2){ frDone=true; L('PATCH CLCS(route2): done x'+hit2+' at +'+(Date.now()-_fcT0)+'ms (pass '+_fcN+')'); }
  }
  if(clcsDone&&frDone) return;
  // tight while the dispatcher is pending (~2 min), then a light 1s cadence for route 2 (~10 min)
  if(!clcsDone&&_fcN<800) setTimeout(fastCLCS,150);
  else if(_fcN<1400) setTimeout(fastCLCS,1000);
}
// Read-only diagnostic: find each CLCS_MARKERS string in the JS heap and dump ~100 chars of context
// per hit so the runtime consumer site can be identified from logcat. Runs only while HH_ENABLED and
// stops once ANY marker is seen resident (per-marker recon showed household loads ~+30-44s in), to
// avoid log spam. Household family is JS-only (2026-08-13: libnetflix.so had 0 hits), so heap-scan
// is the correct surface — do NOT expect these in native memory.
// DEV-ONLY recon flag, decoupled from HH_ENABLED. The probe is a heavy multi-marker heap scan that
// competes with fastMASTER (the getAdMetadata pre-roll kill) on the single JS thread; leaving it on
// in a household build starves the ad-kill race and lets a pre-roll slip. It has ALREADY captured its
// target (2026-08-29: anchor = `pushClcsInterstitialGate` in JS source @0x50c/0x53; the ProfileGate /
// Playback op names appear only in network+telemetry buffers, not anchorable). Keep OFF unless re-recon.
// Run this build WITHOUT the household opt-in: pushClcsInterstitialGate is the GENERIC gate router
// (resident on any appboot), so fastHH never runs and the probe doesn't compete with fastMASTER —
// protecting the ad-kill race that the last (household+probe) build starved.
var CLCS_PROBE_ENABLED=false;
var CLCS_CTX_BEFORE=220, CLCS_CTX_AFTER=460;   // wide window: capture push fn + d(e,t) builder
var _clcsProbeN=0, clcsProbeDone=false;
function clcsProbe(){ if(!CLCS_PROBE_ENABLED||clcsProbeDone)return; _clcsProbeN++;
  var rs=Process.enumerateRanges('rw-'), found=0;
  for(var mi=0;mi<CLCS_MARKERS.length;mi++){ var mk=CLCS_MARKERS[mi], mp=pat(mk), mhits=0;
    for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
      try{var h=Memory.scanSync(r.base,r.size,mp);
        for(var j=0;j<h.length&&mhits<6;j++){
          var addr=h[j].address, ctx=null;
          try{ctx=addr.sub(CLCS_CTX_BEFORE).readCString(CLCS_CTX_BEFORE+mk.length+CLCS_CTX_AFTER);}catch(e){}
          // JS source only: readable dumps contain the marker name; binary/network buffers won't.
          if(ctx==null||ctx.indexOf(mk)<0) continue;
          mhits++; found++;
          L('CLCS-PROBE hit@'+addr+' ctx='+JSON.stringify(ctx));
        }
      }catch(e){}
    }
  }
  if(found>0){ clcsProbeDone=true; L('CLCS-PROBE: '+found+' JS-source hit(s) on pass '+_clcsProbeN+' — design ProfileGate/MHU neuter from the d(e,t) builder'); return; }
  if(_clcsProbeN<240) setTimeout(clcsProbe, 250);   // ~60s, lighter cadence (no fastHH competing)
}

var tries=0;
function apply(){ if(hhYield()){ setTimeout(apply,500); return; } tries++; var rs=Process.enumerateRanges('rw-');
  var loaded=false,gp=pat('nrdp.gibbon');
  for(var i=0;i<rs.length&&!loaded;i++){if(rs[i].size>128*1024*1024)continue;try{if(Memory.scanSync(rs[i].base,rs[i].size,gp).length)loaded=true;}catch(e){}}
  if(loaded){patchA(rs);patchA2(rs);patchADV(rs);if(!advDone)patchADVw(rs);patchDAI(rs);patchMASTER(rs);if(!masterDone)patchMASTERw(rs);patchB(rs);patchFP(rs);patchGAID(rs);patchHH(rs);neuterMhuRenders(rs);patchCLCS(rs);}
  // Keep polling until applied. The ad-insertion source (ADV/DAI) and prepareAdBreakStates (A)
  // can load LATER than the pause module (B) — sometimes only once playback is exercised — so we
  // must NOT give up early. WRITE-ONCE per patch (done guards) — not a re-patch loop.
  var fpOk=(!FP_ENABLED||fpDone);
  var gaidOk=(!GAID_ENABLED||gaidDone);
  var hhOk=(!HH_ENABLED||hhDone);
  if((aDone||a2Done||masterDone)&&bDone&&fpOk&&gaidOk&&hhOk){ L('apply DONE: A='+aDone+' A2='+a2Done+' ADV='+advDone+' DAI='+daiDone+' MASTER='+masterDone+'(m1='+m1Done+',m2='+m2Done+') B='+bDone+' FP='+(FP_ENABLED?fpDone:'off')+' GAID='+(GAID_ENABLED?gaidDone:'off')+' HH='+(HH_ENABLED?hhDone:'off')+' tries='+tries); return; }
  if(tries%15===0) L('apply waiting: A='+aDone+' A2='+a2Done+' ADV='+advDone+' DAI='+daiDone+' MASTER='+masterDone+'(m1='+m1Done+',m2='+m2Done+') B='+bDone+' HH='+(HH_ENABLED?hhDone:'off')+' tries='+tries);
  if(tries<3600) setTimeout(apply, 2000);   // up to ~2h of find-and-apply polling
}

// ---------- monitor (read-only) ----------
var KILLMARK=pat('__adkill'),REALPOD=pat('ads":[{'),DISPAD=pat('displayAd":{'),BM=pat('"bookmark":');
function readNumAfter(addr,skip){ try{var s=addr.add(skip).readCString(14);var m=/^([0-9]{1,12})/.exec(s);return m?parseInt(m[1],10):-1;}catch(e){return -1;} }
var cyc=0;
function observe(){ if(hhYield()){ setTimeout(observe,1000); return; } cyc++;
  var rs=Process.enumerateRanges('rw-');var kill=0,real=0,disp=0;var bset={};
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>64*1024*1024||r.size<256)continue;
    try{ kill+=Memory.scanSync(r.base,r.size,KILLMARK).length; real+=Memory.scanSync(r.base,r.size,REALPOD).length; disp+=Memory.scanSync(r.base,r.size,DISPAD).length;
      var bh=Memory.scanSync(r.base,r.size,BM); for(var k=0;k<bh.length&&k<40;k++){var v=readNumAfter(bh[k].address,11);if(v>0)bset[v]=1;}
    }catch(e){}
  }
  var bks=Object.keys(bset).map(Number).sort(function(a,b){return b-a}).slice(0,6);
  var tag=(kill>1?'  <<<MANIFEST-KILL':'')+(disp>0?'  <<<server-pauseAd(x'+disp+')':'')+(real>0?'  <<<rawRealPod(x'+real+')':'');
  // Drift alarm: raw pods present but nothing killed -> report which anchors installed so we can tell
  // string-drift (MASTER=0: anchor scan-missed) from semantic-drift (MASTER=1: matched but ineffective).
  if(real>0 && kill===0){ tag+='  <<<DRIFT[A='+(aDone?1:0)+' A2='+(a2Done?1:0)+' ADV='+(advDone?1:0)+' DAI='+(daiDone?1:0)+' MASTER='+(masterDone?1:0)+(mwDone?'w':'')+']'; }
  L('OBS'+cyc+': KILLMARK='+kill+' rawRealPods='+real+' rawDisplayAd='+disp+' bookmarks='+JSON.stringify(bks)+tag);
  if(cyc<560) setTimeout(observe,3000);   // ~28 min coverage
}

L('killads ready (A/A2/ADV/DAI/MASTER ad-kill + B pause, single-shot; ad+resume monitor)');
setTimeout(apply,5000);
setTimeout(observe,9000);
// ANCHOR MAP (drift self-detection) — periodic at-a-glance hook state on its OWN timer, so the summary
// ALWAYS appears (apply()'s DONE line is gated on the whole loop completing and can be missed on some
// builds — reported on 1.36.0). Shows which anchors installed and whether MASTER/ADV used the wildcard
// fallback ('w'). ~8 snapshots over ~2.5 min covers the appboot settle window.
var _amN=0;
function anchorMap(){ _amN++;
  L('ANCHOR MAP: A='+(aDone?1:0)+' A2='+(a2Done?1:0)+' ADV='+(advDone?1:(advwDone?'w':0))+' DAI='+(daiDone?1:0)+' MASTER='+(masterDone?(mwDone?'1w':1):0)+' B='+(bDone?1:0)+' FP='+(FP_ENABLED?(fpDone?1:0):'off')+' GAID='+(GAID_ENABLED?(gaidDone?1:0):'off')+' HH='+(HH_ENABLED?(hhDone?1:0):'off')+' pass='+_amN);
  if(_amN<8) setTimeout(anchorMap,20000);
}
setTimeout(anchorMap,15000);
L('fastMASTER armed (getAdMetadata early race-win scanner — beat first-title pre/mid-roll)');
setTimeout(fastMASTER,200);
L('dumpMASTER armed (recon: dump getAdMetadata body if both exact+wildcard MASTER miss — #166)');
setTimeout(dumpMASTER,2000);
if(HH_ENABLED){ L('fastHH armed (household prompt suppression, early race-win scanner)'); setTimeout(fastHH,200);
}
if(CLCS_PROBE_ENABLED){ L('clcsProbe armed (DEV recon: locate runtime CLCS interstitial consumer — Nikflix seam)'); setTimeout(clcsProbe,200); }
if(HH_ENABLED){ L('fastCLCS armed (gate dispatcher race-win)'); setTimeout(fastCLCS,100); }

// LATE-ARM: don't attach until +40s. The hot eval callback isn't hooked during the heavy early boot
// (so no boot stall), but we're armed before the ~+60s route2/menu eval fires on play. We already have
// the appboot arg path (implicit_args_[0]); this run is to CATCH the late route2/dispatcher source eval.
