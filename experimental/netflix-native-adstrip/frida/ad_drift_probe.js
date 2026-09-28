/*
 * ad_drift_probe.js — READ-ONLY drift diagnostic for the shipped ad-kill anchors
 * ---------------------------------------------------------------------------
 * Trigger: ads were observed on-device during playback despite the shipped
 * killads.js patches (A/A2/ADV/DAI/B — see ../../../patches/src/main/resources/
 * netflix/native/killads.js). Two very different root causes produce the same
 * symptom ("an ad played"), and this probe exists to tell them apart WITHOUT
 * guessing at a new byte patch first (see CLAUDE.md: dig one level deeper
 * before concluding a wall, but don't skip the digging):
 *
 *   (1) ANCHOR DRIFT — Netflix's minified source shifted enough that one or
 *       more of our five anchor strings no longer appears at all (neither the
 *       original nor our patched variant). The kill logic concept may still
 *       exist, just re-worded/re-ordered — needs re-anchoring, not a new seam.
 *   (2) NEW AD PATH — all five anchors are found in their PATCHED state (our
 *       edits fired, confirmed applied) yet an ad still rendered. That means
 *       Netflix shipped a genuinely new ad-delivery mechanism the existing
 *       five patches don't cover at all — needs new recon (c.f. how the
 *       household prompt's CLCSInterstitialLolomo turned out to be exactly
 *       this shape; see ../../netflix-household-loadhook/LOADHOOK_PLAN.md §5b).
 *
 * This probe is pure recon: it never writes memory. It only classifies each
 * known anchor as ORIGINAL / PATCHED / UNEXPECTED(byte) / ABSENT, and
 * separately runs the same read-only MONITOR heuristics killads.js already
 * ships (KILLMARK/rawRealPods/rawDisplayAd/bookmark) so ad-delivery evidence
 * can be time-correlated against anchor state. It also does one broad
 * ad-vocabulary census pass (adBreak/interstitial/cuePoint/quartile/beacon/
 * ssai/pod/CLCS) to catch a brand-new term family none of the five anchors
 * were ever written against — same census technique used to diagnose the
 * ad-break schema in PORTABILITY-ASSESSMENT.md / REOPENING.md.
 *
 * Run (temporary — do NOT bundle into a shipped build):
 *   frida -U -n com.netflix.ninja -l ad_drift_probe.js
 *   (or point the gadget's script-mode config at this file for one test run,
 *   then revert to killads.js)
 * Best used the same session an ad is reproduced: start the probe, play until
 * the ad you saw yesterday recurs (or force one per the frequency-cap note in
 * REOPENING.md §3e — ~7 ad-free titles then one with a break), then pull logs.
 *
 * Output: adb logcat -s ADDRIFT
 */
'use strict';

var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('ADDRIFT');
function L(m) { logw(6, TAG, Memory.allocUtf8String(m)); }

function pat(s) { return s.split('').map(function (c) { return ('0' + c.charCodeAt(0).toString(16)).slice(-2); }).join(' '); }

var CHUNK = 700;
function logChunked(prefix, s) {
  for (var i = 0; i < s.length; i += CHUNK) {
    L(prefix + ' [' + (i / CHUNK + 1) + '/' + Math.ceil(s.length / CHUNK) + '] ' + s.slice(i, i + CHUNK));
  }
}

function sanitize(addr, len) {
  var bytes;
  try { bytes = new Uint8Array(addr.readByteArray(len)); } catch (e) { return null; }
  var out = '';
  for (var i = 0; i < bytes.length; i++) {
    var c = bytes[i];
    out += (c === 9 || c === 10 || c === 13 || (c >= 32 && c < 127)) ? String.fromCharCode(c) : '.';
  }
  return out;
}

function scanCount(rs, needle) {
  var p = pat(needle), n = 0;
  for (var i = 0; i < rs.length; i++) {
    var r = rs[i]; if (r.size > 128 * 1024 * 1024) continue;
    try { n += Memory.scanSync(r.base, r.size, p).length; } catch (e) {}
  }
  return n;
}

function firstHit(rs, needle) {
  var p = pat(needle);
  for (var i = 0; i < rs.length; i++) {
    var r = rs[i]; if (r.size > 128 * 1024 * 1024) continue;
    try { var h = Memory.scanSync(r.base, r.size, p); if (h.length) return h[0].address; } catch (e) {}
  }
  return null;
}

// ---------- the five shipped anchors, exactly as in killads.js, plus their
// derived PATCHED-variant text (computed by hand from each patch's own
// {off, expected-char, replacement} so we can tell "already applied" apart
// from "source moved"). Keep these in lockstep with killads.js if it changes.
var ANCHORS = [
  {
    label: 'A (legacy manifest ads[])',
    original: 'var f=e.value;f.syncAdStates();f.state.isHydrated&&"viewable"!==f.metadata.source&&f.applyHydration(f.state.hydrationSequenceId)',
    patched: 'var f=e.value;f.metadata&&(f.metadata.ads.length&&(f.__adkill=f.metadata.ads.length),f.metadata.ads=[]);f.syncAdStates();/*...*/'
  },
  {
    label: 'A2 (_syncAdsLength gate)',
    original: 'e.metadata.ads)||void 0===a?void 0:a.length)&&void 0!==b?b:0;this._ads.length',
    patched: 'e.metadata.axs)||void 0===a?void 0:a.length)&&void 0!==b?b:0;this._ads.length'
  },
  {
    label: 'ADV (adverts.adBreaks source)',
    original: ':ba.map(function(a,b){var c=ea.normalize',
    patched: ':[].map(function(a,b){var c=ea.normalize'
  },
  {
    label: 'DAI (applyDaiPrefetch guard)',
    original: 'return e&&0!==e.size?a.map',
    patched: 'return e&&0===e.size?a.map'
  },
  {
    label: 'B (pause overlay displayAd)',
    original: 'void 0:e.displayAd',
    patched: 'void 0:void 0     '
  }
];

