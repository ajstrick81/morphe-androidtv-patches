package ajstrick81.morphe.extension.primevideophone.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persisted settings for the Prime Video *phone* patches, backed by a dedicated
 * SharedPreferences file (never the app's own, to avoid colliding with Amazon's keys).
 *
 * Every read site already has a Context — DensityHelper.wrap() receives the base
 * Context and DpadPlayerControls.install() receives the Activity — so there is no
 * need for the app-wide Context plumbing the upstream Morphe settings framework uses.
 *
 * Defaults intentionally reproduce the previous hardcoded constants, so behaviour is
 * unchanged when the "Morphe settings" patch is not applied (the class still merges
 * with the extension dex and simply always returns defaults).
 *
 * ListPreference persists String values, hence the zoom scale is stored as a String
 * and parsed defensively — a corrupt/absent value falls back to the default rather
 * than throwing inside attachBaseContext (which would break every Activity).
 */
public final class Settings {

    private static final String TAG = "MORPHE-SETTINGS";

    public static final String PREFS_NAME = "morphe_settings";

    public static final String KEY_DPAD_ENABLED = "morphe_dpad_enabled";
    public static final String KEY_UI_ZOOM_SCALE = "morphe_ui_zoom_scale";

    public static final boolean DEFAULT_DPAD_ENABLED = true;
    /** Matches the previous DensityHelper.DENSITY_SCALE constant (0.80f). */
    public static final String DEFAULT_UI_ZOOM_SCALE = "0.80";

    private Settings() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Whether the D-pad player controls should install. Defaults to enabled. */
    public static boolean isDpadEnabled(Context context) {
        try {
            if (context == null) return DEFAULT_DPAD_ENABLED;
            return prefs(context).getBoolean(KEY_DPAD_ENABLED, DEFAULT_DPAD_ENABLED);
        } catch (Throwable t) {
            Log.e(TAG, "isDpadEnabled failed; using default", t);
            return DEFAULT_DPAD_ENABLED;
        }
    }

    /**
     * Fraction of the device's physical density to render Prime Video at.
     * 1.0 = no change. Lower = smaller / less zoomed.
     */
    public static float getUiZoomScale(Context context) {
        String raw = DEFAULT_UI_ZOOM_SCALE;
        try {
            if (context != null) {
                raw = prefs(context).getString(KEY_UI_ZOOM_SCALE, DEFAULT_UI_ZOOM_SCALE);
            }
            float value = Float.parseFloat(raw);
            // Guard against a nonsensical stored value bricking the UI.
            if (value <= 0.1f || value > 2.0f) {
                Log.w(TAG, "UI zoom scale out of range (" + value + "); using default");
                return Float.parseFloat(DEFAULT_UI_ZOOM_SCALE);
            }
            return value;
        } catch (Throwable t) {
            Log.e(TAG, "getUiZoomScale failed for '" + raw + "'; using default", t);
            return Float.parseFloat(DEFAULT_UI_ZOOM_SCALE);
        }
    }
}
