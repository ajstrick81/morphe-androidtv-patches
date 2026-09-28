'use strict';
// CModule memcpy/memmove hook: NATIVE inline size-gate + marker scan, so the hot
// path (memcpy ~30k/s) stays fast and never starves the JS thread. Only on a real
// marker match does C call back into JS (rare) to resolve the return address ->
// names the libignite function moving the decrypted PRS plaintext = pre-parse seam.

var mod = Process.getModuleByName('libignite.so');
var igLo = mod.base, igHi = mod.base.add(mod.size);
var libc = Process.getModuleByName('libc.so');
console.log('[cmod] libignite '+igLo+'..'+igHi);

function resolve(a){
  if(a.compare(igLo)>=0 && a.compare(igHi)<0) return 'libignite+0x'+a.sub(igLo).toString(16);
  var m=Process.findModuleByAddress(a); return m?(m.name+'+0x'+a.sub(m.base).toString(16)):a.toString();
}

var seen={}, hits=0;
var onMarker = new NativeCallback(function(retAddr, tag, n, off, src){
  var k = resolve(retAddr);
  seen[k]=(seen[k]||0)+1;
  if(seen[k]<=3){
    hits++;
    var t = tag.readCString();
    console.log('\n[CMOD-HIT] '+t+' n='+n+' markerOff='+off+'  caller='+k+'  (raw ret='+retAddr+')');
    // preview around the marker
    try{ var pv=src.add(off).readByteArray(90); var u=new Uint8Array(pv),s='';
      for(var i=0;i<u.length;i++){var c=u[i];s+=(c>=32&&c<127)?String.fromCharCode(c):'.';}
      console.log('   preview: '+s); }catch(e){}
  }
}, 'void', ['pointer','pointer','size_t','size_t','pointer']);

var cm = new CModule(`
#include <gum/guminterceptor.h>
#include <glib.h>

extern void on_marker (gpointer ret_addr, const char * tag, gsize n, gsize off, gconstpointer src);

/* marker = intraTitlePlaylist then quote-colon-bracket (21 bytes) -- the REAL JSON
   field + array open. Discriminates the runtime response from libignite static
   string pools, whose copies read 'intraTitlePlaylist length' etc, never that. */
static gboolean
find_marker (const guint8 * p, gsize cap, gsize * out_off)
{
  static const char M[18] = {'i','n','t','r','a','T','i','t','l','e','P','l','a','y','l','i','s','t'};
  gsize i;
  for (i = 0; i + 21 <= cap; i++) {
    if (p[i] == 'i' && p[i+1] == 'n' && p[i+5] == 'T') {
      gsize k; gboolean ok = TRUE;
      for (k = 2; k < 18; k++) { if (p[i+k] != (guint8)M[k]) { ok = FALSE; break; } }
      if (ok && p[i+18]=='"' && p[i+19]==':' && p[i+20]=='[') { *out_off = i; return TRUE; }
    }
  }
  return FALSE;
}

static const char TAG_CPY[] = "memcpy";
static const char TAG_MOV[] = "memmove";

static void
scan_arg (GumInvocationContext * ic, const char * tag)
{
  gsize n = (gsize) gum_invocation_context_get_nth_argument (ic, 2);
  if (n < 512 || n > 65536) return;   /* skip huge video-frame copies -> no starvation */
  const guint8 * src = (const guint8 *) gum_invocation_context_get_nth_argument (ic, 1);
  if (src == NULL) return;
  gsize cap = n;
  gsize off;
  if (find_marker (src, cap, &off)) {
    gpointer ret = gum_invocation_context_get_return_address (ic);
    on_marker (ret, tag, n, off, src);
  }
}

void on_enter_memcpy (GumInvocationContext * ic) { scan_arg (ic, TAG_CPY); }
void on_enter_memmove (GumInvocationContext * ic) { scan_arg (ic, TAG_MOV); }
`, { on_marker: onMarker });

Interceptor.attach(libc.getExportByName('memcpy'),  { onEnter: cm.on_enter_memcpy });
Interceptor.attach(libc.getExportByName('memmove'), { onEnter: cm.on_enter_memmove });
console.log('[cmod] hooked memcpy + memmove (native filter). Cold-start a title.');

setInterval(function(){ console.log('[hb] cmodHits='+hits+' sites='+Object.keys(seen).length); }, 3000);
