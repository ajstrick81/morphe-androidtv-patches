/*
 * Twitch ATV (Starshot / Laser Array WebView) — fetch/XHR/sendBeacon URL probe.
 *
 * DIAGNOSTIC ONLY. Observation, no behavior change: it wraps the network entry
 * points, classifies each URL, logs it, and keeps a small ring buffer you can pull
 * from the native side. It never blocks, rewrites, or delays a request.
 *
 * Why this exists: static teardown of the ATV apkm proved the video player + all
 * ad-delivery logic are loaded from Twitch's network at runtime (see
 * docs/twitch-ad-delivery-map.md) — none of it is in the APK. So the only way to
 * learn how ATV delivers ads (SSAI weaver-stitched vs. a client-side
 * edge.ads.twitch.tv call) is to watch the live traffic. This shim does that using
 * the same evaluateJavascript seam TwitchAtvWebViewHelper already uses for
 * PLAYBACK_FIX_JS.
 *
 * The open question it answers: during a mid-roll, does the player fetch
 *   https://edge.ads.twitch.tv/2018-01-01/ads?...      (client-side ad decision)
 * or does the ad only ever appear stitched into the weaver .m3u8 playlist (SSAI)?
 * If the former ever fires, some ads are arriving on a path the current
 * playlist-scrubber cannot see.
 *
 * Capture: WebView forwards console.* to logcat, so
 *   adb logcat | grep MORPHE-PROBE
 * shows the live stream. For pull-based retrieval, the native side can run
 *   webView.evaluateJavascript("window.__morpheAdProbe.dump()", cb)
 * to get the JSON ring buffer (last 200 events), or .summary() for host counts.
 */
(function () {
  try {
    if (window.__morpheAdProbe) return;

    var TAG = "MORPHE-PROBE";
    var MAX = 200;

    var probe = {
      events: [],
      counts: {},
      dump: function () { return JSON.stringify(this.events); },
      summary: function () { return JSON.stringify(this.counts); },
      clear: function () { this.events = []; this.counts = {}; }
    };
    window.__morpheAdProbe = probe;

    // Classify a URL into a coarse bucket so the log is readable at a glance and
    // the interesting ad paths stand out from ordinary GQL/asset traffic.
    function classify(url) {
      if (!url) return "other";
      var u = String(url);
      if (/edge\.ads\.twitch\.tv/i.test(u)) return "AD-DECISION";      // client-side ad pod (the smoking gun)
      if (/\/vod-ads/i.test(u)) return "AD-VOD";
      if (/amazon-adsystem\.com|adsafeprotected\.com|googlesyndication|doubleclick/i.test(u)) return "AD-3P";
      if (/\.m3u8|video-weaver|usher\.ttvnw|\.playlist\.ttvnw/i.test(u)) return "PLAYLIST";
      if (/\.ttvnw\.net|cloudfront\.hls/i.test(u)) return "SEGMENT-OR-CDN";
      if (/spade\.twitch\.tv|\/tracking|\/pixel|quartile/i.test(u)) return "TRACKING";
      if (/gql\.twitch\.tv|\/gql/i.test(u)) return "GQL";
      return "other";
    }

    function record(method, url) {
      try {
        var kind = classify(url);
        probe.counts[kind] = (probe.counts[kind] || 0) + 1;
        var ev = { t: Date.now(), m: method, k: kind, url: String(url).slice(0, 300) };
        probe.events.push(ev);
        if (probe.events.length > MAX) probe.events.shift();
        // Surface the ad-relevant buckets loudly; keep the noisy ones quiet so the
        // interesting lines aren't buried. GQL/segments/other are counted, not printed.
        if (kind.indexOf("AD") === 0 || kind === "PLAYLIST" || kind === "TRACKING") {
          console.log(TAG + " [" + kind + "] " + method + " " + ev.url);
        }
      } catch (e) { /* never let logging break the request */ }
    }

    // --- fetch ---
    if (typeof window.fetch === "function") {
      var origFetch = window.fetch;
      window.fetch = function (input, init) {
        try {
          var url = (input && input.url) ? input.url : input;
          record("fetch", url);
        } catch (e) {}
        return origFetch.apply(this, arguments);
      };
    }

    // --- XMLHttpRequest ---
    if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (method, url) {
        try { record("xhr:" + method, url); } catch (e) {}
        return origOpen.apply(this, arguments);
      };
    }

    // --- navigator.sendBeacon (ad quartile / tracking pixels often ride this) ---
    if (navigator && typeof navigator.sendBeacon === "function") {
      var origBeacon = navigator.sendBeacon.bind(navigator);
      navigator.sendBeacon = function (url, data) {
        try { record("beacon", url); } catch (e) {}
        return origBeacon(url, data);
      };
    }

    console.log(TAG + " installed — watching fetch/XHR/sendBeacon");
  } catch (e) {
    try { console.log("MORPHE-PROBE install failed: " + e); } catch (_) {}
  }
})();
