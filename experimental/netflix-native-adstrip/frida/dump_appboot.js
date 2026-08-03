/*
 * dump_appboot.js — offset-free acquisition of the Netflix appboot UI bundle
 * ---------------------------------------------------------------------------
 * Goal: capture the appboot player JS (where the ad-break logic + its own
 *       empty-break branch live) as it exists AFTER MSL-decrypt and AFTER the
 *       load-time appboot signature check — i.e. plaintext in-process.
 *
 * Design constraint: libnetflix.so (soname libandroid_netflix.so, ~84 MB) is
 *   stripped and statically links OpenSSL + Hermes + Gibbon, so symbol-based
 *   hooks are unreliable. This script therefore uses only:
 *     (1) libc file I/O taps  — catch the on-disk cache read/write of appboot
 *     (2) generic heap scan   — sweep RW memory for appboot/ad-break markers
 *     (3) module/export recon — enumerate what IS hookable for a precise v2
 *   None of these need a Netflix offset. Works under frida-gadget (no root).
 *
 * It also classifies every dump as PLAIN JS vs HERMES BYTECODE (HBC), which
 * answers the load-bearing seam-B question: can we string-edit the ad-break
 * resolver in the heap (plain JS) or must we operate on the manifest data only
 * (seam A) because the code ships as HBC?
 *
 * Run (gadget/listen mode, non-root): the app loads libgadget.so via the
 *   toolkit's LoadNativeHookPatch; this script is the gadget's `path` config.
 * Run (interactive, if you have a host): frida -U -n com.netflix.ninja -l dump_appboot.js
 *
 * Output: files written to OUT_DIR (adb-pullable without root). See README.md.
 */

'use strict';

// ---- config ---------------------------------------------------------------
// App-specific external dir: app can always write here w/o a permission, and
// `adb pull` can read it without root. If your repackage set debuggable=true,
// you can instead point this at the internal files dir and use `run-as`.
var OUT_DIR = '/sdcard/Android/data/com.netflix.ninja/files/nfdump';
var PATH_HINTS = ['appboot', 'gibbon', 'milo', 'nrdp', 'nrd/', 'netflix', 'DiskCache', 'storage'];
var JS_MARKERS = ['gibbon.load', 'appboot', 'adBreak', 'interstitial', 'cuePoint',
                  'nrdp', 'function(', 'milo', 'MslEncoder'];
var HEAP_SWEEP_DELAY_MS = 20000;   // appboot is fetched/cached shortly after boot
var HEAP_REGION_PAD     = 0x200000; // dump ±2 MB around a marker hit
var MIN_DUMP_BYTES      = 4096;     // ignore tiny incidental buffers
var HBC_MAGIC = [0x1f, 0x1b, 0xc0, 0x9f]; // Hermes bytecode file magic (0x1F1BC09F)

// ---- tiny util ------------------------------------------------------------
var _seq = 0;
function ts() { return new Date().toISOString().replace(/[:.]/g, '-'); }
function log(m) { console.log('[appboot-dump] ' + m); }

function ensureDir(d) {
  try { new File(d + '/.keep', 'w').close(); } catch (e) {
    // File() can't mkdir -p; fall back to a syscall via libc mkdir chain
    try {
      var mkdir = new NativeFunction(Module.getExportByName('libc.so', 'mkdir'),
                                     'int', ['pointer', 'int']);
      var parts = d.split('/'); var cur = '';
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue; cur += '/' + parts[i];
        mkdir(Memory.allocUtf8String(cur), 0x1ff /*0777*/);
      }
    } catch (e2) { log('mkdir fallback failed: ' + e2); }
  }
}

function classify(bytes) {
  if (bytes.length >= 4 &&
      bytes[0] === HBC_MAGIC[0] && bytes[1] === HBC_MAGIC[1] &&
      bytes[2] === HBC_MAGIC[2] && bytes[3] === HBC_MAGIC[3]) return 'HBC';
  // sniff for readable JS: sample first 2 KB for high ASCII-printable ratio
  var n = Math.min(bytes.length, 2048), printable = 0;
  for (var i = 0; i < n; i++) {
    var c = bytes[i];
    if (c === 9 || c === 10 || c === 13 || (c >= 32 && c < 127)) printable++;
  }
  return (printable / n > 0.85) ? 'JS' : 'BIN';
}

function markerHit(bytes) {
  // cheap ASCII search over the head of the buffer
  var head = '';
  var n = Math.min(bytes.length, 65536);
  for (var i = 0; i < n; i++) { var c = bytes[i]; head += (c >= 32 && c < 127) ? String.fromCharCode(c) : ' '; }
  for (var j = 0; j < JS_MARKERS.length; j++) if (head.indexOf(JS_MARKERS[j]) !== -1) return JS_MARKERS[j];
  return null;
}

function dump(bytes, tag, extra) {
  if (bytes.length < MIN_DUMP_BYTES) return;
  var kind = classify(bytes);
  var hit  = markerHit(bytes);
  if (kind === 'BIN' && !hit) return;        // not appboot-ish, skip noise
  var name = OUT_DIR + '/' + (_seq++) + '_' + tag + '_' + kind +
             (hit ? '_' + hit.replace(/[^a-z0-9]/gi, '') : '') + '_' + ts() +
             (kind === 'JS' ? '.js' : '.bin');
  try {
    var f = new File(name, 'wb');
    f.write(bytes.buffer ? bytes.buffer : bytes);
    f.close();
    log('DUMPED ' + name + '  (' + bytes.length + ' bytes, ' + kind +
        (hit ? ', marker="' + hit + '"' : '') + (extra ? ', ' + extra : '') + ')');
  } catch (e) { log('write failed (' + name + '): ' + e); }
}

