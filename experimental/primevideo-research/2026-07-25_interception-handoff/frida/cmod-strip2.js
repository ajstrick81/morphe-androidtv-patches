'use strict';
// IN-PROCESS PRS STRIP v2 (SAFE). Only blanks when the intraTitlePlaylist array is
// COMPLETE in the buffer (closing ']' present) and every element parses cleanly
// (no truncation). This targets the full response copy (e.g. libc.so+0x3ea01,
// n~40KB, arrayClosed) and SKIPS the truncated libignite chunk-copies that
// corrupted playback in v1. Same-length blank of {"type":"Remote",...}+comma.

var MODE = 'strip';                 // 'detect' | 'strip'
var mod = Process.getModuleByName('libignite.so');
var igLo = mod.base, igHi = mod.base.add(mod.size);
var libc = Process.getModuleByName('libc.so');
console.log('[strip2] libignite '+igLo+'..'+igHi+'  MODE='+MODE);

function resolve(a){ if(a.compare(igLo)>=0&&a.compare(igHi)<0) return 'libignite+0x'+a.sub(igLo).toString(16);
  var m=Process.findModuleByAddress(a); return m?(m.name+'+0x'+a.sub(m.base).toString(16)):a.toString(); }
function toLatin1(ptr,n){ var u8=new Uint8Array(ptr.readByteArray(n)),s=''; for(var i=0;i<u8.length;i++){var c=u8[i];s+=(c>=32&&c<127)?String.fromCharCode(c):'.';} return s; }
function ws(s,i){ while(i<s.length&&' \t\n\r'.indexOf(s[i])>=0)i++; return i; }
// returns end index, or -1 if the value is TRUNCATED (no closing) within s.
function scanVal(s,i){ var c=s[i];
  if(c==='"'){ i++; while(i<s.length){ if(s[i]==='\\'){i+=2;continue;} if(s[i]==='"')return i+1; i++; } return -1; }
  if(c==='{'||c==='['){ var open=c,close=(c==='{')?'}':']',d=0; while(i<s.length){ var e=s[i];
      if(e==='"'){ var j=scanVal(s,i); if(j<0)return -1; i=j; continue; } if(e===open){d++;i++;} else if(e===close){d--;i++; if(d===0)return i;} else i++; } return -1; }
  while(i<s.length&&',}]'.indexOf(s[i])<0&&' \t\n\r'.indexOf(s[i])<0)i++; return i; }

// Parse the whole array. Returns null if truncated/malformed (=> DO NOT TOUCH).
function parseComplete(s, lb){
  var elems=[], i=ws(s,lb+1);
  if(s[lb]!=='[') return null;
  while(i<s.length){
    if(s[i]===']') return {elems:elems, rb:i};
    var es=i, ee=scanVal(s,i);
    if(ee<0) return null;                          // truncated element -> bail, unsafe
    var el={start:es,end:ee,isRemote:/"type"\s*:\s*"Remote"/.test(s.substring(es,ee))};
    elems.push(el); i=ws(s,ee);
    if(s[i]===','){ el.commaAfter=i; i=ws(s,i+1); }
    else if(s[i]===']') return {elems:elems, rb:i};
    else return null;                              // unexpected -> unsafe
  }
  return null;                                     // ran off end without ']' -> truncated
}
function blankRanges(elems){ var out=[];
  for(var k=0;k<elems.length;k++){ var el=elems[k]; if(!el.isRemote) continue;
    if(el.commaAfter!==undefined) out.push([el.start,el.commaAfter+1]);
    else { var pc=(k>0)?elems[k-1].commaAfter:undefined; out.push(pc!==undefined?[pc,el.end]:[el.start,el.end]); } }
  return out; }

var seen={}, strips=0, remoteTotal=0, skippedTrunc=0;
var onMarker = new NativeCallback(function(retAddr, tag, n, off, src){
  var caller=resolve(retAddr);
  var s; try{ s=toLatin1(src, n); }catch(e){ return; }
  var lb = off+20;
  if(s[lb]!=='[') return;
  var pr = parseComplete(s, lb);
  if(pr===null){ skippedTrunc++; return; }         // truncated/partial -> NEVER blank (safety)
  var remote = pr.elems.filter(function(e){return e.isRemote;}).length;
  if(remote===0) return;
  var key=caller+':'+pr.elems.length+':'+remote; seen[key]=(seen[key]||0)+1;
  if(seen[key]<=3) console.log('\n[STRIP2] caller='+caller+' n='+n+' elems='+pr.elems.length+' Remote='+remote+' COMPLETE'+(MODE==='strip'?' -> blanking':' -> would blank'));
  if(MODE!=='strip') return;
  var ranges=blankRanges(pr.elems), verified=0;
  ranges.forEach(function(r){ var len=r[1]-r[0]; if(len<=0)return; var buf=[]; for(var i=0;i<len;i++)buf.push(0x20);
    try{ src.add(r[0]).writeByteArray(buf); }catch(e){ return; }
    var chk=toLatin1(src.add(r[0]),len); if(/^ +$/.test(chk)) verified++; });
  strips++; remoteTotal+=remote;
  if(seen[key]<=3) console.log('   blanked '+ranges.length+' range(s) verified='+verified+' (safe: complete array only)');
}, 'void', ['pointer','pointer','size_t','size_t','pointer']);

var cm = new CModule(`
#include <gum/guminterceptor.h>
#include <glib.h>
extern void on_marker (gpointer ret_addr, const char * tag, gsize n, gsize off, gconstpointer src);
static gboolean find_marker (const guint8 * p, gsize cap, gsize * out_off) {
  static const char M[18] = {'i','n','t','r','a','T','i','t','l','e','P','l','a','y','l','i','s','t'};
  gsize i;
  for (i = 0; i + 21 <= cap; i++) {
    if (p[i]=='i'&&p[i+1]=='n'&&p[i+5]=='T') {
      gsize k; gboolean ok=TRUE;
      for(k=2;k<18;k++){ if(p[i+k]!=(guint8)M[k]){ ok=FALSE; break; } }
      if(ok&&p[i+18]=='"'&&p[i+19]==':'&&p[i+20]=='['){ *out_off=i; return TRUE; }
    }
  }
  return FALSE;
}
static const char T[] = "cpy";
static void scan_arg (GumInvocationContext * ic) {
  gsize n = (gsize) gum_invocation_context_get_nth_argument (ic, 2);
  if (n < 512 || n > 262144) return;
  const guint8 * src = (const guint8 *) gum_invocation_context_get_nth_argument (ic, 1);
  if (src == NULL) return;
  gsize off;
  if (find_marker (src, n, &off)) on_marker (gum_invocation_context_get_return_address(ic), T, n, off, src);
}
void on_enter_memcpy (GumInvocationContext * ic) { scan_arg (ic); }
void on_enter_memmove (GumInvocationContext * ic) { scan_arg (ic); }
`, { on_marker: onMarker });
Interceptor.attach(libc.getExportByName('memcpy'),  { onEnter: cm.on_enter_memcpy });
Interceptor.attach(libc.getExportByName('memmove'), { onEnter: cm.on_enter_memmove });
console.log('[strip2] hooked memcpy+memmove (blank COMPLETE arrays only). Cold-start a title.');
setInterval(function(){ console.log('[hb] strips='+strips+' RemoteBlanked='+remoteTotal+' skippedTruncated='+skippedTrunc+' sites='+Object.keys(seen).length); }, 3000);
