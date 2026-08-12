package ajstrick81.morphe.extension.twitchatv.ads;

import android.webkit.WebView;

/**
 * DIAGNOSTIC reference — NOT wired into the shipping patch.
 *
 * Shows how to inject {@code fetch-xhr-probe.js} using the exact seam
 * TwitchAtvWebViewHelper already uses for PLAYBACK_FIX_JS. To activate during an
 * investigation, add one line to the WrappedClient callbacks:
 *
 * <pre>
 *   &#64;Override public void onPageFinished(WebView view, String url) {
 *       original.onPageFinished(view, url);
 *       injectPlaybackFix(view);
 *       TwitchAtvUrlProbe.inject(view);   // &lt;-- add for a capture session, remove after
 *   }
 * </pre>
 *
 * Off by default: nothing in the shipping extension calls this. It is observation
 * only — wraps window.fetch / XHR.open / navigator.sendBeacon, logs and counts, and
 * never blocks, rewrites, or delays a request. Injecting is idempotent (the JS
 * self-guards on {@code window.__morpheAdProbe}), so it is safe to call on every
 * page event like injectPlaybackFix.
 *
 * Read the capture with:  adb logcat | grep MORPHE-PROBE
 * or pull the ring buffer: view.evaluateJavascript("window.__morpheAdProbe.dump()", cb)
 *
 * The JS below is the minified inline form of fetch-xhr-probe.js in this folder;
 * keep the two in sync (edit the readable .js, then re-minify into this string).
 */
public final class TwitchAtvUrlProbe {
    private TwitchAtvUrlProbe() {}

    public static void inject(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(PROBE_JS, null);
        } catch (Throwable t) {
            // Diagnostic must never affect playback — swallow.
        }
    }

    private static final String PROBE_JS =
        "(function(){try{if(window.__morpheAdProbe)return;var T='MORPHE-PROBE',M=200,"
        + "p={events:[],counts:{},dump:function(){return JSON.stringify(this.events)},"
        + "summary:function(){return JSON.stringify(this.counts)},"
        + "clear:function(){this.events=[];this.counts={}}};window.__morpheAdProbe=p;"
        + "function c(u){if(!u)return'other';u=String(u);"
        + "if(/edge\\.ads\\.twitch\\.tv/i.test(u))return'AD-DECISION';"
        + "if(/\\/vod-ads/i.test(u))return'AD-VOD';"
        + "if(/amazon-adsystem\\.com|adsafeprotected\\.com|googlesyndication|doubleclick/i.test(u))return'AD-3P';"
        + "if(/\\.m3u8|video-weaver|usher\\.ttvnw|\\.playlist\\.ttvnw/i.test(u))return'PLAYLIST';"
        + "if(/\\.ttvnw\\.net|cloudfront\\.hls/i.test(u))return'SEGMENT-OR-CDN';"
        + "if(/spade\\.twitch\\.tv|\\/tracking|\\/pixel|quartile/i.test(u))return'TRACKING';"
        + "if(/gql\\.twitch\\.tv|\\/gql/i.test(u))return'GQL';return'other'}"
        + "function r(m,u){try{var k=c(u);p.counts[k]=(p.counts[k]||0)+1;"
        + "var e={t:Date.now(),m:m,k:k,url:String(u).slice(0,300)};p.events.push(e);"
        + "if(p.events.length>M)p.events.shift();"
        + "if(k.indexOf('AD')===0||k==='PLAYLIST'||k==='TRACKING'){console.log(T+' ['+k+'] '+m+' '+e.url)}}catch(x){}}"
        + "if(typeof window.fetch==='function'){var of=window.fetch;window.fetch=function(i,n){try{var u=(i&&i.url)?i.url:i;r('fetch',u)}catch(x){}return of.apply(this,arguments)}}"
        + "if(window.XMLHttpRequest&&XMLHttpRequest.prototype){var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{r('xhr:'+m,u)}catch(x){}return oo.apply(this,arguments)}}"
        + "if(navigator&&typeof navigator.sendBeacon==='function'){var ob=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){try{r('beacon',u)}catch(x){}return ob(u,d)}}"
        + "console.log(T+' installed — watching fetch/XHR/sendBeacon')}catch(x){try{console.log('MORPHE-PROBE install failed: '+x)}catch(y){}}})();";
}
