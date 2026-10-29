package ajstrick81.morphe.extension.hbomax.speed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;

/**
 * Entry point of the speed extension and the only class the patch calls into
 * besides {@link PlayerBridge}.
 *
 * The speed chip is attached by watching the Activity's view tree for a
 * full-screen video surface rather than by hooking HBO's player screen, so a
 * redesigned player UI in a future HBO release cannot break the chip.
 *
 * Issue #258 fixes:
 *  1. Remote reachability — keys are intercepted at the Window.Callback level
 *     ({@link SpeedWindowCallback}); HOLD UP or MENU grabs the chip, then
 *     LEFT/RIGHT change speed, OK cycles, BACK/UP/DOWN release.
 *  2. Always-on-screen — the chip starts hidden, appears on remote/mouse
 *     activity during playback and auto-hides after {@link #HIDE_DELAY_MS}.
 *  3. Flicker at app start — only a surface covering most of the screen counts
 *     as "the player" (home-screen hero/trailer surfaces don't), and it must be
 *     seen on consecutive scans before attaching / missed before detaching.
 */
public final class HboSpeedController {
    /** Re-walking the view tree on every layout pass would be wasteful. */
    private static final long SCAN_INTERVAL_MS = 400;
    /** Follow-up scan while a hit/miss is still being confirmed. */
    private static final long CONFIRM_DELAY_MS = 450;
    /** Consecutive scans required before attaching / detaching. */
    private static final int CONFIRM_SCANS = 2;
    /** A playback surface must cover at least this fraction of the window in each dimension. */
    private static final float FULLSCREEN_FRACTION = 0.75f;
    /** Idle time before the chip hides again. */
    private static final long HIDE_DELAY_MS = 5000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = new HideRunnable();
    private static final Runnable RESCAN = new RescanRunnable();

    private static Context applicationContext;
    private static WeakReference<Activity> activityReference = new WeakReference<>(null);
    private static HboSpeedPanel panel;
    private static long lastScanUptime;
    private static int hitStreak;
    private static int missStreak;
    /** Key code whose remaining repeats/ACTION_UP we swallow after a grab. */
    private static int swallowKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    private HboSpeedController() {
    }

    /**
     * Called from BeamApplication.onCreate with {@code p0}. Idempotent — safe if
     * the app reaches it more than once.
     */
    public static void setContext(Context context) {
        try {
            if (applicationContext != null || context == null) return;

            applicationContext = context.getApplicationContext();
            Prefs.init(applicationContext);

            Logger.i("Playback speed control initialised for "
                    + applicationContext.getApplicationInfo().packageName);

            if (applicationContext instanceof Application) {
                ((Application) applicationContext)
                        .registerActivityLifecycleCallbacks(new Lifecycle());
            }
        } catch (Throwable t) {
            Logger.e("setContext failed", t);
        }
    }

    static void postToMainThread(Runnable runnable) {
        MAIN.post(runnable);
    }

    // Remote / pointer input --------------------------------------------------

