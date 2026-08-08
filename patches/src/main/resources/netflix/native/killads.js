'use strict';
// Netflix ad-kill (self-contained, single-shot each, NO re-patch loop) + read-only monitor.
//  (A) manifest pre/mid-roll: AdsState.prepareAdBreakStates -> stamp __adkill, empty metadata.ads=[]
//  (B) pause overlay: z() saga -> force f=e.displayAd to void 0 -> no ad opportunity
//  MONITOR (read-only): KILLMARK(__adkill)/rawRealPods = manifest ad kills; rawDisplayAd = server
//  pause ad delivered; BOOKMARK = distinct resume positions seen (confirms resume/bookmark intact).
var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('KILL');
function L(m){ logw(6, TAG, Memory.allocUtf8String(m)); }
function pat(s){ return s.split('').map(function(c){return ('0'+c.charCodeAt(0).toString(16)).slice(-2);}).join(' '); }
function bytesOf(s){ var b=[]; for (var i=0;i<s.length;i++) b.push(s.charCodeAt(i)); return b; }

// ---------- (A) manifest patch ----------
var A_OLD='var f=e.value;f.syncAdStates();f.state.isHydrated&&"viewable"!==f.metadata.source&&f.applyHydration(f.state.hydrationSequenceId)';
var A_NEW='var f=e.value;f.metadata&&(f.metadata.ads.length&&(f.__adkill=f.metadata.ads.length),f.metadata.ads=[]);f.syncAdStates();/*...*/';
var aDone=false;
function patchA(rs){ if(aDone)return; if(A_NEW.length!==A_OLD.length){L('A length mismatch — ABORT');aDone=true;return;}
  var p=pat(A_OLD);
  for(var i=0;i<rs.length;i++){var r=rs[i];if(r.size>128*1024*1024)continue;
    try{var h=Memory.scanSync(r.base,r.size,p);for(var j=0;j<h.length;j++){Memory.protect(h[j].address,A_NEW.length,'rw-');h[j].address.writeByteArray(bytesOf(A_NEW));aDone=true;L('PATCH A: prepareAdBreakStates @'+h[j].address);}}catch(e){}}
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
var tries=0;
function apply(){ tries++; var rs=Process.enumerateRanges('rw-');
  var loaded=false,gp=pat('nrdp.gibbon');
  for(var i=0;i<rs.length&&!loaded;i++){if(rs[i].size>128*1024*1024)continue;try{if(Memory.scanSync(rs[i].base,rs[i].size,gp).length)loaded=true;}catch(e){}}
  if(loaded){patchA(rs);patchB(rs);patchFP(rs);patchGAID(rs);}
  // Keep polling until applied. prepareAdBreakStates (A) can load LATER than the
  // pause module (B) — sometimes only once the ad code is exercised — so we must
  // NOT give up early or a real ad slips through. WRITE-ONCE (aDone/bDone/fpDone
  // guards) — not a re-patch loop. FP is only required when enabled.
  var fpOk=(!FP_ENABLED||fpDone);
  var gaidOk=(!GAID_ENABLED||gaidDone);
  if(aDone&&bDone&&fpOk&&gaidOk){ L('apply DONE: A='+aDone+' B='+bDone+' FP='+(FP_ENABLED?fpDone:'off')+' GAID='+(GAID_ENABLED?gaidDone:'off')+' tries='+tries); return; }
  if(tries%15===0) L('apply waiting: A='+aDone+' B='+bDone+' FP='+(FP_ENABLED?fpDone:'off')+' GAID='+(GAID_ENABLED?gaidDone:'off')+' tries='+tries);
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

L('killads ready (A manifest + B pause, single-shot; ad+resume monitor)');
setTimeout(apply,5000);
setTimeout(observe,9000);