// ---- (3) recon: what is actually hookable in the monolith -----------------
function recon() {
  Process.enumerateModules().forEach(function (m) {
    if (/netflix|hermes|gibbon|cronet|ssl|crypto/i.test(m.name))
      log('module ' + m.name + ' @ ' + m.base + ' size=' + m.size);
  });
  var mono = Process.findModuleByName('libnetflix.so') ||
             Process.findModuleByName('libandroid_netflix.so');
  if (!mono) { log('monolith not loaded yet at recon time'); return; }
  var interesting = /appboot|gibbon|hermes|evaluate|runBytecode|SSL_read|milo|manifest|adbreak/i;
  var exps = mono.enumerateExports().filter(function (e) { return interesting.test(e.name); });
  log('monolith ' + mono.name + ' exports matching seam terms: ' + exps.length);
  exps.slice(0, 60).forEach(function (e) { log('  export ' + e.type + ' ' + e.name + ' @ ' + e.address); });
}

// ---- (1) libc file-I/O taps: catch the disk-cache read/write of appboot ---
var fdPath = {};   // fd -> path, only for netflix-ish files
function pathIsInteresting(p) {
  if (!p) return false;
  var lp = p.toLowerCase();
  for (var i = 0; i < PATH_HINTS.length; i++) if (lp.indexOf(PATH_HINTS[i]) !== -1) return true;
  return lp.indexOf('com.netflix.ninja') !== -1;
}

function hookOpen(sym) {
  var addr = Module.findExportByName('libc.so', sym);
  if (!addr) return;
  Interceptor.attach(addr, {
    onEnter: function (a) { this.p = (sym === 'openat') ? a[1].readUtf8String() : a[0].readUtf8String(); },
    onLeave: function (r) {
      var fd = r.toInt32();
      if (fd >= 0 && pathIsInteresting(this.p)) { fdPath[fd] = this.p; log('open ' + fd + ' <- ' + this.p); }
    }
  });
}

function hookIO(sym) {
  var addr = Module.findExportByName('libc.so', sym);
  if (!addr) return;
  Interceptor.attach(addr, {
    onEnter: function (a) { this.fd = a[0].toInt32(); this.buf = a[1]; this.len = a[2].toInt32(); },
    onLeave: function (r) {
      if (!(this.fd in fdPath)) return;
      var n = (sym === 'write' || sym === 'pwrite64' || sym === 'pwrite') ? this.len : r.toInt32();
      if (n <= MIN_DUMP_BYTES) return;
      try { dump(new Uint8Array(this.buf.readByteArray(n)), 'io_' + sym + '_fd' + this.fd, fdPath[this.fd]); }
      catch (e) {}
    }
  });
}

// ---- (2) generic heap sweep for appboot markers ---------------------------
function heapSweep() {
  log('heap sweep starting…');
  var pattern = null;
  // scan for the most appboot-specific literal we expect in the bundle
  var needle = 'gibbon.load';
  Process.enumerateRanges('rw-').forEach(function (r) {
    if (r.size > 64 * 1024 * 1024) return; // skip the huge mappings; appboot is a few MB
    try {
      Memory.scan(r.base, r.size, needleToHex(needle), {
        onMatch: function (addr) {
          var start = addr.sub(HEAP_REGION_PAD); if (start.compare(r.base) < 0) start = r.base;
          var end = addr.add(HEAP_REGION_PAD);
          var span = end.compare(r.base.add(r.size)) > 0 ? r.base.add(r.size).sub(start) : end.sub(start);
          try { dump(new Uint8Array(Memory.readByteArray(start, span.toInt32())), 'heap_' + addr, 'sweep@' + needle); }
          catch (e) {}
          return 'stop'; // one dump per range is plenty
        },
        onError: function () {}, onComplete: function () {}
      });
    } catch (e) {}
  });
  log('heap sweep done.');
}
function needleToHex(s) {
  var h = []; for (var i = 0; i < s.length; i++) h.push(('0' + s.charCodeAt(i).toString(16)).slice(-2));
  return h.join(' ');
}

// ---- boot -----------------------------------------------------------------
ensureDir(OUT_DIR);
log('output dir: ' + OUT_DIR);
['open', 'openat'].forEach(hookOpen);
['read', 'pread64', 'pread', 'write', 'pwrite64', 'pwrite'].forEach(hookIO);
setTimeout(recon, 5000);
setTimeout(heapSweep, HEAP_SWEEP_DELAY_MS);

// interactive helpers if you have a host session
rpc.exports = {
  recon: recon,
  sweep: heapSweep,
  sweepfor: function (needle) {                 // sweep for a custom literal
    Process.enumerateRanges('rw-').forEach(function (r) {
      if (r.size > 64 * 1024 * 1024) return;
      try { Memory.scan(r.base, r.size, needleToHex(needle), {
        onMatch: function (addr) {
          var start = addr.sub(HEAP_REGION_PAD); if (start.compare(r.base) < 0) start = r.base;
          try { dump(new Uint8Array(Memory.readByteArray(start, HEAP_REGION_PAD * 2)), 'heap_' + addr, 'sweep@' + needle); } catch (e) {}
          return 'stop';
        }, onError: function () {}, onComplete: function () {} }); } catch (e) {}
    });
    return 'sweep(' + needle + ') queued';
  }
};
log('armed: libc I/O taps live; recon in 5s; heap sweep in ' + (HEAP_SWEEP_DELAY_MS / 1000) + 's.');
