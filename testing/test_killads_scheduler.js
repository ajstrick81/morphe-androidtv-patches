'use strict';
// Unit test for killads.js async scheduler and household suppression logic.
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const SRC = path.join(__dirname, '../patches/src/main/resources/netflix/native/killads.js');

const MEM = Buffer.alloc(8 * 1024 * 1024);
let brk = 0x1000;
function alloc(n) { const a = brk; brk += (n + 15) & ~15; return a; }

class P {
  constructor(o) { this.o = Number(o) >>> 0; }
  add(n) { return new P(this.o + Number(n instanceof P ? n.o : n)); }
  sub(n) { return new P((this.o - Number(n instanceof P ? n.o : n)) >>> 0); }
  chk(n) { if (this.o < 0x1000 || this.o + n > MEM.length) throw new Error('access violation @' + this.o.toString(16)); }
  readU8() { this.chk(1); return MEM[this.o]; }
  readU32() { this.chk(4); return MEM.readUInt32LE(this.o); }
  readCString(n) {
    this.chk(1);
    let e = this.o;
    const lim = n == null ? MEM.length : Math.min(MEM.length, this.o + n);
    while (e < lim && MEM[e]) e++;
    return MEM.toString('latin1', this.o, e);
  }
  readByteArray(n) { this.chk(n); return MEM.buffer.slice(MEM.byteOffset + this.o, MEM.byteOffset + this.o + n); }
  writeU8(b) { this.chk(1); MEM[this.o] = b; }
  writeByteArray(a) { this.chk(a.length); for (let i = 0; i < a.length; i++) MEM[this.o + i] = a[i]; }
  toString() { return '0x' + this.o.toString(16); }
}

const ptr = x => new P(typeof x === 'string' ? parseInt(x) : x);
const logs = [];
const timers = [];
let sandboxRanges = () => [];
let sandboxModules = {};
const asyncQ = [];
let inFlight = 0;
let maxInFlight = 0;

function flushAsync() {
  while (asyncQ.length) asyncQ.shift()();
}

const Memory = {
  protect() { return true; },
  scan(base, size, patStr, cb) {
    inFlight++;
    maxInFlight = Math.max(maxInFlight, inFlight);
    let hits = [];
    try {
      hits = Memory.scanSync(base, size, patStr);
    } catch (e) {
      asyncQ.push(() => { inFlight--; cb.onError(String(e)); });
      return;
    }
    asyncQ.push(() => {
      inFlight--;
      hits.forEach(h => cb.onMatch(h.address, h.size));
      cb.onComplete();
    });
  },
  allocUtf8String(s) {
    const a = alloc(s.length + 1);
    MEM.write(s, a, 'latin1');
    return new P(a);
  },
  scanSync(base, size, patStr) {
    const toks = patStr.split(' ');
    base.chk(size);
    const out = [];
    const end = base.o + size;
    const needle = Buffer.from(toks.map(h => parseInt(h, 16)));
    let i = base.o;
    while (true) {
      i = MEM.indexOf(needle, i);
      if (i < 0 || i + needle.length > end) break;
      out.push({ address: new P(i), size: needle.length });
      i++;
    }
    return out;
  }
};

const sandbox = {
  console, Memory, ptr, NativePointer: P, Date, JSON, Math, Object, String, Number, Uint8Array, parseInt, isNaN,
  NativeFunction: class { constructor() { return (lvl, tag, msg) => { logs.push(msg.readCString()); return 0; }; } },
  Module: { findGlobalExportByName() { return new P(0x10); } },
  Process: {
    enumerateRanges() { return sandboxRanges(); },
    findRangeByAddress() { return null; },
    findModuleByName(name) { return sandboxModules[name] || null; }
  },
  setTimeout(fn, ms) { timers.push({ fn, ms }); return 0; }
};

let fail = 0;
function ok(c, m) {
  console.log((c ? 'PASS ' : 'FAIL ') + m);
  if (!c) fail++;
}

