package ajstrick81.morphe.extension.hbomax.speed;

import android.util.Log;

/**
 * Thin logging wrapper so {@code adb logcat -s HboSpeed} is enough to debug a
 * patched build on device. Everything logs unconditionally — a release build of
 * HBO Max is never debuggable, so a debuggable-gated logger would never fire.
 */
public final class Logger {
    public static final String TAG = "HboSpeed";

    private Logger() {
    }

    public static void d(String message) {
        Log.d(TAG, message);
    }

    public static void i(String message) {
        Log.i(TAG, message);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}
