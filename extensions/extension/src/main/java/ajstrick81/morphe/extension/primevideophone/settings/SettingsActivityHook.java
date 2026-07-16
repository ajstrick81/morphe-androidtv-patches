package ajstrick81.morphe.extension.primevideophone.settings;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

/**
 * Entry point for the hijacked host Activity.
 *
 * The patch rewrites com.google.android.gms.common.api.GoogleApiActivity's onCreate to
 * call {@link #initialize(Activity)} and return, so the activity's original flow never
 * starts. This is the upstream Morphe technique, and it exists because a *new* activity
 * cannot be added to the manifest without breaking root installs — so an expendable
 * existing activity is hijacked instead. GoogleApiActivity is the standard victim: it
 * extends Activity directly and only serves GMS error resolution dialogs, which the app
 * effectively never shows.
 *
 * Unlike upstream, the patch KEEPS the activity's other methods (see SettingsPatch.kt for
 * why removing them is both unnecessary and harmful here). So GMS can still launch this
 * activity for its original purpose — which is exactly why initialize() checks the intent
 * below and finishes on anything that is not ours, rather than assuming every launch is.
 *
 * The screen is hosted in android.R.id.content, so no custom layout resource is needed.
 */
public final class SettingsActivityHook {

    private static final String TAG = "MORPHE-SETTINGS";

    /** Intent data that marks a launch as ours; anything else is not for us. */
    public static final String SETTINGS_INTENT_DATA = "morphe_settings_intent";

    private SettingsActivityHook() {}

    /**
     * Injection point — called from the hijacked GoogleApiActivity.onCreate after
     * invoke-super, with p0 (the Activity) as the argument.
     */
    public static void initialize(Activity activity) {
        try {
            if (activity == null) return;

            // Sanity check: GMS (or anything else) may still try to launch this
            // activity for its original purpose. Its real logic is gone, so there is
            // nothing useful to do — finish rather than present an empty screen.
            Intent intent = activity.getIntent();
            String data = (intent == null) ? null : intent.getDataString();
            if (!SETTINGS_INTENT_DATA.equals(data)) {
                Log.w(TAG, "not a Morphe settings intent (data=" + data + "); finishing");
                activity.finish();
                return;
            }

            activity.setTitle("Morphe settings");
            activity.getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new MorphePreferenceFragment())
                    .commit();

            Log.i(TAG, "Morphe settings opened");
        } catch (Throwable t) {
            Log.e(TAG, "initialize failed", t);
            try {
                activity.finish();
            } catch (Throwable ignored) {
                // Nothing further we can do.
            }
        }
    }
}
