package ajstrick81.morphe.extension.twitchatv.ads;

import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Twitch Android TV (Starshot / laserarray WebView) — HLS ad-segment scrubber.
 *
 * The TV player is hls.js in a WebView; live ads are server-stitched (SSAI) into
 * the "video-weaver" media playlist served from {@code *.playlist.ttvnw.net}. That
 * fetch does NOT go through the service worker ({@code fromServiceWorker=false},
 * verified on-device), so a native {@link WebViewClient#shouldInterceptRequest}
 * override sees it regardless of any JS fetch/XHR timing — the timing-independent
 * seam the vaft JS injection could not reliably win.
 *
 * On-device capture of a real ad break showed the exact structure:
 * <pre>
 *   #EXT-X-DATERANGE:...CLASS="twitch-stitched-ad"...ROLL-TYPE="PREROLL"
 *   #EXT-X-DISCONTINUITY
 *   #EXTINF:2.002,DCM|259975342        &lt;- ad segment (title = "DCM|&lt;creative&gt;")
 *   https://&lt;hash&gt;.j.cloudfront.hls.ttvnw.net/v1/segment/...ts
 *   ...
 * </pre>
 * Content segments are {@code #EXTINF:2.000,live}. So the discriminator is the
 * EXTINF title: {@code live} = content, anything else = ad.
 *
 * BLANKING, not stripping. An earlier version removed ad segments outright, but a
 * pre/mid-roll's whole window is ad, so that left an EMPTY live playlist — hls.js
 * never advances the ad timeline, the ad never completes server-side, and Twitch
 * serves it endlessly (a stuck-ad loop, not just a gap). Instead we keep the
 * playlist intact (segments AND the quartile/trigger dateranges, so the ad's
 * beacons fire and it completes) and only rewrite each ad segment's URI to a
 * sentinel we serve a ~2s black+silent TS for ({@link BlankSegment}). The
 * timeline advances (black plays for the ad duration), the ad completes, and
 * content resumes — the ad video is never shown.
 *
 * The playlist token URL needs no auth (a plain GET works — verified), so we
 * re-fetch it ourselves, rewrite, and return it. Fails open: any error returns
 * the original client's response so playback is never broken by this hook.
 */
public final class TwitchAtvWebViewHelper {

    private static final String TAG = "MORPHE-TW-ATV-WV";

    /** Sentinel host that ad segment URIs are rewritten to; we serve black TS for it. */
    private static final String BLANK_HOST = "morphe.invalid";
    private static final String BLANK_SENTINEL = "https://" + BLANK_HOST + "/b.ts?i=";

    private static volatile byte[] blankTs;

    /** 90 kHz ticks per second — MPEG-TS timestamp unit. */
    private static final long TS_HZ = 90000L;

    /** Cache of PTS-shifted blank copies, keyed by offset ticks (bounded). */
    private static final Map<Long, byte[]> shiftCache = new ConcurrentHashMap<>();

    private static byte[] blankTsBytes() {
        byte[] b = blankTs;
        if (b == null) {
            b = Base64.decode(BlankSegment.TS_BASE64, Base64.DEFAULT);
            blankTs = b;
        }
        return b;
    }

    /** Blank TS with all timestamps shifted forward by {@code ticks} (cached). */
    private static byte[] shiftedBlank(long ticks) {
        if (ticks == 0) return blankTsBytes();
        byte[] c = shiftCache.get(ticks);
        if (c != null) return c;
        byte[] s = BlankSegment.shift(blankTsBytes(), ticks);
        if (shiftCache.size() < 512) shiftCache.put(ticks, s);
        return s;
    }

    /** Parses the {@code o=<ticks>} PTS offset from a blank sentinel URL. */
    private static long parseOffset(String url) {
        int k = url.indexOf("o=");
        if (k < 0) return 0;
        long v = 0;
        for (int e = k + 2; e < url.length(); e++) {
            char ch = url.charAt(e);
            if (ch < '0' || ch > '9') break;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    /** Only the video-weaver media playlists carry stitched ads. */
    private static boolean isWeaverPlaylist(String url) {
        return url != null && url.contains(".playlist.ttvnw.net/") && url.contains("/playlist/");
    }

    public static WebViewClient wrapClient(final WebViewClient original) {
        Log.d(TAG, "wrapClient — Twitch ATV HLS scrubber active");
        return new WrappedClient(original);
    }

    /**
     * Injects a small, idempotent stall-recovery monitor into the player page.
     * Substituting black segments for ads can leave hls.js briefly buffering; if
     * it stalls long enough it fatal-errors ("Oh bummer") instead of resuming.
     * This watches the &lt;video&gt; and, only when it is genuinely stuck (time not
     * advancing while not paused), nudges it (seek toward the buffered edge +
     * play) so it recovers before hitting the fatal buffer-stall — the same idea
     * as TwitchAdSolutions' PlayerBufferingFix, kept minimal and player-agnostic.
     * No-op during normal playback (time advances → never nudges).
     */
    private static void injectPlaybackFix(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(PLAYBACK_FIX_JS, null);
        } catch (Throwable t) {
            Log.e(TAG, "playback-fix injection failed", t);
        }
    }

    private static final String PLAYBACK_FIX_JS =
        "(function(){try{"
        + "if(window.__morphePlaybackFix)return;window.__morphePlaybackFix=true;"
        + "var last=-1,same=0;"
        + "setInterval(function(){try{"
        + "var v=document.querySelector('video');"
        + "if(!v||v.paused||v.ended||v.seeking){same=0;return;}"
        + "var t=v.currentTime;"
        + "if(Math.abs(t-last)<0.02){"
        + "  same++;"
        + "  if(same>=2){"
        + "    try{var b=v.buffered;if(b&&b.length){var e=b.end(b.length-1);"
        + "      if(e>t+0.1){v.currentTime=Math.min(e-0.05,t+0.5);}else{v.currentTime=t+0.05;}"
        + "    }else{v.currentTime=t+0.05;}}catch(e){}"
        + "    try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}"
        + "    same=0;"
        + "  }"
        + "}else{same=0;}"
        + "last=t;"
        + "}catch(e){}},700);"
        + "}catch(e){}})();";

    /**
     * Injects the "focus break" ad overlay controller into the player page. This
     * draws a full-screen, fwocus-style ambient splash (animated background,
     * greeting, live clock, drifting focus quotes, optional soft ambient audio)
     * ON TOP of the player during an ad window — instead of leaving the viewer on
     * the plain black blank segment. It is purely cosmetic: the black+silent TS
     * still plays underneath so the ad completes server-side and content resumes;
     * the overlay only covers the black wait with something pleasant.
     *
     * <p>The overlay is show/hide-driven from native ad detection (see
     * {@link #setOverlay}): {@code scrubPlaylist} already knows, per weaver-playlist
     * poll, whether a {@code twitch-stitched-ad} is present, so we toggle the
     * overlay on that exact signal rather than any fragile in-page timing. Defines
     * {@code window.__morpheAdOverlay(bool)} (and {@code window.__morpheOverlayTheme(name)}
     * for manual theming). Idempotent; a 120 s watchdog auto-hides if an ad-end
     * poll is ever missed, so the overlay can never get stuck over live content.
     */
    private static void injectAdOverlay(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(AD_OVERLAY_JS, null);
        } catch (Throwable t) {
            Log.e(TAG, "ad-overlay injection failed", t);
        }
    }

    /** Toggle the in-page focus-break overlay from a background thread (UI-thread hop). */
    private static void setOverlay(final WebView view, final boolean show) {
        if (view == null) return;
        final String js = "if(window.__morpheAdOverlay)window.__morpheAdOverlay(" + show + ");";
        try {
            view.post(new Runnable() {
                @Override public void run() {
                    try {
                        view.evaluateJavascript(js, null);
                    } catch (Throwable t) {
                        Log.e(TAG, "ad-overlay toggle failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "ad-overlay toggle post failed", t);
        }
    }

    // Self-contained "focus break" overlay. Single-quoted JS only (no Java-string
    // escaping); themes/quotes are pure CSS + generated DOM so nothing is fetched.
    private static final String AD_OVERLAY_JS = String.join("\n",
        "(function(){try{",
        "if(window.__morpheAdOverlay)return;",
        "var musicWanted=false;try{musicWanted=(localStorage.getItem('morpheMusic')==='1');}catch(e){}", // persisted music toggle
        "var THEMES=[",
        " {name:'Cafe Rain',bg:'linear-gradient(160deg,#243b4a,#3a5566 42%,#c9975b)',rain:true,accent:'#ffd9a0'},",
        " {name:'Midnight',bg:'radial-gradient(120% 120% at 30% 20%,#2a2f6b,#12132b 60%,#05060f)',rain:false,accent:'#9db4ff'},",
        " {name:'Sunset Lofi',bg:'linear-gradient(160deg,#3a2350,#a24d7a 55%,#f0a860)',rain:false,accent:'#ffd0e0'},",
        " {name:'Forest',bg:'linear-gradient(160deg,#12241a,#254b34 46%,#7fae6b)',rain:true,accent:'#c8f0b0'}",
        "];",
        "var QUOTES=[",
        " 'Clarity comes from action, not thought.',",
        " 'The stream will be right back.',",
        " 'Breathe. Stretch. Sip your coffee.',",
        " 'Good things are worth a short wait.',",
        " 'Stay present.'",
        "];",
        "var themeIdx=-1;",
        "var root,bg,brand,quoteEl,greetEl,subEl,clockEl,hintEl,rainWrap,dock;",
        "var clockTimer=null,quoteTimer=null,watchdog=null,audio=null;",
        "var dockCtrls=[],ctlIdx=0,keyHandler=null;",
        "function el(tag,css){var e=document.createElement(tag);if(css)e.style.cssText=css;return e;}",
        "function injectKeyframes(){if(document.getElementById('mphe-kf'))return;var st=document.createElement('style');st.id='mphe-kf';",
        " st.textContent='@keyframes mpheRain{to{transform:translateY(118vh);}}@keyframes mpheSpin{to{transform:rotate(360deg);}}';",
        " (document.head||document.documentElement).appendChild(st);}",
        "function build(){",
        " root=el('div','position:fixed;inset:0;z-index:2147483600;opacity:0;transition:opacity .6s ease;pointer-events:none;overflow:hidden;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#fff;');",
        " bg=el('div','position:absolute;inset:0;transition:background 1s ease;');root.appendChild(bg);",
        " root.appendChild(el('div','position:absolute;inset:0;background:radial-gradient(120% 120% at 50% 40%,transparent 42%,rgba(0,0,0,.55) 100%);'));",
        " rainWrap=el('div','position:absolute;inset:0;overflow:hidden;');root.appendChild(rainWrap);",
        " brand=el('div','position:absolute;top:5%;left:4%;font-size:2vw;font-weight:700;letter-spacing:.5px;text-shadow:0 2px 12px rgba(0,0,0,.6);');brand.textContent='morphe';root.appendChild(brand);",
        " quoteEl=el('div','position:absolute;top:6%;right:4%;max-width:34%;text-align:right;font-size:1.5vw;font-weight:600;font-style:italic;opacity:.92;transition:opacity .4s ease;text-shadow:0 2px 12px rgba(0,0,0,.6);');root.appendChild(quoteEl);",
        " var c=el('div','position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;width:100%;');",
        " greetEl=el('div','font-size:2.4vw;font-weight:700;text-shadow:0 2px 16px rgba(0,0,0,.6);');",
        " subEl=el('div','font-size:1.4vw;font-weight:500;opacity:.85;margin-top:.4vw;text-shadow:0 2px 12px rgba(0,0,0,.6);');",
        " clockEl=el('div','font-size:9vw;font-weight:800;line-height:1;margin-top:2vw;text-shadow:0 6px 40px rgba(0,0,0,.55);');",
        " c.appendChild(greetEl);c.appendChild(subEl);c.appendChild(clockEl);root.appendChild(c);",
        " hintEl=el('div','position:absolute;bottom:6%;left:50%;transform:translateX(-50%);font-size:1.2vw;font-weight:600;opacity:.85;display:flex;align-items:center;gap:.8vw;text-shadow:0 2px 12px rgba(0,0,0,.6);');root.appendChild(hintEl);",
        " buildDock();root.appendChild(dock);",
        " (document.body||document.documentElement).appendChild(root);injectKeyframes();setHint();",
        "}",
        // D-pad-navigable control dock: scene chips + a music toggle. pointer-events
        // are enabled only on the dock (root stays none) so clicks/taps also work.
        "function buildDock(){dock=el('div','position:absolute;bottom:5%;left:50%;transform:translateX(-50%);display:flex;gap:1vw;align-items:center;padding:1.1vh 1.2vw;background:rgba(20,18,30,.55);border:1px solid rgba(255,255,255,.14);border-radius:14px;pointer-events:auto;');",
        " dockCtrls=[];",
        " for(var i=0;i<THEMES.length;i++){(function(i){var b=el('div','padding:1vh 1.2vw;border-radius:10px;font-size:1.1vw;font-weight:600;border:2px solid transparent;background:rgba(255,255,255,.06);cursor:pointer;white-space:nowrap;');b.textContent=THEMES[i].name;b.onclick=function(){ctlIdx=i;chooseScene(i);};dock.appendChild(b);dockCtrls.push({el:b,act:function(){chooseScene(i);}});})(i);}",
        " var mb=el('div','padding:1vh 1.2vw;border-radius:10px;font-size:1.1vw;font-weight:600;border:2px solid transparent;background:rgba(255,255,255,.06);cursor:pointer;white-space:nowrap;');mb.onclick=function(){ctlIdx=dockCtrls.length-1;toggleMusic();};dock.appendChild(mb);dockCtrls.push({el:mb,act:toggleMusic,music:true});",
        " musicLabel();highlightDock();}",
        "function musicLabel(){var c=dockCtrls[dockCtrls.length-1];if(c)c.el.textContent=musicWanted?'Music: on':'Music: off';}",
        "function highlightDock(){for(var i=0;i<dockCtrls.length;i++){var sel=(i===ctlIdx);dockCtrls[i].el.style.borderColor=sel?'#fff':'transparent';dockCtrls[i].el.style.background=sel?'rgba(255,255,255,.2)':'rgba(255,255,255,.06)';}}",
        "function chooseScene(i){themeIdx=i;applyTheme(i);highlightDock();try{localStorage.setItem('morpheScene',i);}catch(e){}}",
        "function toggleMusic(){musicWanted=!musicWanted;musicLabel();highlightDock();if(musicWanted)startAudio();else stopAudio();try{localStorage.setItem('morpheMusic',musicWanted?'1':'0');}catch(e){}}",
        // TV WebViews deliver the D-pad as JS key events (arrows 37-40, center/Enter
        // 13/23). Capture is added only while the overlay is shown and removed on
        // hide, and we stop only the keys we consume — Back/others pass through to
        // the app, so the viewer is never trapped.
        "function onKey(e){var k=e.keyCode;",
        " if(k===37){ctlIdx=(ctlIdx+dockCtrls.length-1)%dockCtrls.length;highlightDock();e.preventDefault();e.stopPropagation();}",
        " else if(k===39){ctlIdx=(ctlIdx+1)%dockCtrls.length;highlightDock();e.preventDefault();e.stopPropagation();}",
        " else if(k===13||k===23||k===66){if(dockCtrls[ctlIdx])dockCtrls[ctlIdx].act();e.preventDefault();e.stopPropagation();}}",
        "function buildRain(on){if(!rainWrap)return;rainWrap.innerHTML='';if(!on)return;",
        " for(var i=0;i<90;i++){var left=Math.random()*100,dur=0.5+Math.random()*0.7,delay=Math.random()*2,h=6+Math.random()*10;",
        "  rainWrap.appendChild(el('span','position:absolute;top:-14%;left:'+left+'%;width:1.5px;height:'+h+'vh;background:linear-gradient(transparent,rgba(255,255,255,.35));animation:mpheRain '+dur+'s linear '+delay+'s infinite;'));}}",
        "function applyTheme(i){var t=THEMES[i];if(!t)return;bg.style.background=t.bg;brand.style.color=t.accent;buildRain(t.rain);}",
        "function setHint(){hintEl.innerHTML='';hintEl.appendChild(el('span','width:1.1vw;height:1.1vw;border:2px solid rgba(255,255,255,.35);border-top-color:#fff;border-radius:50%;display:inline-block;animation:mpheSpin 1s linear infinite;'));var tx=el('span');tx.textContent='Ad break — back to the stream shortly';hintEl.appendChild(tx);}",
        "function fmt(){var d=new Date(),h=d.getHours(),m=d.getMinutes(),ap=h<12?'AM':'PM',hh=h%12;if(hh===0)hh=12;return hh+':'+(m<10?'0'+m:m)+' '+ap;}",
        "function greet(){var h=new Date().getHours();if(h<12)return['Good morning!','Morning focus time.'];if(h<18)return['Good afternoon!','Afternoon focus time.'];return['Good evening!','Evening wind-down.'];}",
        "function startTimers(){var g=greet();greetEl.textContent=g[0];subEl.textContent=g[1];clockEl.textContent=fmt();",
        " if(clockTimer)clearInterval(clockTimer);clockTimer=setInterval(function(){clockEl.textContent=fmt();},1000);",
        " var qi=Math.floor(Math.random()*QUOTES.length);quoteEl.textContent=QUOTES[qi];",
        " if(quoteTimer)clearInterval(quoteTimer);quoteTimer=setInterval(function(){qi=(qi+1)%QUOTES.length;quoteEl.style.opacity='0';setTimeout(function(){quoteEl.textContent=QUOTES[qi];quoteEl.style.opacity='.92';},400);},9000);startAudio();}",
        "function stopTimers(){if(clockTimer){clearInterval(clockTimer);clockTimer=null;}if(quoteTimer){clearInterval(quoteTimer);quoteTimer=null;}stopAudio();}",
        "function startAudio(){if(!musicWanted)return;try{if(!audio){var AC=window.AudioContext||window.webkitAudioContext;if(!AC)return;var ctx=new AC();var g=ctx.createGain();g.gain.value=0.0;g.connect(ctx.destination);",
        "  var o1=ctx.createOscillator();o1.type='sine';o1.frequency.value=110;var o2=ctx.createOscillator();o2.type='sine';o2.frequency.value=110.5;",
        "  var o3=ctx.createOscillator();o3.type='sine';o3.frequency.value=165;var g3=ctx.createGain();g3.gain.value=0.5;o3.connect(g3);g3.connect(g);",
        "  o1.connect(g);o2.connect(g);o1.start();o2.start();o3.start();audio={ctx:ctx,g:g};}",
        "  if(audio.ctx.resume)audio.ctx.resume();audio.g.gain.setTargetAtTime(0.04,audio.ctx.currentTime,1.5);}catch(e){}}",
        "function stopAudio(){try{if(audio)audio.g.gain.setTargetAtTime(0.0,audio.ctx.currentTime,0.8);}catch(e){}}",
        "window.__morpheOverlayTheme=function(n){try{for(var i=0;i<THEMES.length;i++){if(THEMES[i].name.toLowerCase().indexOf((''+n).toLowerCase())>=0){themeIdx=i;if(root)applyTheme(i);return THEMES[i].name;}}}catch(e){}return null;};",
        "window.__morpheAdOverlay=function(on){try{",
        " if(on){if(!root)build();",
        "  var saved=-1;try{var sv=localStorage.getItem('morpheScene');if(sv!==null)saved=parseInt(sv,10);}catch(e){}",
        "  if(saved>=0&&saved<THEMES.length){themeIdx=saved;}else{themeIdx=(themeIdx+1)%THEMES.length;}", // saved pick wins; else rotate
        "  ctlIdx=themeIdx;applyTheme(themeIdx);highlightDock();startTimers();",
        "  requestAnimationFrame(function(){root.style.opacity='1';});",
        "  if(keyHandler)window.removeEventListener('keydown',keyHandler,true);keyHandler=onKey;window.addEventListener('keydown',keyHandler,true);",
        "  if(watchdog)clearTimeout(watchdog);watchdog=setTimeout(function(){window.__morpheAdOverlay(false);},120000);",
        " }else{if(!root)return;root.style.opacity='0';stopTimers();",
        "  if(keyHandler){window.removeEventListener('keydown',keyHandler,true);keyHandler=null;}",
        "  if(watchdog){clearTimeout(watchdog);watchdog=null;}}",
        "}catch(e){}};",
        "}catch(e){}})();");

    private static final class WrappedClient extends WebViewClient {
        private final WebViewClient original;

        WrappedClient(WebViewClient original) {
            this.original = original;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                if (url.contains(BLANK_HOST)) {
                    return blankTsResponse(url);
                }
                if (isWeaverPlaylist(url)) {
                    WebResourceResponse scrubbed = scrubPlaylist(view, url);
                    if (scrubbed != null) return scrubbed;
                }
            } catch (Throwable t) {
                Log.e(TAG, "shouldInterceptRequest error", t);
            }
            return original.shouldInterceptRequest(view, request);
        }

        // --- delegate everything else to the app's real client ---------------
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            original.onPageStarted(view, url, favicon);
            injectPlaybackFix(view);
            injectAdOverlay(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            original.onPageFinished(view, url);
            injectPlaybackFix(view);
            injectAdOverlay(view);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return original.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            original.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            original.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                       android.net.http.SslError error) {
            original.onReceivedSslError(view, handler, error);
        }
    }

    private static WebResourceResponse scrubPlaylist(WebView view, String url) throws Exception {
        String body = httpGet(url);
        if (body == null) return null; // couldn't fetch — let the WebView load it
        if (!body.contains("twitch-stitched-ad")) {
            // No ad this poll — content resumed: hide the focus-break overlay and
            // serve the content unchanged (single fetch).
            setOverlay(view, false);
            return m3u8Response(body);
        }
        // Ad present this poll — show the focus-break overlay over the blanked video.
        setOverlay(view, true);
        String rewritten = blankAdSegments(body);
        Log.i(TAG, "blanked stitched-ad segments in weaver playlist");
        return m3u8Response(rewritten);
    }

    /**
     * True for the client-side ad-pod {@code #EXT-X-DATERANGE} lines — the ones
     * carrying {@code X-TV-TWITCH-AD-*} / {@code X-TTV-MAF-AD-*} attributes, or the
     * {@code CLASS="twitch-stitched-ad"} marker. On-device diagnosis (2026-08-14)
     * showed a real ATV preroll ships a full client-side pod inline in the weaver
     * playlist (ROLL-TYPE, POD-LENGTH, CREATIVE-ID, RADS-TOKEN, ad-decision URL,
     * verification/tracking beacons). We already blank the stitched VIDEO segments,
     * but Twitch's in-WebView ad coordinator still reads these tags and runs the
     * "Ad · 1 of 3" pod overlay/gate — so ads still "play" (a black wait). Stripping
     * these metadata lines removes the signal the coordinator acts on. The benign
     * dateranges ({@code twitch-stream-source}, {@code twitch-trigger},
     * {@code twitch-session}, {@code timestamp}) are left untouched.
     */
    private static boolean isAdDaterange(String trimmed) {
        return trimmed.startsWith("#EXT-X-DATERANGE")
            && (trimmed.contains("X-TV-TWITCH-AD")
                || trimmed.contains("X-TTV-MAF-AD")
                || trimmed.contains("twitch-stitched-ad"));
    }

    /**
     * Keeps the playlist intact (headers, benign dateranges, per-segment tags) but
     * (a) rewrites each ad segment's URI (the URI following an #EXTINF whose title is
     * not "live") to a per-segment sentinel we serve a black TS for — keeping the
     * timeline advancing so the ad completes server-side and content resumes — and
     * (b) strips the client-side ad-pod dateranges ({@link #isAdDaterange}) so the
     * WebView's ad coordinator has no {@code X-TV-TWITCH-AD-*} pod to gate on.
     */
    static String blankAdSegments(String playlist) {
        String[] lines = playlist.split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length());
        boolean prevExtinfIsAd = false;
        double curDur = 0;      // duration (s) of the pending #EXTINF
        long adTicks = 0;       // cumulative PTS offset (90 kHz) since the ad run began

        for (String line : lines) {
            String t = line.trim();
            if (isAdDaterange(t)) {
                // Drop the client-side ad-pod signal entirely (see isAdDaterange).
                continue;
            }
            if (t.startsWith("#EXT-X-DISCONTINUITY")) {
                // Break boundary — Twitch stitches the pod as one continuous
                // timeline after this tag, so restart the blank timeline here.
                adTicks = 0;
                out.append(line).append('\n');
            } else if (t.startsWith("#EXTINF")) {
                int colon = t.indexOf(':');
                int comma = t.indexOf(',');
                String title = comma >= 0 ? t.substring(comma + 1).trim() : "";
                prevExtinfIsAd = !title.equals("live");
                curDur = 0;
                if (colon >= 0 && comma > colon) {
                    try {
                        curDur = Double.parseDouble(t.substring(colon + 1, comma).trim());
                    } catch (NumberFormatException ignored) { }
                }
                out.append(line).append('\n');
            } else if (!t.isEmpty() && !t.startsWith("#")) {
                // URI line.
                if (prevExtinfIsAd) {
                    // Per-segment sentinel: hash keeps distinct segments distinct;
                    // o=<ticks> is the segment's start on the pod timeline, so each
                    // served blank is PTS-shifted to continue from the previous one
                    // (monotonic → hls.js plays the pod through without a resume stall).
                    out.append(BLANK_SENTINEL)
                       .append(Integer.toHexString(t.hashCode()))
                       .append("&o=").append(adTicks)
                       .append('\n');
                    adTicks += Math.round(curDur * TS_HZ);
                } else {
                    out.append(line).append('\n');
                    adTicks = 0; // content segment ends the ad run
                }
                prevExtinfIsAd = false;
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static WebResourceResponse blankTsResponse(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "*");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(
                "video/mp2t", null, 200, "OK", headers,
                new ByteArrayInputStream(shiftedBlank(parseOffset(url))));
    }

    private static WebResourceResponse m3u8Response(String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "*");
        headers.put("Cache-Control", "no-cache");
        InputStream data = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new WebResourceResponse(
                "application/vnd.apple.mpegurl", "utf-8", 200, "OK", headers, data);
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "httpGet failed: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
