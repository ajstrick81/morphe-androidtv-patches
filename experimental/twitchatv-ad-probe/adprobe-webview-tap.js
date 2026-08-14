'use strict';
// ADPROBE — WebView (in-page V8) heap/data-tap front-end (read-only ad-seam diagnostic)
// ====================================================================================
// A Twitch-ATV front-end for the ADPROBE methodology (experimental/netflix-adprobe/
// adprobe-jsvm-tap.js). Same four reusable ideas — swappable keyword table, residency
// classification (code vs data), seam-lock, live-capture discipline — but a DIFFERENT
// front-end, because Twitch ATV's ad logic does not live where Netflix's does.
//
//   Netflix / PV : JS VM (QuickJS) runs IN the app's own native process  -> Frida rw- heap scan.
//   Twitch ATV   : player (hls.js) runs in the system WebView's CHROMIUM renderer — a separate,
//                  sandboxed process, loaded from the NETWORK (proven: the APK ships no player/ad
//                  code; see docs/twitch-ad-delivery-map.md). An in-process Frida scan of the
//                  Twitch app process would scan the empty shell. So the correct front-end is an
//                  IN-PAGE tap: injected via evaluateJavascript (like PLAYBACK_FIX_JS), it runs
//                  INSIDE the same V8 VM as the player and reads live objects + fetched bodies
//                  directly — no memory scanning needed.
//
// It is a DIAGNOSTIC, never a patch: it reads and logs only. Inject once; it hooks fetch/XHR to
// accumulate a corpus of readable strings (playlist bodies, response text) and, on each pass,
// runs the keyword table over that corpus + live player objects, classifying each hit's residency.
//
// THE FOUR PORTED IDEAS (kept identical to the base ADPROBE):
//  1. KEYWORD TABLE {k, w, b} — k=needle, w=context bytes to dump (0 = count-only), b=bytes-before.
//     Retarget by swapping the table; scan/dump logic is unchanged.
//  2. RESIDENCY CLASSIFICATION (code vs data) — but adapted to the WebView reality: in-page JS
//     CANNOT read cross-origin bundle SOURCE (the hls.js bundle is network-loaded, opaque to the
//     page). It CAN read (a) DATA the player fetched (m3u8 playlist bodies) and (b) live OBJECT
//     state (the hls.js instance, the <video> element). So residency here answers: is the ad
//     discriminator present as DATA (in playlists / objects — seam is the NETWORK/DATA layer, i.e.
//     shouldInterceptRequest, exactly what the shipping scrubber patches) vs is there any resident
//     CODE seam in-page (there is not, because the bundle is cross-origin). This independently
//     re-derives WHY the patch must live at the native request layer.
//  3. SEAM-LOCK — narrow to the consumer that branches on the ad discriminator, then to the single
//     upstream source all consumers share. For Twitch SSAI that source is the weaver .m3u8 body
//     (the EXTINF title / DATERANGE CLASS the scrubber already keys on). The decisive extra target
//     is X-TV-TWITCH-AD / edge.ads.twitch.tv — if those ever appear, a CLIENT-SIDE source exists
//     alongside the playlist, i.e. a second seam the scrubber misses.
//  4. LIVE-CAPTURE — WebView mirrors console.* to logcat, but capture with a live window during a
//     real ad break; use w:0 count keys to confirm "0 ad segments" after a patch.
//
// Capture:  adb logcat | grep MORPHE-ADPROBE
// Pull:     webView.evaluateJavascript("window.__morpheAdprobe.pass()", cb)   // run a pass on demand
//           webView.evaluateJavascript("window.__morpheAdprobe.dump()", cb)   // JSON of last hits

