package ajstrick81.morphe.extension.primevideophone.controls;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
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

import java.util.List;

import ajstrick81.morphe.extension.primevideophone.settings.Settings;
import ajstrick81.morphe.extension.primevideophone.settings.SettingsActivityHook;

/**
 * D-pad player controls for the Prime Video *phone* app running on TV hardware.
 *
 * The phone player UI (com.amazon.video.sdk.uiplayerv2) is Jetpack Compose built
 * for touch: nothing is D-pad focusable and PlayerActivity overrides no key
 * handler, so a TV remote's directional keys do nothing during playback. We
 * intercept keys via a wrapping Window.Callback and synthesize touches on the
 * on-screen transport controls (the same thing the "ADB Mouse" workaround does,
 * but in-process — no shell, no permissions).
 *
 * Control positions are computed from **dp offsets + the app's current density**,
 * NOT fixed pixel fractions. This matters because the controls are laid out in dp:
 * when the "Reduce UI zoom" patch changes the app density, the top-bar buttons
 * move, so a fixed fraction would miss them. dp-based math stays correct at any
 * zoom. Offsets were measured once at 320dpi / 1920x1080:
 *   Play/Pause  = screen center
 *   Scrub ±10s  = center ± 86dp
 *   Subtitles   = 109dp from right edge, 31dp from top
 *   Settings    =  28dp from right edge, 31dp from top
 *
 * Mapping:
 *   OK / DPAD_CENTER       -> Play/Pause
 *   OK / DPAD_CENTER (hold) -> open the Morphe settings screen
 *   DPAD_LEFT/RIGHT        -> Scrub back / forward 10s
 *   DPAD_UP                -> Subtitles (top bar)
 *   DPAD_DOWN              -> Settings (top bar)
 * All other keys pass through unchanged (Back still exits).
 *
 * The long-press is the *primary* entry point to the Morphe settings screen, not a
 * convenience: Prime Video 3.0.461 migrated its settings UI to React Native and
 * deleted every PreferenceScreen xml, so the row we inject into the app's own
 * settings list cannot survive there. This route only depends on the player, which
 * no Prime Video release has moved.
 *
 * Controls auto-hide (~2.5s) while playing but stay up while paused. We model
 * that so a single press wakes AND acts (see handleMappedKey). It's a heuristic
 * that self-corrects on the next press if it desyncs.
 *
 * The wrapper is a named class (not anonymous): after extendWith()'s raw dex
 * merge, ART's verifier has rejected anonymous framework-interface subtypes in
 * this repo before (see PeacockWebViewHelper), so keep this shape stable.
 */
public final class DpadPlayerControls {

    private static final String TAG = "MORPHE-DPAD";

    // Control positions as dp offsets (see class doc). Converted to px at runtime.
    private static final float DP_SCRUB_FROM_CENTER = 86f;
    private static final float DP_SUBTITLES_FROM_RIGHT = 109f;
    private static final float DP_SETTINGS_FROM_RIGHT = 28f;
    private static final float DP_TOPBAR_FROM_TOP = 31f;

    // Auto-wake model (see handleMappedKey). Heuristic; self-corrects on desync.
    private static final long VISIBLE_WINDOW_MS = 2500L;
    private static final long WAKE_DELAY_MS = 250L;
    private static long controlsVisibleUntilMs = 0L;
    private static boolean assumedPaused = false;

    /** Hold OK for this long to open the Morphe settings screen. */
    private static final long LONG_PRESS_MS = 600L;

    /** The activity the settings patch hijacks to host our screen. */
    private static final String SETTINGS_HOST_ACTIVITY =
        "com.google.android.gms.common.api.GoogleApiActivity";

    private DpadPlayerControls() {}

