'use strict';
// ============================================================================================
// #317 SCAN-TRACE DIAGNOSTIC PRELUDE — NOT SHIPPED. build.sh prepends this to an unmodified
// copy of patches/src/main/resources/netflix/native/killads.js -> killads-317-trace.js.
//
// Fire TV Stick 3rd Gen (Fire OS 7 / Android 9 / ARM32) dies with SIGSEGV/SEGV_ACCERR inside
// Frida's fault-recovery path ~5 s after "killads ready"; an empty script does not crash.
// This prelude changes nothing about what killads.js patches. It only:
//   1. logs every Memory.scanSync / Memory.scan (range, protection, backing file) BEFORE it runs,
//      so the last "S>" line in logcat before the crash names the range being scanned;
//   2. flags scans whose target is no longer mapped, not readable, or runs past its mapping;
//   3. optionally skips file-backed ranges (DIAG_ANON_ONLY) — candidate fix to test;
//   4. optionally stops whole stages from running (DIAG_DISABLE) — to bisect if 1-3 don't settle it.
// Everything logs to tag KILL:   adb logcat -G 16M && adb logcat -c && adb logcat -s KILL
// ============================================================================================
var DIAG_TRACE_MS   = 30000;  // log EVERY scan for the first 30 s after load (crash window is ~5-10 s)
var DIAG_ANON_ONLY  = false;  // TEST 2: true = never scan file-backed mappings (anonymous memory only)
var DIAG_DISABLE    = [];     // TEST 3: stage names to never run, e.g. ['apply'] or ['fastMASTER']
var DIAG_EXC_LOG    = false;  // optional: log native faults Frida sees (may itself change behaviour)

var _dG = (typeof globalThis !== 'undefined') ? globalThis : (0, eval)('this');
var _dFind = (typeof Module.findGlobalExportByName === 'function')
  ? function (n) { return Module.findGlobalExportByName(n); }
  : function (n) { return Module.findExportByName(null, n); };
var _dLogw = new NativeFunction(_dFind('__android_log_write'), 'int', ['int', 'pointer', 'pointer']);
var _dTag = Memory.allocUtf8String('KILL');
function DL(m) { _dLogw(6, _dTag, Memory.allocUtf8String('DIAG ' + m)); }

var _dT0 = Date.now(), _dN = 0, _dSkipped = 0, _dAnom = 0;
function _dSec() { return ((Date.now() - _dT0) / 1000).toFixed(2); }
function _dInWindow() { return (Date.now() - _dT0) < DIAG_TRACE_MS; }

// Describe the mapping a scan target sits in. Flags: UNMAPPED, NOREAD, SPAN (target runs past the
// end of its mapping into whatever follows), FILE (file-backed).
function _dDesc(base, size) {
  var r = null; try { r = Process.findRangeByAddress(ptr(base)); } catch (e) {}
  if (!r) return { flags: 'UNMAPPED', file: null, txt: 'UNMAPPED' };
  var flags = [], f = (r.file && r.file.path) ? r.file.path : null;
  if (r.protection.charAt(0) !== 'r') flags.push('NOREAD');
  try { if (ptr(base).add(size).compare(r.base.add(r.size)) > 0) flags.push('SPAN'); } catch (e) {}
  if (f) flags.push('FILE');
  return { flags: flags.join(','), file: f,
           txt: r.protection + ' ' + (f || 'anon') + ' map=' + r.base + '+' + r.size };
}

// ---- 1-3: trace (and optionally filter) every synchronous scan ----
var _dScanSync = Memory.scanSync;
Memory.scanSync = function (base, size, pattern) {
  var n = ++_dN, d = _dDesc(base, size);
  if (DIAG_ANON_ONLY && d.file) {
    _dSkipped++;
    if (_dInWindow()) DL('S- #' + n + ' skip file-backed ' + d.txt);
    return [];
  }
  var bad = d.flags.indexOf('UNMAPPED') >= 0 || d.flags.indexOf('NOREAD') >= 0 || d.flags.indexOf('SPAN') >= 0;
  if (bad) _dAnom++;
  if (_dInWindow() || bad)
    DL('S> #' + n + ' t=' + _dSec() + ' ' + base + '+' + size + ' ' + d.txt + (d.flags ? ' [' + d.flags + ']' : '') +
       ' pat=' + String(pattern).slice(0, 23));
  try { return _dScanSync.call(Memory, base, size, pattern); }
  catch (e) { DL('S! #' + n + ' threw ' + e); throw e; }
};
if (Memory.scanSync === _dScanSync) DL('WARN scanSync hook did not install');

// Async scans (Frida worker pool) — only the household path uses them, but one tombstone was on
// pool-frida, so trace them unconditionally.
var _dScan = Memory.scan;
Memory.scan = function (base, size, pattern, cb) {
  var n = ++_dN, d = _dDesc(base, size);
  if (DIAG_ANON_ONLY && d.file) { _dSkipped++; try { cb.onComplete && cb.onComplete(); } catch (e) {} return; }
  DL('A> #' + n + ' t=' + _dSec() + ' ' + base + '+' + size + ' ' + d.txt + (d.flags ? ' [' + d.flags + ']' : ''));
  return _dScan.call(Memory, base, size, pattern, cb);
};

// ---- 4: stage bisect — drop timers for disabled stage functions (matched by function name) ----
var _dSetTimeout = setTimeout, _dBlocked = {};
_dG.setTimeout = function (fn, ms) {
  var nm = fn && fn.name;
  if (nm && DIAG_DISABLE.indexOf(nm) >= 0) {
    if (!_dBlocked[nm]) { _dBlocked[nm] = 1; DL('stage ' + nm + ' DISABLED'); }
    return 0;
  }
  return _dSetTimeout.apply(_dG, arguments);
};

// ---- optional: see the fault Frida is trying to recover from ----
if (DIAG_EXC_LOG) {
  Process.setExceptionHandler(function (d) {
    var m = d.memory ? (' ' + d.memory.operation + '@' + d.memory.address) : '';
    DL('EXC ' + d.type + ' pc=' + d.address + m + ' t=' + _dSec() + ' (map: ' + (d.memory ? _dDesc(d.memory.address, 1).txt : '?') + ')');
    return false;   // never swallow — let the normal path run
  });
}

// ---- heartbeat: pins the crash time against the last S> line ----
(function beat() {
  DL('alive t=' + _dSec() + ' scans=' + _dN + ' skipped=' + _dSkipped + ' anomalies=' + _dAnom);
  _dSetTimeout(beat, _dInWindow() ? 1000 : 15000);
})();
DL('trace prelude loaded: ANON_ONLY=' + DIAG_ANON_ONLY + ' DISABLE=' + JSON.stringify(DIAG_DISABLE) +
   ' EXC_LOG=' + DIAG_EXC_LOG + ' window=' + DIAG_TRACE_MS + 'ms frida=' + Frida.version + ' runtime=' + Script.runtime);
// ===================== end prelude — unmodified killads.js follows =====================
