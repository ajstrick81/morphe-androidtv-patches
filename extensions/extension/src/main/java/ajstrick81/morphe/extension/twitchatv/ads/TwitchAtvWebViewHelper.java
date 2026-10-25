package ajstrick81.morphe.extension.twitchatv.ads;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Base64;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
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

    // ── playback-speed control (opt-in via TwitchSpeedTogglePatch) ────────────
    // The player is hls.js over an HTML5 <video>, so the only thing needed is to
    // set video.playbackRate. The native key interceptor sits in front of the
    // Activity, so it sees the OK press whichever view ends up handling it, and
    // drives the speed list drawn into the page by SPEED_TOGGLE_JS. The interval
    // re-applies the chosen rate after hls.js recreates the <video> element.
    //
    // Triggers: the blue colour key (KEYCODE_PROG_BLUE) opens and closes the
    // list; long-press OK (KEYCODE_DPAD_CENTER) stays as the fallback for remotes
    // that do not send colour keys. Transport keys are deliberately not
    // hijacked: remotes that have them use them for seeking, and current
    // TCL/Google TV remotes do not send them at all.
    private static volatile boolean speedEnabled;
    private static volatile WeakReference<WebView> speedView;

    /**
     * Repeats of DPAD_CENTER before the press counts as a long press. The
     * framework only starts repeating after ViewConfiguration.getLongPressTimeout()
     * (~500 ms), so 1 is the first repeat and therefore the earliest reliable
     * signal — no extra delay is added on top of the system timeout.
     */
    private static final int OK_LONG_PRESS_REPEATS = 1;

    /**
     * Latches one speed toggle per physical OK press. Both the Activity fallback
     * and the WebView OnKeyListener can see the same event; without the latch a
     * single hold would toggle twice and land back on 1x.
     */
    private static volatile boolean okLongPressFired;

    /**
     * Latches one blue-key toggle per physical press. Same reason as
     * {@link #okLongPressFired}: the Window.Callback, the Activity fallback and
     * the WebView OnKeyListener can all see the same event. Without the latch the
     * second sighting would open the menu and immediately close it again.
     */
    private static volatile boolean blueKeyFired;

    // ── on-screen speed menu (opened by blue key or long-press OK) ───────────
    // The Twitch player UI is their own React app; its quality menu cannot be
    // extended from here. Instead the menu is drawn into the page by our JS and
    // driven entirely from the native key interceptor, so D-pad navigation does
    // not depend on Twitch's focus handling.
    private static final float[] SPEED_RATES =
            {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private static final String SPEED_RATES_JS = "[0.5,0.75,1.0,1.25,1.5,1.75,2.0]";
    private static final int SPEED_MENU_TIMEOUT_MS = 6000;

    private static volatile boolean speedMenuOpen;
    private static volatile int speedMenuIndex = 2; // 1.0x
    private static volatile int speedMenuToken;
    private static volatile float currentRate = 1.0f;

    /**
     * Key whose press was consumed by the menu and whose release must therefore
     * be swallowed too. Needed when the menu closes on key-down (OK applies, Back
     * cancels): the Activity would otherwise act on the matching key-up — for
     * Back that means leaving the player.
     */
    private static volatile int swallowNextUpKey = -1;

    /** Called from injected smali by the opt-in speed patch. */
    public static void setSpeedEnabled() {
        speedEnabled = true;
        Log.i(TAG, "playback speed controls enabled");
    }

    private static void installSpeedToggle(WebView view) {
        if (!speedEnabled || view == null) return;
        speedView = new WeakReference<>(view);
        speedMenuOpen = false;
        try {
            // Off unless explicitly asked for over ADB:
            //   adb shell settings put global morphe_webview_debug 1
            // Debugging hooks cost a little on every page and were keeping the
            // WebView busy during playback, so they are only for DOM inspection
            // with chrome://inspect when Twitch's player markup has to be re-checked.
            boolean debug = "1".equals(android.provider.Settings.Global.getString(
                    view.getContext().getContentResolver(), "morphe_webview_debug"));
            if (debug) {
                WebView.setWebContentsDebuggingEnabled(true);
                Log.i(TAG, "WebView debugging enabled via morphe_webview_debug");
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read morphe_webview_debug", t);
        }
        try {
            view.setOnKeyListener(new SpeedKeyListener(view));
            view.evaluateJavascript(SPEED_TOGGLE_JS, null);
            installWindowKeyInterceptor(view);
        } catch (Throwable t) {
            Log.e(TAG, "speed toggle injection failed", t);
        }
    }

    /**
     * Installs the key interceptor on the Activity's Window.Callback.
     *
     * The WebView OnKeyListener alone is not reliable: D-pad events only reach it
     * while that exact view holds focus, and Twitch's player moves focus between
     * its own overlay views. A Window.Callback wrapper sits in front of the whole
     * Activity (DecorView.dispatchKeyEvent calls the callback before anything is
     * dispatched to the view tree), so OK presses are seen no matter which view
     * ends up handling them. Everything except the speed triggers is delegated
     * unchanged.
     */
    private static void installWindowKeyInterceptor(WebView view) {
        try {
            Activity activity = findActivity(view.getContext());
            if (activity == null) {
                Log.w(TAG, "no activity for key interceptor; WebView listener only");
                return;
            }
            Window window = activity.getWindow();
            if (window == null) return;
            Window.Callback current = window.getCallback();
            if (current == null || current instanceof SpeedWindowCallback) return;
            window.setCallback(new SpeedWindowCallback(current, view));
            Log.i(TAG, "window key interceptor installed on " + activity.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "window key interceptor failed", t);
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null || base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    /**
     * Named (not anonymous) listener: the extension dex is merged raw by
     * extendWith(), and ART's verifier has rejected anonymous framework
     * subclasses in this helper before (see proguard-rules.pro).
     *
     * Note: this replaces any OnKeyListener the host set on the WebView, but it
     * returns false for every key except the speed triggers, so normal key
     * handling still falls through.
     */
    private static final class SpeedKeyListener implements View.OnKeyListener {
        private final WebView view;

        SpeedKeyListener(WebView view) {
            this.view = view;
        }

        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            return dispatchSpeedKey(view, keyCode, event);
        }
    }

    /**
     * Window.Callback delegate that intercepts the speed triggers first and
     * forwards everything else verbatim. Named (not anonymous) for the same ART
     * verifier reason as {@link SpeedKeyListener}.
     */
    private static final class SpeedWindowCallback implements Window.Callback {
        private final Window.Callback delegate;
        private final WebView view;

        SpeedWindowCallback(Window.Callback delegate, WebView view) {
            this.delegate = delegate;
            this.view = view;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && dispatchSpeedKey(view, event.getKeyCode(), event)) return true;
            return delegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return delegate.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return delegate.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            delegate.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            delegate.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            delegate.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            delegate.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            delegate.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            delegate.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return delegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return delegate.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            delegate.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            delegate.onActionModeFinished(mode);
        }
    }

    /**
     * Activity-level fallback, called from injected smali on StarshotActivity's
     * dispatchKeyEvent/onKeyDown. Covers the case where the Activity consumes
     * media keys before the WebView gains focus.
     *
     * @return true when the key was consumed by a speed change.
     */
    public static boolean onSpeedKeyEvent(int keyCode, KeyEvent event) {
        WebView view = speedView == null ? null : speedView.get();
        return view != null && dispatchSpeedKey(view, keyCode, event);
    }

    /** Non-consuming fallback for methods with no scratch register. */
    public static void onSpeedKeyEventVoid(int keyCode, KeyEvent event) {
        WebView view = speedView == null ? null : speedView.get();
        if (view != null) dispatchSpeedKey(view, keyCode, event);
    }

    /** dispatchKeyEvent(KeyEvent) overload — key code comes from the event. */
    public static boolean onSpeedKeyEvent(KeyEvent event) {
        return event != null && onSpeedKeyEvent(event.getKeyCode(), event);
    }

    /** Non-consuming dispatchKeyEvent(KeyEvent) overload. */
    public static void onSpeedKeyEventVoid(KeyEvent event) {
        if (event != null) onSpeedKeyEventVoid(event.getKeyCode(), event);
    }

    /**
     * @return true when the key was consumed by a speed change.
     *
     * KEYCODE_PROG_BLUE is the primary trigger: it is unused by the Twitch
     * player, so a single press opens the list and a second press closes it.
     * DPAD_CENTER remains the fallback for remotes without colour keys. Its
     * initial press must reach the page unchanged (that is the normal OK click),
     * so only the repeats are consumed, and the matching ACTION_UP is swallowed
     * once the menu fired so the page does not also treat the hold as a click
     * (which would pause the video). Every other key, including the media
     * transport keys, is passed through untouched.
     */
    private static boolean dispatchSpeedKey(WebView view, int keyCode, KeyEvent event) {
        if (view == null || event == null) return false;
        try {
            if (event.getAction() == KeyEvent.ACTION_UP && keyCode == swallowNextUpKey) {
                swallowNextUpKey = -1;
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                return dispatchBlueKey(view, event);
            }
            if (speedMenuOpen) return handleSpeedMenuKey(view, keyCode, event);
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                return dispatchOkLongPress(view, event);
            }
        } catch (Throwable t) {
            Log.e(TAG, "speed key handling failed", t);
        }
        return false;
    }

    /**
     * Blue colour key: one press opens the speed list, another closes it.
     *
     * Colour keys are standard Android key codes (KEYCODE_PROG_RED..BLUE) but
     * not every remote sends them. They are otherwise unused by Twitch's player,
     * which is why this does not take anything away from the app. Both the press
     * and the release are consumed so no other handler sees a stray key event.
     */
    private static boolean dispatchBlueKey(WebView view, KeyEvent event) {
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            boolean consumed = blueKeyFired;
            blueKeyFired = false;
            if (consumed) Log.i(TAG, "blue key: swallowing key-up");
            return consumed;
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) return blueKeyFired;
        if (blueKeyFired) return true; // already handled for this press
        blueKeyFired = true;
        if (speedMenuOpen) {
            Log.i(TAG, "blue key: closing speed menu");
            hideSpeedMenu(view);
        } else {
            Log.i(TAG, "blue key: opening speed menu");
            showSpeedMenu(view, false);
        }
        return true;
    }

    private static boolean dispatchOkLongPress(WebView view, KeyEvent event) {
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            boolean consumed = okLongPressFired;
            okLongPressFired = false;
            if (consumed) Log.i(TAG, "ok long-press: swallowing key-up");
            return consumed;
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        final int repeat = event.getRepeatCount();
        if (repeat == 0) {
            // A fresh press always starts clean, even if a previous ACTION_UP was
            // swallowed by something else and left the latch set. Remember the
            // paused state before the app sees the press, so a toggle can undo the
            // play/pause that the app itself triggers off the same press.
            okLongPressFired = false;
            view.evaluateJavascript("window.__morpheSpeedArm&&window.__morpheSpeedArm();", null);
            return false;
        }
        if (repeat < OK_LONG_PRESS_REPEATS) return false;
        if (okLongPressFired) return true; // already handled for this press
        okLongPressFired = true;
        Log.i(TAG, "ok long-press: opening speed menu");
        showSpeedMenu(view, true);
        return true;
    }

    // ── speed menu ────────────────────────────────────────────────────────────

    private static void showSpeedMenu(WebView view, boolean undoOkPress) {
        speedMenuOpen = true;
        speedMenuIndex = nearestRateIndex(currentRate);
        final int token = ++speedMenuToken;
        if (undoOkPress) {
            evaluate(view, "window.__morpheSpeedUndoPress&&window.__morpheSpeedUndoPress();");
        } else {
            // Opened by the blue key, not by an OK press. Drop any arm left over
            // from an earlier OK press so choosing a rate cannot undo a
            // play/pause that was not caused by this menu.
            evaluate(view,
                    "window.__morpheSpeedArmedAt=0;window.__morpheSpeedWasPaused=null;");
        }
        evaluate(view, "window.__morpheSpeedMenu&&window.__morpheSpeedMenu(true,"
                + speedMenuIndex + ",'" + SPEED_RATES_JS + "');");
        view.postDelayed(new MenuTimeout(view, token), SPEED_MENU_TIMEOUT_MS);
    }

    private static void hideSpeedMenu(WebView view) {
        if (!speedMenuOpen) return;
        speedMenuOpen = false;
        speedMenuToken++;
        evaluate(view, "window.__morpheSpeedMenu&&window.__morpheSpeedMenu(false,0,'');");
    }

    private static void moveSpeedSelection(WebView view, int delta) {
        int next = speedMenuIndex + delta;
        if (next < 0) next = 0;
        if (next >= SPEED_RATES.length) next = SPEED_RATES.length - 1;
        if (next == speedMenuIndex) return;
        speedMenuIndex = next;
        evaluate(view, "window.__morpheSpeedMenu&&window.__morpheSpeedMenu(true,"
                + speedMenuIndex + ",'" + SPEED_RATES_JS + "');");
    }

    private static void applySpeedSelection(WebView view) {
        float rate = SPEED_RATES[speedMenuIndex];
        Log.i(TAG, "speed menu: applying " + rate + "x");
        currentRate = rate;
        evaluate(view, "window.__morpheSpeedSet&&window.__morpheSpeedSet(" + rate + ");");
        hideSpeedMenu(view);
    }

    private static int nearestRateIndex(float rate) {
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_RATES.length; i++) {
            float delta = Math.abs(SPEED_RATES[i] - rate);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private static boolean isSpeedMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BACK;
    }

    /**
     * While the menu is open every relevant key belongs to it: the page must not
     * also seek, scroll or open its own controls. Navigation is on key-down so a
     * single tap moves exactly one row; the matching key-up is swallowed.
     */
    private static boolean handleSpeedMenuKey(WebView view, int keyCode, KeyEvent event) {
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            if (keyCode == swallowNextUpKey) swallowNextUpKey = -1;
            return isSpeedMenuKey(keyCode);
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        boolean handled = true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveSpeedSelection(view, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveSpeedSelection(view, +1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // The press that opened the menu is still repeating; only a fresh
                // press selects, otherwise holding OK would apply and close at once.
                if (event.getRepeatCount() > 0) break;
                applySpeedSelection(view);
                break;
            case KeyEvent.KEYCODE_BACK:
                Log.i(TAG, "speed menu: closed without change");
                hideSpeedMenu(view);
                break;
            default:
                handled = false;
        }
        if (handled) swallowNextUpKey = keyCode;
        return handled;
    }

    private static void evaluate(WebView view, String js) {
        try {
            view.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.e(TAG, "evaluateJavascript failed", t);
        }
    }

    /** Closes the menu on its own if the remote is put down mid-selection. */
    private static final class MenuTimeout implements Runnable {
        private final WebView view;
        private final int token;

        MenuTimeout(WebView view, int token) {
            this.view = view;
            this.token = token;
        }

        @Override
        public void run() {
            if (speedMenuOpen && speedMenuToken == token) {
                Log.i(TAG, "speed menu: closed by timeout");
                hideSpeedMenu(view);
            }
        }
    }

    private static final String SPEED_TOGGLE_JS =
        "(function(){try{"
        + "if(window.__morpheSpeedInstalled)return;window.__morpheSpeedInstalled=true;"
        + "window.__morpheSpeed=window.__morpheSpeed||1;"
        + "window.__morpheSpeedArmedAt=0;window.__morpheSpeedWasPaused=null;"
        + "function v(){return document.querySelector('video');}"
        // Called from native on the first ACTION_DOWN of an OK press: remembers
        // whether playback was running, so a long press can undo the play/pause
        // the page performs off that same press.
        + "window.__morpheSpeedArm=function(){try{var e=v();"
        + "window.__morpheSpeedWasPaused=e?!!e.paused:null;"
        + "window.__morpheSpeedArmedAt=Date.now();}catch(x){}};"
        + "function undoPressPlayPause(){try{var e=v();"
        + "if(!e||window.__morpheSpeedWasPaused===null)return;"
        + "if(Date.now()-(window.__morpheSpeedArmedAt||0)>2500)return;"
        + "if(!!e.paused===window.__morpheSpeedWasPaused)return;"
        + "if(window.__morpheSpeedWasPaused){try{e.pause();}catch(x){}}"
        + "else{try{var p=e.play();if(p&&p.catch)p.catch(function(){});}catch(x){}}"
        + "}catch(x){}}"
        // Writing playbackRate unconditionally re-runs the media pipeline's rate
        // handling even when nothing changed, so only write when it differs.
        //
        // The rate is never changed automatically. Whatever is picked in the
        // hold-OK list stays applied until it is changed again. A
        // live stream that cannot sustain it will freeze for a moment; that is
        // the stream's buffer, and deciding to slow down is the viewer's call,
        // not the driver's.
        + "function setApplied(r){try{var e=v();if(!e)return;"
        + "if(Math.abs((e.playbackRate||1)-r)>0.001)e.playbackRate=r;}catch(x){}}"
        + "function apply(){try{setApplied(window.__morpheSpeed);}catch(x){}}"
        + "function badge(t){try{var el=document.getElementById('__morpheSpeedBadge');"
        + "if(!el){el=document.createElement('div');el.id='__morpheSpeedBadge';"
        + "el.style.cssText='position:fixed;right:40px;bottom:40px;z-index:2147483647;"
        + "padding:10px 18px;border-radius:8px;background:rgba(0,0,0,.78);color:#fff;"
        + "font:600 26px/1 sans-serif;pointer-events:none;opacity:0;transition:opacity .15s';"
        + "(document.body||document.documentElement).appendChild(el);}"
        + "el.textContent=t;el.style.opacity='1';clearTimeout(window.__morpheSpeedTimer);"
        + "window.__morpheSpeedTimer=setTimeout(function(){el.style.opacity='0';},1200);}catch(x){}}"
        // Speed menu, rendered by us and driven from the native key interceptor.
        + "window.__morpheSpeedSet=function(r){try{var n=parseFloat(r);"
        + "if(!(n>0))return;"
        + "window.__morpheSpeed=n;apply();undoPressPlayPause();"
        + "badge(n+'x');}catch(x){}};"
        + "window.__morpheSpeedUndoPress=undoPressPlayPause;"
        + "window.__morpheSpeedMenu=function(open,idx,rates){try{"
        + "var el=document.getElementById('__morpheSpeedMenu');"
        + "if(!open){if(el)el.style.display='none';return;}"
        + "var list=typeof rates==='string'?JSON.parse(rates):rates;if(!list||!list.length)return;"
        + "if(!el){el=document.createElement('div');el.id='__morpheSpeedMenu';"
        + "el.style.cssText='position:fixed;right:7%;top:50%;transform:translateY(-50%);"
        + "z-index:2147483647;min-width:210px;background:rgba(18,16,22,.95);"
        + "border-radius:16px;padding:18px 22px;color:#fff;pointer-events:none;"
        + "box-shadow:0 12px 48px rgba(0,0,0,.65);font:600 26px/1.7 Roboto,Arial,sans-serif';"
        + "(document.body||document.documentElement).appendChild(el);}"
        + "el.innerHTML='';var head=document.createElement('div');"
        + "head.style.cssText='font-size:16px;letter-spacing:.12em;opacity:.55;margin:0 0 8px 8px';"
        + "head.textContent='PLAYBACK SPEED';el.appendChild(head);"
        + "for(var i=0;i<list.length;i++){var row=document.createElement('div');"
        + "var sel=(i===idx);"
        + "row.style.cssText='padding:6px 12px;border-radius:9px;'+(sel"
        + "?'background:#9147ff;color:#fff;':'color:#d7d5de;');"
        + "row.textContent=list[i]+'x'+(sel?'   \\u25C0':'');el.appendChild(row);}"
        + "el.style.display='block';}catch(x){}};"
        // Slow safety net only: hls.js can swap the <video> element, which resets
        // its rate. Nothing to do at 1x.
        + "setInterval(function(){try{if(document.hidden)return;"
        + "if(window.__morpheSpeed===1)return;apply();}catch(x){}},3000);"
        + "document.addEventListener('loadedmetadata',function(){apply();},true);"
        + "apply();"
        + "}catch(x){}})();";

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
                    WebResourceResponse scrubbed = scrubPlaylist(url);
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
            installSpeedToggle(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            original.onPageFinished(view, url);
            injectPlaybackFix(view);
            installSpeedToggle(view);
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

    private static WebResourceResponse scrubPlaylist(String url) throws Exception {
        String body = httpGet(url);
        if (body == null) return null; // couldn't fetch — let the WebView load it
        if (!body.contains("twitch-stitched-ad")) {
            // No ad this poll — serve the content unchanged (single fetch).
            return m3u8Response(body);
        }
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