    /**
     * Idempotently wraps {@code activity}'s Window.Callback so directional keys
     * drive playback. Safe to call repeatedly (e.g. every onResume).
     *
     * The wrapper installs even when the user has switched the D-pad controls OFF,
     * because it also carries the long-press-to-settings gesture — the only way
     * back to the screen holding that toggle. Skipping the install here would make
     * "D-pad off" a one-way door. When off, the wrapper handles nothing but the
     * long-press and delegates every key untouched.
     */
    public static void install(Activity activity) {
        try {
            if (activity == null) return;
            Window window = activity.getWindow();
            if (window == null) return;
            Window.Callback current = window.getCallback();
            if (current instanceof DpadWindowCallback) {
                // Already wrapped — but the user may have just come back from the
                // settings screen, which is reached FROM this player, so re-read the
                // toggle instead of leaving the wrapper on its construction-time value.
                ((DpadWindowCallback) current).refreshSettings();
                return;
            }
            window.setCallback(new DpadWindowCallback(current, activity));
            Log.i(TAG, "D-pad player controls installed (key mapping "
                + (Settings.isDpadEnabled(activity) ? "enabled" : "disabled")
                + "); wrapped callback = "
                + (current == null ? "null" : current.getClass().getName()));
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    private static boolean isMapped(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlayPause(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Computes the target tap pixel for a key, given the view size and dp->px scale. */
    private static float[] targetPx(int keyCode, int w, int h, float scale) {
        float cx = w / 2f;
        float cy = h / 2f;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return new float[] { cx, cy };
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return new float[] { cx - DP_SCRUB_FROM_CENTER * scale, cy };
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return new float[] { cx + DP_SCRUB_FROM_CENTER * scale, cy };
            case KeyEvent.KEYCODE_DPAD_UP:
                return new float[] { w - DP_SUBTITLES_FROM_RIGHT * scale, DP_TOPBAR_FROM_TOP * scale };
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return new float[] { w - DP_SETTINGS_FROM_RIGHT * scale, DP_TOPBAR_FROM_TOP * scale };
            default:
                return null;
        }
    }

    /** Dispatches a synthetic tap at absolute pixel (x, y) into the decor view NOW. */
    private static void dispatchTapNow(final View decor, final float x, final float y) {
        try {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "dispatchTapNow failed", t);
        }
    }

    private static void injectTapPx(final View decor, final float x, final float y) {
        try {
            decor.post(new Runnable() {
                @Override public void run() { dispatchTapNow(decor, x, y); }
            });
        } catch (Throwable t) {
            Log.e(TAG, "injectTapPx failed", t);
        }
    }

    private static void injectTapPxDelayed(final View decor, final float x, final float y, long delayMs) {
        try {
            decor.postDelayed(new Runnable() {
                @Override public void run() { dispatchTapNow(decor, x, y); }
            }, delayMs);
        } catch (Throwable t) {
            Log.e(TAG, "injectTapPxDelayed failed", t);
        }
    }

    /**
     * Window.Callback that delegates everything to the original callback except
     * key events for mapped directional keys, which it converts to control taps.
     */
    private static final class DpadWindowCallback implements Window.Callback {

        private final Window.Callback delegate;
        private final Activity activity;
        /**
         * Refreshed on every install() rather than read per key, so a key press
         * never blocks on SharedPreferences.
         */
        private boolean dpadEnabled;

        private long centerDownAtMs = 0L;
        private boolean centerLongPressFired = false;

        DpadWindowCallback(Window.Callback delegate, Activity activity) {
            this.delegate = delegate;
            this.activity = activity;
            refreshSettings();
        }

        /** Re-reads the user's toggle. Called on every onResume via install(). */
        void refreshSettings() {
            this.dpadEnabled = Settings.isDpadEnabled(activity);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int keyCode = event.getKeyCode();
            // OK carries two gestures (tap = play/pause, hold = settings), so it
            // needs the press/release state machine even when mapping is off.
            if (isPlayPause(keyCode)) {
                return handleCenterKey(event);
            }
            if (dpadEnabled && isMapped(keyCode)) {
                // Act once on the down stroke; swallow both down and up so the
                // Compose player UI doesn't also react to the raw directional key.
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    handleMappedKey(keyCode);
                }
                return true;
            }
            return delegate.dispatchKeyEvent(event);
        }

        private boolean heldLongEnough() {
            return centerDownAtMs != 0L
                && SystemClock.uptimeMillis() - centerDownAtMs >= LONG_PRESS_MS;
        }

        /**
         * Distinguishes a tap on OK (play/pause) from a hold (open settings).
         *
         * The action is deferred to the release stroke because a tap cannot be told
         * from the start of a hold at press time. Holds are also caught on the
         * repeat strokes, so the screen opens while the key is still down — the
         * feedback a user expects from a long-press — rather than on release.
         *
         * When the key mapping is off, a tap is swallowed rather than passed on.
         * That costs nothing: the player has no D-pad focus and ignores OK anyway.
         */
        private boolean handleCenterKey(KeyEvent event) {
            try {
                int action = event.getAction();
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        centerDownAtMs = SystemClock.uptimeMillis();
                        centerLongPressFired = false;
                    } else if (!centerLongPressFired && heldLongEnough()) {
                        centerLongPressFired = true;
                        openSettings();
                    }
                    return true;
                }
                if (action == KeyEvent.ACTION_UP) {
                    // Fall back to timing the release, in case no repeat stroke
                    // arrived (key repeat is not guaranteed for every remote).
                    if (!centerLongPressFired && heldLongEnough()) {
                        centerLongPressFired = true;
                        openSettings();
                    } else if (!centerLongPressFired && dpadEnabled) {
                        handleMappedKey(event.getKeyCode());
                    }
                    centerDownAtMs = 0L;
                    centerLongPressFired = false;
                }
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "handleCenterKey failed", t);
                return true;
            }
        }

        /** Opens the settings screen the "Morphe settings" patch hosts. */
        private void openSettings() {
            try {
                Intent intent = new Intent();
                // Resolved against the app's own package, so this still points at
                // the right activity if the Clone patch renames the package.
                intent.setClassName(activity, SETTINGS_HOST_ACTIVITY);
                intent.setData(Uri.parse(SettingsActivityHook.SETTINGS_INTENT_DATA));
                activity.startActivity(intent);
                Log.i(TAG, "Morphe settings opened via long-press");
            } catch (Throwable t) {
                Log.e(TAG, "openSettings failed (is the \"Morphe settings\" patch applied?)", t);
            }
        }

        /**
         * Maps a directional key to a control tap, waking the controls first if
         * they've likely auto-hidden so a single press both reveals and acts.
         */
        private void handleMappedKey(int keyCode) {
            try {
                View decor = activity.getWindow().getDecorView();
                int w = decor.getWidth();
                int h = decor.getHeight();
                if (w <= 0 || h <= 0) return;

                // dp->px scale from the app's CURRENT density (which the zoom
                // patch may have changed) so control positions stay correct.
                float scale = activity.getResources().getConfiguration().densityDpi / 160f;
                float[] target = targetPx(keyCode, w, h, scale);
                if (target == null) return;

                long now = SystemClock.uptimeMillis();
                boolean controlsVisible = now <= controlsVisibleUntilMs;

                if (controlsVisible) {
                    injectTapPx(decor, target[0], target[1]);
                } else {
                    // Controls hidden — a tap at center lands on the video surface
                    // (no button there while hidden) and reveals the controls; then
                    // hit the real target once it has rendered. Net effect: one press.
                    injectTapPx(decor, w / 2f, h / 2f);                      // wake
                    injectTapPxDelayed(decor, target[0], target[1], WAKE_DELAY_MS); // act
                }

                // Update the visibility model. OK toggles play/pause; while paused
                // the controls stay up indefinitely, while playing they hide after
                // the window. (Heuristic — self-corrects on the next press.)
                if (isPlayPause(keyCode)) {
                    assumedPaused = !assumedPaused;
                }
                controlsVisibleUntilMs = assumedPaused
                    ? Long.MAX_VALUE
                    : (now + VISIBLE_WINDOW_MS + WAKE_DELAY_MS);
            } catch (Throwable t) {
                Log.e(TAG, "handleMappedKey failed", t);
            }
        }

        // ── straight delegation for everything else ──────────────────────────

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

        @Override
        public void onProvideKeyboardShortcuts(List<android.view.KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            delegate.onPointerCaptureChanged(hasCapture);
        }
    }
}
