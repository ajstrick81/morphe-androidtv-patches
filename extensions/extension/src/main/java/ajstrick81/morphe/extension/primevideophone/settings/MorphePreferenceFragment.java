package ajstrick81.morphe.extension.primevideophone.settings;

import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Log;

/**
 * The Morphe settings UI for the Prime Video phone app, built entirely in code.
 *
 * Building the PreferenceScreen programmatically (rather than inflating a generated
 * XML like upstream Morphe does) is the key simplification of this minimal port: it
 * removes any need for the preference-serialisation DSL, the AddResources string
 * infrastructure, and shipping XML/layout resources into the APK. Titles are literal
 * strings for the same reason — this is a personal TV mod, not a localised product.
 *
 * Uses the framework android.preference.* API (deprecated but present, and what
 * upstream Morphe also uses). A framework PreferenceFragment is a ListView, so it is
 * natively D-pad focusable — the settings screen works with a TV remote for free,
 * which is the whole point of running the phone app on a TV.
 *
 * NOTE: the fragment may be re-instantiated by the framework by name after a
 * configuration change, so it must be kept by R8 (see extensions/proguard-rules.pro).
 */
@SuppressWarnings("deprecation")
public class MorphePreferenceFragment extends PreferenceFragment {

    private static final String TAG = "MORPHE-SETTINGS";

    /** Display labels and their stored values for the UI zoom scale. */
    private static final CharSequence[] ZOOM_LABELS = {
            "100% — no change (stock phone UI)",
            "90%",
            "85%",
            "80% — recommended",
            "75%",
            "70%",
            "66% — tvdpi look (may be too small)",
    };
    private static final CharSequence[] ZOOM_VALUES = {
            "1.00", "0.90", "0.85", "0.80", "0.75", "0.70", "0.66",
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            PreferenceManager manager = getPreferenceManager();
            manager.setSharedPreferencesName(Settings.PREFS_NAME);
            manager.setSharedPreferencesMode(Context.MODE_PRIVATE);

            Context context = getActivity();
            PreferenceScreen screen = manager.createPreferenceScreen(context);

            SwitchPreference dpad = new SwitchPreference(context);
            dpad.setKey(Settings.KEY_DPAD_ENABLED);
            dpad.setDefaultValue(Settings.DEFAULT_DPAD_ENABLED);
            dpad.setTitle("D-pad player controls");
            dpad.setSummary("OK = play/pause, Left/Right = seek, Up = subtitles, Down = settings. "
                    + "Applies as soon as you go back to the player. Holding OK always opens "
                    + "this screen, even when off.");
            screen.addPreference(dpad);

            ListPreference zoom = new ListPreference(context);
            zoom.setKey(Settings.KEY_UI_ZOOM_SCALE);
            zoom.setDefaultValue(Settings.DEFAULT_UI_ZOOM_SCALE);
            zoom.setEntries(ZOOM_LABELS);
            zoom.setEntryValues(ZOOM_VALUES);
            zoom.setTitle("UI zoom");
            zoom.setDialogTitle("UI zoom");
            // %s renders the selected entry's label as the summary.
            zoom.setSummary("%s\nScales Prime Video's own density only. Restart the app to apply.");
            screen.addPreference(zoom);

            setPreferenceScreen(screen);
        } catch (Throwable t) {
            Log.e(TAG, "failed to build settings screen", t);
        }
    }
}
