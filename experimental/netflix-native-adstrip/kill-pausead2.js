'use strict';
// Kill Netflix pause ads at the DATA chokepoint (single-shot, pre-pause, no loop).
// z() saga: f = getDataFragment(c)?.displayAd; if(f){build ad} else return undefined -> saga bails.
// Force f falsy by turning the ternary value `e.displayAd` into `void 0`:
//   ...?void 0:e.displayAd)...  ->  ...?void 0:void 0     )...   (length-preserving)
var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('PA2');
function LG(m){ logw(6, TAG, Memory.allocUtf8String(m)); }
function pat(s){ return s.split('').map(function(c){return ('0'+c.charCodeAt(0).toString(16)).slice(-2);}).join(' '); }
function bytesOf(s){ var b=[]; for (var i=0;i<s.length;i++) b.push(s.charCodeAt(i)); return b; }

var ANCHOR = 'void 0:e.displayAd';        // unique; the ternary no-ad?void0 : e.displayAd
var OLD = 'e.displayAd';                   // 11 bytes
var NEW = 'void 0     ';                    // 11 bytes (void 0 + 5 spaces) -> always undefined
var applied = false;

function tryApply() {
  if (applied) return true;
  var rs = Process.enumerateRanges('rw-'), p = pat(ANCHOR), n = 0;
  for (var i = 0; i < rs.length; i++) {
    var hits; try { hits = Memory.scanSync(rs[i].base, rs[i].size, p); } catch (e) { continue; }
    for (var j = 0; j < hits.length; j++) {
      var tern = hits[j].address;                 // points at "void 0:e.displayAd"
      var target = tern.add('void 0:'.length);    // points at "e.displayAd"
      // verify
      var cur = null; try { cur = target.readCString(OLD.length); } catch (e) {}
      if (cur !== OLD) { LG('anchor found but bytes mismatch: ' + JSON.stringify(cur)); continue; }
      try {
        Memory.protect(target, NEW.length, 'rw-');
        target.writeByteArray(bytesOf(NEW));
        applied = true; n++;
        LG('PATCHED displayAd->void0 @ ' + target + ' now="' + tern.readCString(18) + '"');
      } catch (e) { LG('write err ' + e); }
    }
  }
  return applied;
}

LG('kill-pausead2 ready (single-shot)');
// poll ONLY until applied once, then stop (no persistent re-patch loop)
var tries = 0;
var t = setInterval(function () {
  tries++;
  if (tryApply() || tries > 60) { clearInterval(t); LG('done: applied=' + applied + ' after ' + tries + ' polls'); }
}, 1000);
rpc.exports = { apply: tryApply };