(function () {
  try {
    if (window.__morpheAdprobe) return;
    var TAG = 'MORPHE-ADPROBE';

    // ---- corpus: readable strings the page legitimately has access to ------------------
    // Each entry is {kind, get} where get() returns a string. kind drives residency labeling.
    var BODIES = [];          // captured fetch/XHR response bodies (playlists etc.), bounded ring
    var MAXB = 12;
    function pushBody(url, text) {
      if (!text || text.length > 512 * 1024) return;
      // Only keep bodies that look like manifests / ad payloads — skip binary/JSON noise.
      if (!/#EXTM3U|#EXT-X|twitch-stitched-ad|X-TV-TWITCH-AD|adBreaks|surestream/i.test(text)) return;
      BODIES.push({ url: String(url).slice(0, 200), text: text });
      if (BODIES.length > MAXB) BODIES.shift();
    }

    function corpus() {
      var c = [];
      for (var i = 0; i < BODIES.length; i++) c.push({ kind: 'playlist-data', src: BODIES[i].url, s: BODIES[i].text });
      // live object state: stringify likely player globals + the <video> element's buffered/quality
      try {
        var g = window.hls || window.__hls || (window.Twitch && window.Twitch.player);
        if (g) c.push({ kind: 'live-object', src: 'player-global', s: safeStringify(g) });
      } catch (e) {}
      try {
        var v = document.querySelector('video');
        if (v) c.push({ kind: 'live-object', src: 'video-el', s: 'currentTime=' + v.currentTime + ' paused=' + v.paused + ' src=' + (v.currentSrc || '').slice(0, 160) });
      } catch (e) {}
      // performance resource URLs — cheap way to see ad-endpoint fetches even without bodies
      try {
        var urls = performance.getEntriesByType('resource').map(function (r) { return r.name; }).join('\n');
        if (urls) c.push({ kind: 'resource-urls', src: 'performance', s: urls });
      } catch (e) {}
      return c;
    }

    function safeStringify(o) {
      var seen = [];
      try {
        return JSON.stringify(o, function (k, v) {
          if (typeof v === 'object' && v !== null) { if (seen.indexOf(v) >= 0) return '[circular]'; seen.push(v); }
          if (typeof v === 'function') return 'fn';
          return v;
        }).slice(0, 20000);
      } catch (e) { return String(o).slice(0, 4000); }
    }

    // ---- keyword table = Twitch ad-delivery discriminators. SWAP for another app. -------
    // {k: needle, w: context-window chars (0 = count-only), b: chars-before to include}
    var KEYS = [
      // SSAI / SureStream — the path the shipping scrubber handles (expect DATA residency)
      { k: 'twitch-stitched-ad', w: 200, b: 40 },   // ad daterange CLASS marker
      { k: '#EXT-X-DATERANGE',   w: 180, b: 10 },
      { k: 'ROLL-TYPE',          w: 60,  b: 12 },   // PREROLL / MIDROLL
      { k: ',live',              w: 0,   b: 0  },   // content EXTINF title (discriminator baseline, count)
      { k: '#EXT-X-DISCONTINUITY', w: 0, b: 0  },
      { k: 'stitched',           w: 0,   b: 0  },
      { k: 'SureStream',         w: 120, b: 20 },
      // CLIENT-SIDE path — the smoking gun. If any of these ever appear in an ATV playlist/body,
      // there is a second ad source the playlist-scrubber cannot see.
      { k: 'X-TV-TWITCH-AD',     w: 120, b: 20 },
      { k: 'X-TTV-MAF-AD',       w: 120, b: 20 },
      { k: 'edge.ads.twitch.tv', w: 120, b: 20 },
      { k: 'commercial',         w: 100, b: 20 },
      // sanity keys — confirm the tap is even seeing the stream
      { k: 'video-weaver',       w: 0,   b: 0  },
      { k: '.m3u8',              w: 0,   b: 0  }
    ];

    var seen = {}, passNo = 0, lastHits = [];

    function pass(keys) {
      var table = keys || KEYS; passNo++;
      var counts = {}, residency = {}; lastHits = [];
      var c = corpus();
      for (var pi = 0; pi < table.length; pi++) {
        var K = table[pi]; if (!K.k) continue;
        for (var ci = 0; ci < c.length; ci++) {
          var chunk = c[ci], s = chunk.s, from = 0, idx;
          while ((idx = s.indexOf(K.k, from)) >= 0) {
            counts[K.k] = (counts[K.k] || 0) + 1;
            (residency[K.k] = residency[K.k] || {})[chunk.kind] = 1;
            from = idx + K.k.length;
            if (K.w > 0) {
              var key = K.k + '@' + chunk.src + '#' + idx;
              if (!seen[key]) {
                seen[key] = 1;
                var ctx = s.slice(Math.max(0, idx - K.b), idx + K.w).replace(/[^\x20-\x7e]/g, '.');
                lastHits.push({ k: K.k, kind: chunk.kind, src: chunk.src, ctx: ctx });
                console.log(TAG + ' [' + K.k + '] {' + chunk.kind + '} ' + chunk.src + ' :: ' + ctx);
              }
            }
          }
        }
      }
      // residency verdict per key: data-only vs (impossible here) code — makes the seam explicit
      var res = {};
      Object.keys(residency).forEach(function (k) { res[k] = Object.keys(residency[k]).join('+'); });
      console.log(TAG + ' pass' + passNo + ' counts=' + JSON.stringify(counts));
      console.log(TAG + ' pass' + passNo + ' residency=' + JSON.stringify(res));
      // seam-lock hint: did a client-side source appear?
      if (counts['X-TV-TWITCH-AD'] || counts['X-TTV-MAF-AD'] || counts['edge.ads.twitch.tv']) {
        console.log(TAG + ' SEAM: client-side ad source present — playlist scrubber does NOT cover it.');
      }
      return { counts: counts, residency: res, hits: lastHits };
    }

    // ---- hook fetch/XHR to feed the body corpus (read-only; never blocks/rewrites) ------
    if (typeof window.fetch === 'function') {
      var of = window.fetch;
      window.fetch = function (input, init) {
        var url = (input && input.url) ? input.url : input;
        var p = of.apply(this, arguments);
        try {
          p.then(function (resp) {
            try { resp.clone().text().then(function (t) { pushBody(url, t); }).catch(function () {}); } catch (e) {}
            return resp;
          }).catch(function () {});
        } catch (e) {}
        return p;
      };
    }
    if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
      var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function (m, url) { this.__ap_url = url; return oo.apply(this, arguments); };
      XMLHttpRequest.prototype.send = function () {
        var xhr = this;
        try {
          xhr.addEventListener('load', function () {
            try { if (typeof xhr.responseText === 'string') pushBody(xhr.__ap_url, xhr.responseText); } catch (e) {}
          });
        } catch (e) {}
        return os.apply(this, arguments);
      };
    }

    window.__morpheAdprobe = {
      pass: function () { return JSON.stringify(pass()); },
      dump: function () { return JSON.stringify(lastHits); },
      bodies: function () { return BODIES.length; },
      KEYS: KEYS
    };

    // auto-pass on a timer so a passive `adb logcat | grep MORPHE-ADPROBE` run captures ad breaks.
    try { setInterval(function () { try { pass(); } catch (e) {} }, 15000); } catch (e) {}
    console.log(TAG + ' installed — WebView in-page tap (residency: playlist-data / live-object)');
  } catch (e) {
    try { console.log('MORPHE-ADPROBE install failed: ' + e); } catch (_) {}
  }
})();

// Node/CommonJS export for host tests; harmless in the WebView (module undefined -> skipped).
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { note: 'in-page WebView front-end; run inside the page, not Node' };
}