// Broad ad-vocabulary census — same terms used historically to diagnose the
// ad-break schema (PORTABILITY-ASSESSMENT.md §3/§3d). A new high-count term
// here that ISN'T one of the five anchors above is the "new ad path" lead.
var CENSUS_TERMS = [
  'adBreak', 'AdBreak', 'interstitial', 'cuePoint', 'quartile', 'beacon',
  'ssai', 'SSAI', 'adverts', 'StatefulAdBreak', 'applyDaiPrefetch',
  'CLCSInterstitial', 'displayAd', 'pauseAd', 'midroll', 'preroll'
];

function classifyAnchors(rs) {
  for (var i = 0; i < ANCHORS.length; i++) {
    var an = ANCHORS[i];
    var origHit = firstHit(rs, an.original);
    var patHit = firstHit(rs, an.patched);
    if (origHit) {
      L('ANCHOR ' + an.label + ': ORIGINAL (unpatched) @' + origHit + ' — patch has not fired yet, or fired on a different in-memory copy');
    } else if (patHit) {
      L('ANCHOR ' + an.label + ': PATCHED (confirmed applied) @' + patHit);
    } else {
      L('ANCHOR ' + an.label + ': ABSENT — neither original nor patched text found. Candidate DRIFT (source likely re-worded); dumping census context below if any related term hit.');
    }
  }
}

function census(rs) {
  L('--- ad-vocabulary census ---');
  for (var i = 0; i < CENSUS_TERMS.length; i++) {
    var term = CENSUS_TERMS[i];
    var count = scanCount(rs, term);
    if (count > 0) {
      var addr = firstHit(rs, term);
      L('CENSUS "' + term + '": ' + count + ' hit(s), first @' + addr);
    }
  }
  L('--- census done ---');
}

// ---------- read-only MONITOR, mirrors killads.js observe() exactly, so its
// output is directly comparable to the shipped script's own OBS lines.
var KILLMARK = pat('__adkill'), REALPOD = pat('ads":[{'), DISPAD = pat('displayAd":{'), BM = pat('"bookmark":');
function readNumAfter(addr, skip) { try { var s = addr.add(skip).readCString(14); var m = /^([0-9]{1,12})/.exec(s); return m ? parseInt(m[1], 10) : -1; } catch (e) { return -1; } }
var cyc = 0;
function observe() {
  cyc++;
  var rs = Process.enumerateRanges('rw-'); var kill = 0, real = 0, disp = 0; var bset = {};
  for (var i = 0; i < rs.length; i++) {
    var r = rs[i]; if (r.size > 64 * 1024 * 1024 || r.size < 256) continue;
    try {
      kill += Memory.scanSync(r.base, r.size, KILLMARK).length;
      real += Memory.scanSync(r.base, r.size, REALPOD).length;
      disp += Memory.scanSync(r.base, r.size, DISPAD).length;
      var bh = Memory.scanSync(r.base, r.size, BM);
      for (var k = 0; k < bh.length && k < 40; k++) { var v = readNumAfter(bh[k].address, 11); if (v > 0) bset[v] = 1; }
    } catch (e) {}
  }
  var bks = Object.keys(bset).map(Number).sort(function (a, b) { return b - a }).slice(0, 6);
  var tag = (kill > 1 ? '  <<<MANIFEST-KILL' : '') + (disp > 0 ? '  <<<server-pauseAd(x' + disp + ')' : '') + (real > 0 ? '  <<<rawRealPod(x' + real + ')' : '');
  L('OBS' + cyc + ': KILLMARK=' + kill + ' rawRealPods=' + real + ' rawDisplayAd=' + disp + ' bookmarks=' + JSON.stringify(bks) + tag);

  // Ad-delivery evidence WITHOUT a matching kill mark = strongest single
  // signal of case (2), new ad path: content data present, our own kill
  // marker never got attached to it. Dump context immediately.
  if ((real > 0 || disp > 0) && kill === 0) {
    L('!!! ad-delivery evidence with ZERO kill marks — dumping context around first hit');
    var addr = firstHit(rs, real > 0 ? 'ads":[{' : 'displayAd":{');
    if (addr) {
      var text = sanitize(addr.sub(200), 600);
      if (text !== null) logChunked('DRIFT_CTX@' + addr, text);
    }
  }
  if (cyc < 560) setTimeout(observe, 3000); // ~28 min coverage, matches killads.js
}

// ---------- boot -----------------------------------------------------------
L('ad_drift_probe armed (read-only) — logcat -s ADDRIFT');
setTimeout(function () { L('=== initial anchor classification ==='); classifyAnchors(Process.enumerateRanges('rw-')); }, 15000);
setTimeout(function () { census(Process.enumerateRanges('rw-')); }, 40000);
setTimeout(function () { L('=== re-check anchor classification (post-boot settle) ==='); classifyAnchors(Process.enumerateRanges('rw-')); }, 60000);
setTimeout(observe, 9000);
