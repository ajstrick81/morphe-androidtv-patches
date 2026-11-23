package ajstrick81.morphe.extension.hbomax.speed;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the user's last speed so it survives app restarts. Uses HBO Max's
 * own SharedPreferences — no storage permission and nothing written outside the
 * app's sandbox (ownership rule: stay local, add no cloud/telemetry).
 */
final class Prefs {
    private static final String FILE = "hbo_playback_speed";
    private static final String KEY_SPEED = "speed";

    private static SharedPreferences preferences;

    private Prefs() {
    }

    static void init(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static float speed() {
        return preferences == null ? 1f : preferences.getFloat(KEY_SPEED, 1f);
    }

    static void speed(float value) {
        if (preferences != null) preferences.edit().putFloat(KEY_SPEED, value).apply();
    }
}
