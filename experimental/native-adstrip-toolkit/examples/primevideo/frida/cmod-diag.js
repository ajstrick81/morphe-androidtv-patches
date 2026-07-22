'use strict';
// DETECT-ONLY diagnostic (NO writes -> safe for playback). For each memcpy of the
// real intraTitlePlaylist, dump: buffer size n, marker offset, and for each array
// element its type (Main/Remote) + span + whether it is COMPLETE (closing brace
// present) or TRUNCATED at the buffer end. This tells us if chunk truncation is
// why in-place blanking corrupted playback.

var mod = Process.getModuleByName('libignite.so');
var igLo = mod.base, igHi = mod.base.add(mod.size);
var libc = Process.getModuleByName('libc.so');
console.log('[diag] libignite '+igLo+'..'+igHi);

function resolve(a){ if(a.compare(igLo)>=0&&a.compare(igHi)<0) return 'libignite+0x'+a.sub(igLo).toString(16);
  var m=Process.findModuleByAddress(a); return m?(m.name+'+0x'+a.sub(m.base).toString(16)):a.toString(); }
function toLatin1(ptr,n){ var u8=new Uint8Array(ptr.readByteArray(n)),s=''; for(var i=0;i<u8.length;i++){var c=u8[i];s+=(c>=32&&c<127)?String.fromCharCode(c):'.';} return s; }
function ws(s,i){ while(i<s.length&&' \t\n\r'.indexOf(s[i])>=0)i++; return i; }
function scanVal(s,i){ var c=s[i];
  if(c==='"'){ i++; while(i<s.length){ if(s[i]==='\\'){i+=2;continue;} if(s[i]==='"')return i+1; i++; } return s.length; }
  if(c==='{'||c==='['){ var open=c,close=(c==='{')?'}':']',d=0; while(i<s.length){ var e=s[i];
      if(e==='"'){ i=scanVal(s,i); continue; } if(e===open){d++;i++;} else if(e===close){d--;i++; if(d===0)return i;} else i++; } return -1; /* truncated */ }
  while(i<s.length&&',}]'.indexOf(s[i])<0&&' \t\n\r'.indexOf(s[i])<0)i++; return i; }

var reported={}, n_reports=0;
var onMarker = new NativeCallback(function(retAddr, tag, n, off, src){
  var caller=resolve(retAddr);
  var s; try{ s=toLatin1(src, n); }catch(e){ return; }
  var lb = off+20;
  var arrayClosed = false, i = ws(s, lb+1), idx=0, remote=0, out=[];
  while(i<s.length && s[i]!==']'){
    var es=i, ee=scanVal(s,i);
    if(ee<0){ out.push('  ['+idx+'] TRUNCATED from +'+(es-lb)+' (no closing brace in buffer)'); break; }
    var txt=s.substring(es,ee);
    var type = /"type"\s*:\s*"([A-Za-z]+)"/.exec(txt);
    var isR = /"type"\s*:\s*"Remote"/.test(txt); if(isR) remote++;
    out.push('  ['+idx+'] '+(type?type[1]:'?')+'  span +'+(es-lb)+'..+'+(ee-lb)+' (len '+(ee-es)+')');
    idx++; i=ws(s,ee); if(s[i]===','){ i=ws(s,i+1); } else if(s[i]===']'){ arrayClosed=true; break; }
  }
  if(i<s.length && s[i]===']') arrayClosed=true;
  // dedup by structural signature so new titles/shapes always report
  var sig=caller+':'+idx+':'+remote+':'+arrayClosed;
  if(reported[sig]) return; reported[sig]=1; n_reports++;
  console.log('\n[DIAG] caller='+caller+' n='+n+' elems='+idx+' Remote='+remote+' arrayClosed='+arrayClosed+' (bufEndsAt +'+(n-lb)+' from array start)');
  out.forEach(function(l){ console.log(l); });
  // tail preview to see what's right after where the buffer ends / array closes
  console.log('   arrayHead: '+JSON.stringify(s.substring(lb, lb+70)));
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
  if (n < 512 || n > 65536) return;
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
console.log('[diag] armed (DETECT ONLY, no writes). Cold-start a title.');
setInterval(function(){ console.log('[hb] reports='+n_reports); }, 4000);