    /**
     * Sees every key before HBO's view tree does.
     *
     * @return true when the key was consumed by the speed chip.
     */
    static boolean onKey(KeyEvent event) {
        if (panel == null) return false;

        int code = event.getKeyCode();
        int action = event.getAction();

        // Swallow the tail (repeats + ACTION_UP) of the key that grabbed the chip,
        // so HBO never sees a dangling half key-press.
        if (code == swallowKeyCode) {
            if (action == KeyEvent.ACTION_UP) swallowKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }

        if (panel.isActive()) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (action == KeyEvent.ACTION_DOWN) panel.step(-1);
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (action == KeyEvent.ACTION_DOWN) panel.step(+1);
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) panel.cycle();
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (action == KeyEvent.ACTION_DOWN) {
                        release();
                        swallowKeyCode = code; // eat its ACTION_UP too
                    }
                    return true;
                default:
                    // Media keys etc. go to HBO; keep the chip grabbed.
                    scheduleHide();
                    return false;
            }
        }

        // Not grabbed: any remote activity during playback reveals the chip.
        show();

        if (action == KeyEvent.ACTION_DOWN) {
            boolean holdUp = code == KeyEvent.KEYCODE_DPAD_UP && event.getRepeatCount() == 1;
            boolean menu = code == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0;
            if (holdUp || menu) {
                grab();
                swallowKeyCode = code;
                return true;
            }
        }
        return false;
    }

    /** Mouse/air-mouse movement or click: reveal the chip like a key would. */
    static void onPointerActivity() {
        if (panel != null) show();
    }

    private static void show() {
        if (panel == null) return;
        if (panel.getVisibility() != View.VISIBLE) panel.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private static void grab() {
        if (panel == null) return;
        panel.setActive(true);
        show();
        Logger.d("Speed chip grabbed by remote");
    }

    private static void release() {
        if (panel == null) return;
        panel.setActive(false);
        scheduleHide();
        Logger.d("Speed chip released");
    }

    private static void scheduleHide() {
        MAIN.removeCallbacks(HIDE);
        MAIN.postDelayed(HIDE, HIDE_DELAY_MS);
    }

    private static void hideNow() {
        if (panel == null) return;
        panel.setActive(false);
        panel.setVisibility(View.GONE);
    }

    // Attaching and detaching -------------------------------------------------

    private static void startWatching(Activity activity) {
        activityReference = new WeakReference<>(activity);
        hitStreak = 0;
        missStreak = 0;

        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor == null) return;

        installKeyInterceptor(window);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(LAYOUT_LISTENER);
        scan();
    }

    private static void stopWatching(Activity activity) {
        if (activityReference.get() != activity) return;

        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(LAYOUT_LISTENER);
        }
        if (window != null) removeKeyInterceptor(window);

        MAIN.removeCallbacks(RESCAN);
        detachPanel();
        activityReference = new WeakReference<>(null);
    }

    private static void installKeyInterceptor(Window window) {
        try {
            Window.Callback current = window.getCallback();
            if (current == null || current instanceof SpeedWindowCallback) return;
            window.setCallback(new SpeedWindowCallback(current));
        } catch (Throwable t) {
            Logger.e("Key interceptor install failed", t);
        }
    }

    private static void removeKeyInterceptor(Window window) {
        try {
            Window.Callback current = window.getCallback();
            if (current instanceof SpeedWindowCallback) {
                window.setCallback(((SpeedWindowCallback) current).delegate);
            }
        } catch (Throwable t) {
            Logger.e("Key interceptor removal failed", t);
        }
    }

    private static final ViewTreeObserver.OnGlobalLayoutListener LAYOUT_LISTENER =
            new LayoutListener();

    private static void scan() {
        try {
            Activity activity = activityReference.get();
            if (activity == null || activity.getWindow() == null) return;
            lastScanUptime = SystemClock.uptimeMillis();

            View root = activity.getWindow().getDecorView();
            int minWidth = Math.round(root.getWidth() * FULLSCREEN_FRACTION);
            int minHeight = Math.round(root.getHeight() * FULLSCREEN_FRACTION);
            View video = (minWidth > 0 && minHeight > 0)
                    ? VideoSurface.find(root, minWidth, minHeight) : null;

            if (video != null) {
                missStreak = 0;
                if (panel == null && ++hitStreak >= CONFIRM_SCANS) {
                    attachPanel(activity);
                } else if (panel == null) {
                    MAIN.removeCallbacks(RESCAN);
                    MAIN.postDelayed(RESCAN, CONFIRM_DELAY_MS);
                }
            } else {
                hitStreak = 0;
                if (panel != null && ++missStreak >= CONFIRM_SCANS) {
                    detachPanel();
                } else if (panel != null) {
                    MAIN.removeCallbacks(RESCAN);
                    MAIN.postDelayed(RESCAN, CONFIRM_DELAY_MS);
                }
            }
        } catch (Throwable t) {
            Logger.e("Scan failed", t);
        }
    }

    private static void attachPanel(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        panel = new HboSpeedPanel(activity); // starts GONE
        ((ViewGroup) content).addView(panel, panel.buildLayoutParams());
        // Re-apply the saved speed to the new player session.
        PlayerBridge.setSpeed(Prefs.speed());
        Logger.d("Speed chip attached (hidden until remote activity)");
    }

    private static void detachPanel() {
        MAIN.removeCallbacks(HIDE);
        swallowKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        hitStreak = 0;
        missStreak = 0;
        if (panel == null) return;

        ViewGroup parent = panel.getParent() instanceof ViewGroup
                ? (ViewGroup) panel.getParent() : null;
        if (parent != null) parent.removeView(panel);
        panel = null;
        Logger.d("Speed chip detached");
    }

    // Named framework subclasses (ART verifier — see SpeedWindowCallback) -----

    private static final class LayoutListener implements ViewTreeObserver.OnGlobalLayoutListener {
        @Override
        public void onGlobalLayout() {
            if (SystemClock.uptimeMillis() - lastScanUptime < SCAN_INTERVAL_MS) return;
            scan();
        }
    }

    private static final class RescanRunnable implements Runnable {
        @Override
        public void run() {
            scan();
        }
    }

    private static final class HideRunnable implements Runnable {
        @Override
        public void run() {
            hideNow();
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            startWatching(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            stopWatching(activity);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
