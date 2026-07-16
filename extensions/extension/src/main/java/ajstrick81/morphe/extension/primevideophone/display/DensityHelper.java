package ajstrick81.morphe.extension.primevideophone.display;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import ajstrick81.morphe.extension.primevideophone.settings.Settings;

/**
 * Reduces the Prime Video phone app's UI zoom on TV hardware by overriding the
 * app's OWN display density — NOT the system's.
 *
 * The phone app renders at the device's physical density (~320dpi on the Onn
 * 4K), which makes its phone-first UI look large/zoomed on a 10-foot TV. The
 * obvious lever, `adb wm density`, is off-limits: on this Google TV box any
 * system density override triggers a fatal "Google TV does not support 1920x1080"
 * launcher error (see onn-tv-wm-density-breaks-display memory).
 *
 * Instead we wrap each Activity's base Context with a Configuration whose
 * densityDpi is scaled down, via AppCompatActivity.attachBaseContext. This
 * affects only Prime Video's own resources — the system display is untouched,
 * so the launcher error is impossible. Applied app-wide because every PV
 * activity ultimately extends AppCompatActivity.
 *
 * The scale is the single tunable: 0.66 ≈ 213/320 (tvdpi), i.e. roughly the look
 * reached earlier with `wm density 213`. Lower = smaller/less zoomed. It is read
 * from {@link Settings} (user-configurable via the "Morphe settings" patch) and
 * falls back to 0.80 — the previous hardcoded value — when unset, so behaviour is
 * unchanged if that patch is not applied.
 */
public final class DensityHelper {

    private static final String TAG = "MORPHE-DENSITY";

    private DensityHelper() {}

    /** Returns a density-scaled configuration context, or the original on any issue. */
    public static Context wrap(Context base) {
        try {
            if (base == null) return base;
            // Read from the base Context: this runs inside attachBaseContext, before
            // the Activity has a Context of its own.
            float densityScale = Settings.getUiZoomScale(base);
            Configuration current = base.getResources().getConfiguration();
            int currentDpi = current.densityDpi;
            int newDpi = Math.round(currentDpi * densityScale);
            if (newDpi <= 0 || newDpi == currentDpi) return base;

            Configuration cfg = new Configuration(current);
            cfg.densityDpi = newDpi;
            Context scaled = base.createConfigurationContext(cfg);
            Log.i(TAG, "UI density " + currentDpi + " -> " + newDpi + " (PV only)");
            return scaled;
        } catch (Throwable t) {
            Log.e(TAG, "density wrap failed; leaving default", t);
            return base;
        }
    }
}
