'use strict';
// BOTH kills, single-shot each (NO re-patch loop), + read-only data oracle.
//  (A) manifest pre/mid-roll: AdsState.prepareAdBreakStates -> stamp __adkill, empty metadata.ads=[]
//  (B) pause overlay: z() saga -> force f=e.displayAd to void 0 -> no ad opportunity
//  OBSERVE (read-only): KILLMARK(__adkill)/rawRealPods(ads":[{) prove manifest kills; rawDisplayAd
//  ("displayAd":{) = server sent a pause ad (our object/code patch leaves raw JSON intact -> oracle).
var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('KILL');
function L(m){ logw(6, TAG, Memory.allocUtf8String(m)); }
function pat(s){ return s.split('').map(function(c){return ('0'+c.charCodeAt(0).toString(16)).slice(-2);}).join(' '); }
function bytesOf(s){ var b=[]; for (var i=0;i<s.length;i++) b.push(s.charCodeAt(i)); return b; }

// ---------- (A) manifest patch ----------
var A_OLD = 'var f=e.value;f.syncAdStates();f.state.isHydrated&&"viewable"!==f.metadata.source&&f.applyHydration(f.state.hydrationSequenceId)';
var A_NEW = 'var f=e.value;f.metadata&&(f.metadata.ads.length&&(f.__adkill=f.metadata.ads.length),f.metadata.ads=[]);f.syncAdStates();/*...*/';
var aDone=false;
function patchA(rs){
  if(aDone) return;
  if(A_NEW.length!==A_OLD.length){ L('A length mismatch OLD='+A_OLD.length+' NEW='+A_NEW.length+' — ABORT A'); aDone=true; return; }
  var p=pat(A_OLD);
  for(var i=0;i<rs.length;i++){ var r=rs[i]; if(r.size>128*1024*1024)continue;
    try{ var h=Memory.scanSync(r.base,r.size,p);
      for(var j=0;j<h.length;j++){ Memory.protect(h[j].address,A_NEW.length,'rw-'); h[j].address.writeByteArray(bytesOf(A_NEW)); aDone=true; L('PATCH A: prepareAdBreakStates @'+h[j].address+' (metadata.ads=[]+__adkill stamp)'); } }catch(e){}
  }
}

// ---------- (B) pause patch ----------
var B_ANCHOR='void 0:e.displayAd', B_OLD='e.displayAd', B_NEW='void 0     '; // 11==11
var bDone=false;
function patchB(rs){
  if(bDone) return;
  var p=pat(B_ANCHOR);
  for(var i=0;i<rs.length;i++){ var r=rs[i]; if(r.size>128*1024*1024)continue;
    try{ var h=Memory.scanSync(r.base,r.size,p);
      for(var j=0;j<h.length;j++){ var t=h[j].address.add('void 0:'.length);
        var cur=null; try{cur=t.readCString(B_OLD.length);}catch(e){}
        if(cur!==B_OLD) continue;
        Memory.protect(t,B_NEW.length,'rw-'); t.writeByteArray(bytesOf(B_NEW)); bDone=true;
        L('PATCH B: pause z() displayAd->void0 @'+t); } }catch(e){}
  }
}

// ---------- apply (poll until BOTH applied, then stop) ----------
var tries=0;
function apply(){
  tries++;
  var rs=Process.enumerateRanges('rw-');
  var loaded=false, gp=pat('nrdp.gibbon');
  for(var i=0;i<rs.length && !loaded;i++){ if(rs[i].size>128*1024*1024)continue; try{ if(Memory.scanSync(rs[i].base,rs[i].size,gp).length) loaded=true; }catch(e){} }
  if(loaded){ patchA(rs); patchB(rs); }
  if((aDone&&bDone)||tries>90){ L('apply stop: A='+aDone+' B='+bDone+' tries='+tries); return; }
  setTimeout(apply, 2000);
}

// ---------- observe (read-only) ----------
var KILLMARK=pat('__adkill'), REALPOD=pat('ads":[{'), EMPTYPOD=pat('ads":[]'), DISPAD=pat('displayAd":{');
var cyc=0;
function observe(){
  cyc++;
  var rs=Process.enumerateRanges('rw-'); var kill=0,real=0,empty=0,disp=0;
  for(var i=0;i<rs.length;i++){ var r=rs[i]; if(r.size>64*1024*1024||r.size<256)continue;
    try{ kill+=Memory.scanSync(r.base,r.size,KILLMARK).length; real+=Memory.scanSync(r.base,r.size,REALPOD).length;
      empty+=Memory.scanSync(r.base,r.size,EMPTYPOD).length; disp+=Memory.scanSync(r.base,r.size,DISPAD).length; }catch(e){}
  }
  var tag=kill>0?'  <<< PROOF manifest: emptied '+kill+' real-ad break(s)':'';
  var ptag=disp>0?'  <<< server sent pause displayAd (x'+disp+')':'';
  L('OBS'+cyc+': KILLMARK='+kill+' rawRealPods='+real+' rawEmptyPods='+empty+' rawDisplayAd='+disp+tag+ptag);
  if(cyc<160) setTimeout(observe, 2500);
}

L('combined-kill ready (A manifest + B pause, single-shot; read-only oracle)');
setTimeout(apply, 5000);
setTimeout(observe, 8000);
