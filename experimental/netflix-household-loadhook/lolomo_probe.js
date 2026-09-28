/*
 * lolomo_probe.js — READ-ONLY recon for the new CLCSInterstitialLolomo GraphQL op
 * ---------------------------------------------------------------------------
 * Context: github.com/YidirK/Nikflix issue #131 reports Netflix now fires a
 * SECOND interstitial GraphQL operationName, CLCSInterstitialLolomo, alongside
 * the one our existing household patch's design already reasons about,
 * CLCSInterstitialPlaybackAndPostPlayback (see killads.js HH block + this
 * directory's LOADHOOK_PLAN.md §3). Nikflix is a browser extension that
 * filters at the network layer by operationName; this app has no equivalent
 * layer — our household suppression instead flips specific redux flags
 * (isActiveMisdetectionSession / isNetflixHouseholdAvailable) inside the
 * already-loaded appboot JS source (see SuppressHouseholdPromptPatch.kt).
 *
 * Open question this probe answers: does CLCSInterstitialLolomo feed that
 * SAME redux gate (in which case the existing HH_ANCHORS already cover it,
 * once the load-hook timing-wall fix lands), or is it a genuinely separate
 * delivery path that needs its own seam? This is Step "0" ahead of
 * LOADHOOK_PLAN.md Step 2 (read-only interception probe) — pure recon, ZERO
 * memory writes, safe to run against a live shipped app.
 *
 * What it does:
 *   1. Scans rw- ranges for the literal CLCSInterstitialLolomo AND (for
 *      comparison) CLCSInterstitialPlaybackAndPostPlayback.
 *   2. For each hit, logs a sanitized text window of context around the
 *      match (so we can read the actual source shape: is it an entry in a
 *      GraphQL operation-name/id map, a switch-case, a plain string
 *      literal?) — chunked to fit logcat line limits.
 *   3. For each Lolomo hit, checks whether any of the three known HH seam
 *      markers (setAccountSharingFlags, isNetflixHouseholdAvailable,
 *      isActiveMisdetectionSession) also appear within a bounded byte
 *      radius of that hit — a cheap proxy for "same module / same gate".
 *   4. Retries on a poll loop (~2s cadence, ~90s window) since the appboot
 *      bundle materialises late (~20-30s after launch) — matches the timing
 *      already documented for HH in LOADHOOK_PLAN.md.
 *
 * Run (temporary — do NOT bundle this into a shipped build):
 *   - Interactive, with a host: frida -U -n com.netflix.ninja -l lolomo_probe.js
 *   - Or point the gadget's script-mode config at this file for one test run,
 *     then revert to killads.js. Do not ship this file as the app's script.
 *
 * Output: adb logcat -s LOLOMO   (tag chosen to not collide with the
 * production killads.js "KILL" tag, so both can run/be filtered separately).
 */
'use strict';

var logw = new NativeFunction(Module.findGlobalExportByName('__android_log_write'),
  'int', ['int', 'pointer', 'pointer']);
var TAG = Memory.allocUtf8String('LOLOMO');
function L(m) { logw(6, TAG, Memory.allocUtf8String(m)); }

function pat(s) { return s.split('').map(function (c) { return ('0' + c.charCodeAt(0).toString(16)).slice(-2); }).join(' '); }

// Chunk long context dumps to stay under logcat's per-line limit.
var CHUNK = 700;
function logChunked(prefix, s) {
  for (var i = 0; i < s.length; i += CHUNK) {
    L(prefix + ' [' + (i / CHUNK + 1) + '/' + Math.ceil(s.length / CHUNK) + '] ' + s.slice(i, i + CHUNK));
  }
}

// Sanitize a raw byte window to printable text (non-printable -> '.').
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

// The two operationName literals: the new one (subject of issue #131) and the
// already-known one our HH design docs reason about, for side-by-side shape.
var TARGETS = [
  { name: 'CLCSInterstitialLolomo', tag: 'LOLOMO_HIT' },
  { name: 'CLCSInterstitialPlaybackAndPostPlayback', tag: 'PLAYBACK_HIT' }
];