function runTest() {
  let raw = fs.readFileSync(SRC, 'latin1');
  raw = raw.replace('var HH_ENABLED=false;', 'var HH_ENABLED=true;');

  vm.createContext(sandbox);
  vm.runInContext(raw + '\n;this.__t={aScanAll,aScanRange,hhBusy,hhLanded,adkDefer,fastHH,fastCLCS,patchGibbonBootstrap,rwRanges,scannable,st:function(){return {clcsDone,frDone,hhDone,hhFlipped,mhuDoneSet};}};', sandbox);

  const T = sandbox.__t;
  ok(typeof T.aScanAll === 'function', 'aScanAll is defined');
  ok(typeof T.hhBusy === 'function', 'hhBusy is defined');
  ok(typeof T.hhLanded === 'function', 'hhLanded is defined');
  ok(typeof T.adkDefer === 'function', 'adkDefer is defined');

  // Test 1: scannable filters device and proc mappings
  ok(T.scannable({ file: null }) === true, 'anon memory is scannable');
  ok(T.scannable({ file: { path: '/dev/zero' } }) === true, '/dev/zero is scannable');
  ok(T.scannable({ file: { path: '/dev/mali0' } }) === false, '/dev/mali is NOT scannable');
  ok(T.scannable({ file: { path: '/proc/self/maps' } }) === false, '/proc is NOT scannable');

  // Test 2: Pool limits in-flight scans to AS_MAX=2
  const rg = [];
  for (let k = 0; k < 6; k++) {
    const b = alloc(65536);
    rg.push({ base: new P(b), size: 65536 });
  }
  let doneCount = 0;
  maxInFlight = 0;
  T.aScanAll(rg, '61 62 63', () => {}, () => { doneCount++; });
  ok(inFlight === 2, 'at most 2 scans in flight initially (inFlight=' + inFlight + ')');
  flushAsync();
  ok(doneCount === 1 && maxInFlight === 2, 'drained all 6 ranges with max 2 in flight');

  // Test 3: Priority queue: household (prio 1) outranks diag (prio 3)
  const rr = [];
  for (let k = 0; k < 4; k++) rr.push({ base: new P(alloc(65536)), size: 65536 });
  const calls = [];
  const origScan = Memory.scan;
  sandbox.Memory.scan = function (base, size, patStr, cb) {
    calls.push(patStr);
    inFlight++;
    asyncQ.push(() => { inFlight--; cb.onComplete(); });
  };
  T.aScanAll(rr, '33 33 33', () => {}, () => {}, true); // low prio (prio 3)
  T.aScanAll(rr.slice(0, 2), '11 11 11', () => {}, () => {}, 1); // household (prio 1)
  asyncQ.shift()(); // slot frees
  ok(calls[2] === '11 11 11', 'household scan outranks low priority scan when slot frees');
  flushAsync();
  sandbox.Memory.scan = origScan;
  Memory.scan = origScan;

  // Test 4: adkDefer logic
  sandbox._fcT0 = Date.now();
  ok(T.adkDefer() === true, 'adkDefer is true initially before household lands');
  sandbox.clcsDone = true;
  sandbox.frDone = true;
  sandbox.hhDone = true;
  ok(T.hhLanded() === true, 'hhLanded is true when clcsDone && frDone && hhDone');
  ok(T.adkDefer() === false, 'adkDefer releases once household has landed');

  // Restore Memory.scan to real implementation for functional tests
  sandbox.Memory.scan = Memory.scan;

  // Test 5: fastCLCS patches dispatcher and route2 in memory (range >= AS_MIN_RANGE)
  const fcSnippet = 'function d(e,t){switch(t.type){case"clcsInterstitialGate":case"clcsInterstitialPreProfileGate":return r.pushClcsInterstitialGate(e);default:return}}'
    + ' x=(0,s.log)({msg:"doRouteOnFetchedInterstitial:fetchDgsData"}),!v){p.next=24;break}';
  const fcBuf = Buffer.from(fcSnippet, 'latin1');
  const fcAllocSize = 65536;
  const fcAddr = alloc(fcAllocSize);
  fcBuf.copy(MEM, fcAddr);

  sandbox.clcsDone = false;
  sandbox.frDone = false;
  sandboxRanges = () => [{ base: new P(fcAddr), size: fcAllocSize }];
  timers.length = 0;
  T.fastCLCS();
  flushAsync();

  const patchedFc = MEM.toString('latin1', fcAddr, fcAddr + fcBuf.length);
  ok(patchedFc.includes('case"xlcsInterstitialGate":case"xlcsInterstitialPreProfileGate":'), 'fastCLCS broke dispatcher case labels');
  ok(patchedFc.includes('doRouteOnFetchedInterstitial:fetchDgsData"}),!0){p.next=24;break}'), 'fastCLCS flipped route2 guard !v -> !0');
  ok(sandbox.clcsDone === true && sandbox.frDone === true, 'clcsDone and frDone are true after fastCLCS');

  // Test 6: fastHH patches account sharing flags, render gates, and neuters MHU screen
  const hhSnippet = 'u.put((0,a.setAccountSharingFlags)({isActiveMisdetectionSession:!0}));'
    + ' u.put((0,a.setAccountSharingFlags)({isNetflixHouseholdAvailable:!0}));'
    + ' "ready"===c&&(y=null!=f&&f,w=null!=h&&h);'
    + ' MHU_RESOLUTION_LANDING,render:function(e){var t=e.a,n=e.b,r=1;\nreturn(0,u.j)("div",{})}';
  const hhBuf = Buffer.from(hhSnippet, 'latin1');
  const hhAllocSize = 65536;
  const hhAddr = alloc(hhAllocSize);
  hhBuf.copy(MEM, hhAddr);

  sandbox.hhDone = false;
  sandbox.hhFlipped = {};
  sandbox.mhuDoneSet = {};
  sandbox.mhuHits = 0;
  sandboxRanges = () => [{ base: new P(hhAddr), size: hhAllocSize }];
  timers.length = 0;
  T.fastHH();
  flushAsync();

  const patchedHh = MEM.toString('latin1', hhAddr, hhAddr + hhBuf.length);
  ok(patchedHh.includes('isActiveMisdetectionSession:!1'), 'fastHH flipped isActiveMisdetectionSession to !1');
  ok(patchedHh.includes('isNetflixHouseholdAvailable:!1'), 'fastHH flipped isNetflixHouseholdAvailable to !1');
  ok(patchedHh.includes('y=null!=f&&0,w=null!=h&&0)'), 'fastHH flipped render gates to 0');
  ok(/MHU_RESOLUTION_LANDING,render:function\(e\)\{return null; *\nreturn/.test(patchedHh), 'MHU screen render neutered to return null;');
  ok(sandbox.hhDone === true, 'hhDone is true after render gate flipped');

  // Test 7: patchGibbonBootstrap clears V8ScriptCache and neutralizes e.cache(100)
  const gbSnippet = 'nrdp$1.script.addEventListener("script",(e=>{0!==e.version&&e.usedCache||e.cache(1)})),'
    + 'nrdp$1.gibbon.addEventListener("uiLoaded",(()=>{!function e(t,r,i){const a=Date.now()\n'
    + 'for(;t.length&&Date.now()-a<r;){const e=t.pop()\ne&&(e.version<100||!e.usedCache)&&e.cache(100)}'
    + 't.length&&nrdp$1.setTimeout(e.bind(null,t,r,i),i)}(nrdp$1.script.scripts(),250,500)}))';
  const gbBuf = Buffer.from(gbSnippet, 'latin1');
  const gbAllocSize = 0x10000;
  const gbAddr = alloc(gbAllocSize);
  gbBuf.copy(MEM, gbAddr);

  const gbBase = (gbAddr - 0x3c70f31) >>> 0;
  sandboxModules['libgibbon.so'] = { name: 'libgibbon.so', base: new P(gbBase), size: 0x4000000 };
  sandbox._gbDone = false;
  sandbox._gbPass = 0;
  T.patchGibbonBootstrap();

  const patchedGb = MEM.toString('latin1', gbAddr, gbAddr + gbBuf.length);
  ok(patchedGb.includes('nrdp$1.script.clearScriptCache()/*'), 'patchGibbonBootstrap replaced script listener with clearScriptCache');
  ok(patchedGb.includes('void 0/*..*/'), 'patchGibbonBootstrap neutered e.cache(100) in uiLoaded');
  ok(sandbox._gbDone === true, '_gbDone is true after patchGibbonBootstrap');

  console.log(fail ? ('\n' + fail + ' FAILED') : '\nALL PASS');
  process.exit(fail ? 1 : 0);
}

runTest();
