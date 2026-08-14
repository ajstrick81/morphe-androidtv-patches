'use strict';
// Netflix ad-kill (self-contained, single-shot each, NO re-patch loop) + read-only monitor.
//  (A)   manifest pre/mid-roll (legacy model): AdsState hydration -> empty metadata.ads=[]
//  (ADV) dynamic ad-insertion master kill: empty adverts.adBreaks at its single manifest source
//  (DAI) dynamic ad-insertion belt: applyDaiPrefetch returns content unchanged when ads present
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
var _fastHHn=0;
function fastHH(){ if(!HH_ENABLED||hhDone)return; _fastHHn++;
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

var tries=0;
function apply(){ tries++; var rs=Process.enumerateRanges('rw-');
  var loaded=false,gp=pat('nrdp.gibbon');
  for(var i=0;i<rs.length&&!loaded;i++){if(rs[i].size>128*1024*1024)continue;try{if(Memory.scanSync(rs[i].base,rs[i].size,gp).length)loaded=true;}catch(e){}}
  if(loaded){patchA(rs);patchADV(rs);patchDAI(rs);patchB(rs);patchFP(rs);patchGAID(rs);patchHH(rs);neuterMhuRenders(rs);}
  // Keep polling until applied. The ad-insertion source (ADV/DAI) and prepareAdBreakStates (A)
  // can load LATER than the pause module (B) — sometimes only once playback is exercised — so we
  // must NOT give up early. WRITE-ONCE per patch (done guards) — not a re-patch loop.
  var fpOk=(!FP_ENABLED||fpDone);
  var gaidOk=(!GAID_ENABLED||gaidDone);
  var hhOk=(!HH_ENABLED||hhDone);
  if(aDone&&bDone&&fpOk&&gaidOk&&hhOk){ L('apply DONE: A='+aDone+' ADV='+advDone+' DAI='+daiDone+' B='+bDone+' FP='+(FP_ENABLED?fpDone:'off')+' GAID='+(GAID_ENABLED?gaidDone:'off')+' HH='+(HH_ENABLED?hhDone:'off')+' tries='+tries); return; }
  if(tries%15===0) L('apply waiting: A='+aDone+' ADV='+advDone+' DAI='+daiDone+' B='+bDone+' HH='+(HH_ENABLED?hhDone:'off')+' tries='+tries);
  if(tries<3600) setTimeout(apply, 2000);   // up to ~2h of find-and-apply polling
}

// ---------- monitor (read-only) ----------
var KILLMARK=pat('__adkill'),REALPOD=pat('ads":[{'),DISPAD=pat('displayAd":{'),BM=pat('"bookmark":');
function readNumAfter(addr,skip){ try{var s=addr.add(skip).readCString(14);var m=/^([0-9]{1,12})/.exec(s);return m?parseInt(m[1],10):-1;}catch(e){return -1;} }
var cyc=0;
function observe(){ cyc++;
  var rs=Process.enumerateRanges('rw-');var kill=0,real=0,disp=0;var bset={};
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>64*1024*1024||r.size<256)continue;
    try{ kill+=Memory.scanSync(r.base,r.size,KILLMARK).length; real+=Memory.scanSync(r.base,r.size,REALPOD).length; disp+=Memory.scanSync(r.base,r.size,DISPAD).length;
      var bh=Memory.scanSync(r.base,r.size,BM); for(var k=0;k<bh.length&&k<40;k++){var v=readNumAfter(bh[k].address,11);if(v>0)bset[v]=1;}
    }catch(e){}
  }
  var bks=Object.keys(bset).map(Number).sort(function(a,b){return b-a}).slice(0,6);
  var tag=(kill>1?'  <<<MANIFEST-KILL':'')+(disp>0?'  <<<server-pauseAd(x'+disp+')':'')+(real>0?'  <<<rawRealPod(x'+real+')':'');
  L('OBS'+cyc+': KILLMARK='+kill+' rawRealPods='+real+' rawDisplayAd='+disp+' bookmarks='+JSON.stringify(bks)+tag);
  if(cyc<560) setTimeout(observe,3000);   // ~28 min coverage
}

L('killads ready (A/ADV/DAI ad-kill + B pause, single-shot; ad+resume monitor)');
setTimeout(apply,5000);
setTimeout(observe,9000);
if(HH_ENABLED){ L('fastHH armed (household prompt suppression, early race-win scanner)'); setTimeout(fastHH,200); }