// Known HH seam markers (from killads.js HH_ANCHORS) — proximity to these
// suggests Lolomo feeds the SAME redux gate our existing patch already flips.
var HH_MARKERS = [
  'setAccountSharingFlags',
  'isNetflixHouseholdAvailable',
  'isActiveMisdetectionSession'
];

var CONTEXT_BEFORE = 250;   // bytes of context to dump before a hit
var CONTEXT_AFTER = 450;    // bytes of context to dump after a hit
var PROXIMITY_RADIUS = 8192; // window (bytes, each side) to check for HH markers

var seenHits = {}; // dedupe by "target|address"

function checkProximity(region, hitAddr) {
  var winStart = hitAddr.sub(PROXIMITY_RADIUS);
  if (winStart.compare(region.base) < 0) winStart = region.base;
  var regionEnd = region.base.add(region.size);
  var winEnd = hitAddr.add(PROXIMITY_RADIUS);
  if (winEnd.compare(regionEnd) > 0) winEnd = regionEnd;
  var winSize = winEnd.sub(winStart).toInt32();
  if (winSize <= 0) return;

  for (var m = 0; m < HH_MARKERS.length; m++) {
    var marker = HH_MARKERS[m];
    try {
      var hits = Memory.scanSync(winStart, winSize, pat(marker));
      for (var k = 0; k < hits.length; k++) {
        var off = hits[k].address.sub(hitAddr).toInt32();
        L('PROXIMITY: "' + marker + '" found ' + off + ' bytes from Lolomo hit @' + hitAddr +
          ' (marker @' + hits[k].address + ') — same module/gate candidate');
      }
    } catch (e) {}
  }
}

function scanFor(target, rs) {
  var p = pat(target.name);
  for (var i = 0; i < rs.length; i++) {
    var r = rs[i];
    if (r.size > 128 * 1024 * 1024) continue; // skip huge mappings, matches killads.js convention
    try {
      var hits = Memory.scanSync(r.base, r.size, p);
      for (var j = 0; j < hits.length; j++) {
        var addr = hits[j].address;
        var key = target.name + '|' + addr;
        if (seenHits[key]) continue;
        seenHits[key] = true;

        L(target.tag + ': found "' + target.name + '" @' + addr + ' in range ' + r.base + '-' + r.base.add(r.size) +
          ' prot=' + r.protection);

        var winStart = addr.sub(CONTEXT_BEFORE);
        if (winStart.compare(r.base) < 0) winStart = r.base;
        var span = addr.add(target.name.length + CONTEXT_AFTER).sub(winStart).toInt32();
        var text = sanitize(winStart, span);
        if (text !== null) {
          logChunked(target.tag + '_CTX@' + addr, text);
        } else {
          L(target.tag + '_CTX@' + addr + ': read failed');
        }

        if (target.name === 'CLCSInterstitialLolomo') checkProximity(r, addr);
      }
    } catch (e) {}
  }
}

var tries = 0;
var MAX_TRIES = 45; // ~90s at 2s cadence — appboot materialises ~20-30s in
function probe() {
  tries++;
  var rs = Process.enumerateRanges('rw-');
  for (var t = 0; t < TARGETS.length; t++) scanFor(TARGETS[t], rs);

  var lolomoSeen = Object.keys(seenHits).some(function (k) { return k.indexOf('CLCSInterstitialLolomo|') === 0; });
  var playbackSeen = Object.keys(seenHits).some(function (k) { return k.indexOf('CLCSInterstitialPlaybackAndPostPlayback|') === 0; });
  if (tries % 5 === 0 || (lolomoSeen && playbackSeen)) {
    L('probe status: lolomoSeen=' + lolomoSeen + ' playbackSeen=' + playbackSeen + ' tries=' + tries);
  }
  if (tries < MAX_TRIES) setTimeout(probe, 2000);
  else L('probe done after ' + tries + ' tries. lolomoSeen=' + lolomoSeen + ' playbackSeen=' + playbackSeen);
}

L('lolomo_probe armed (read-only recon for issue #131 CLCSInterstitialLolomo) — logcat -s LOLOMO');
setTimeout(probe, 5000);
