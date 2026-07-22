'use strict';
// One-shot, NON-deduped diagnostic. Scan every rw- range once for
// intraTitlePlaylist, report element/Remote counts for each, and count ranges
// that fail to scan. Answers: is the Remote-carrying buffer resident in heap
// during playback, or does polling just miss it?

var NEEDLE = 'intraTitlePlaylist';

function toLatin1(bytes){
  var u8=new Uint8Array(bytes), s='';
  for(var i=0;i<u8.length;i++){ var c=u8[i]; s+=(c>=32&&c<127)?String.fromCharCode(c):'.'; }
  return s;
}
function ws(s,i){ while(i<s.length && ' \t\n\r'.indexOf(s[i])>=0) i++; return i; }
function scanVal(s,i){
  var c=s[i];
  if(c==='"'){ i++; while(i<s.length){ if(s[i]==='\\'){i+=2;continue;} if(s[i]==='"')return i+1; i++; } return s.length; }
  if(c==='{'||c==='['){ var open=c,close=(c==='{')?'}':']',d=0;
    while(i<s.length){ var e=s[i];
      if(e==='"'){ i=scanVal(s,i); continue; }
      if(e===open){d++;i++;} else if(e===close){d--;i++; if(d===0) return i;} else i++;
    } return s.length; }
  while(i<s.length && ',}]'.indexOf(s[i])<0 && ' \t\n\r'.indexOf(s[i])<0) i++;
  return i;
}
function countElems(s, lb){
  var i=ws(s,lb+1), n=0, remote=0;
  while(i<s.length && s[i]!==']'){
    var ee=scanVal(s,i); var text=s.substring(i,ee);
    n++; if(/"type"\s*:\s*"Remote"/.test(text)) remote++;
    i=ws(s,ee); if(s[i]===',') i=ws(s,i+1);
  }
  return {n:n, remote:remote};
}
function hexPat(needle){ var p=''; for(var i=0;i<needle.length;i++){ var h=needle.charCodeAt(i).toString(16); p+=(h.length<2?'0'+h:h)+' '; } return p.trim(); }

var ranges = Process.enumerateRanges('rw-');
var pat = hexPat(NEEDLE);
var totalFound=0, scanErrors=0, parsed=0, withRemote=0, maxElems=0;
console.log('[once] scanning ' + ranges.length + ' rw- ranges for ' + NEEDLE);
for(var r=0;r<ranges.length;r++){
  var found;
  try{ found = Memory.scanSync(ranges[r].base, ranges[r].size, pat); }
  catch(e){ scanErrors++; continue; }
  for(var f=0; f<found.length; f++){
    totalFound++;
    var addr = found[f].address;
    var bytes; try{ bytes = addr.add(-1).readByteArray(120000); }catch(e){ continue; }
    // re-anchor: our scan matched the bare needle; look for the quoted array start near it
    var s; try{ s = toLatin1(bytes); }catch(e){ continue; }
    var key = s.indexOf('"'+NEEDLE+'"');
    if(key<0){ continue; }
    var colon = s.indexOf(':', key+NEEDLE.length+2);
    var lb = s.indexOf('[', colon);
    if(lb<0 || lb-colon>10) continue;
    parsed++;
    var c = countElems(s, lb);
    if(c.n>maxElems) maxElems=c.n;
    if(c.remote>0){ withRemote++;
      console.log('  [RESIDENT Remote buffer] @ '+addr+'  elements='+c.n+'  Remote='+c.remote);
    } else if(c.n>1){
      console.log('  [multi-elem, no Remote] @ '+addr+'  elements='+c.n);
    }
  }
}
console.log('[once] DONE. rawMatches='+totalFound+' scanErrors='+scanErrors+
            ' parsedArrays='+parsed+' withRemote='+withRemote+' maxElems='+maxElems);
