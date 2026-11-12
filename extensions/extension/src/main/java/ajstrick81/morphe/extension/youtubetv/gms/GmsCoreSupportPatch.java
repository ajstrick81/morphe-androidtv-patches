package ajstrick81.morphe.extension.youtubetv.gms;

import java.lang.reflect.Method;

/**
 * Runtime GmsCore (MicroG-RE) availability check for YouTube Android TV.
 *
 * Ported from Morphe mobile:
 *   extensions/shared-youtube/library/.../GmsCoreSupportPatch.java
 *
 * Called from injected smali at the top of CobaltActivity.onCreate() (the superclass of
 * both MainActivity and StandalonePlayerActivity) via:
 *
 *   invoke-static/range { p0 .. p0 },
 *       Lajstrick81/morphe/extension/youtubetv/gms/GmsCoreSupportPatch;->checkGmsCore(Ljava/lang/Object;)V
 *
 * WHY: a re-signed YouTube TV build dies with
 *   java.lang.SecurityException: PackageVerificationRslt: not allowed: pkg=..., sha256=...
 * because Google Play services refuses a package name/signature it does not recognise.
 * "GmsCore support" redirects every Play services string to app.revanced.android.gms, so
 * the app needs MicroG-RE instead. Without it the app's GMS-backed calls have no provider;
 * this class reports that with a Toast at start-up.
 *
 * TV-SPECIFIC ADAPTATIONS:
 * - Battery optimization check REMOVED: Android TV devices do not aggressively kill
 *   background processes, and the TV Settings UI is unsuitable for the dialog-based
 *   battery optimization whitelist flow.
 * - Dialog-based UX REPLACED with Toast-based UX: Toasts render natively on Android TV
 *   and need no D-pad navigation.
 * - Root/mounted install detection KEPT: same logic as mobile.
 * - PackageManager check KEPT: verifies app.revanced.android.gms is installed.
 * - Content provider check KEPT: verifies MicroG's GSF content provider is running. The
 *   URI is app.revanced.android.gsf.gservices/prefix, which is also where the patch
 *   redirects the app's own content://com.google.android.gsf.gservices strings.
 *
 * REFLECTION-ONLY:
 *   The entry point takes java.lang.Object and reaches every platform type through
 *   java.lang.reflect (Activity, PackageManager, ContentResolver, Uri, Toast). The class
 *   therefore references no Android SDK symbols, and the injected descriptor stays
 *   (Ljava/lang/Object;)V. The extension module does compile against android.jar (other
 *   extensions import platform classes directly); this class is kept reflection-only so
 *   the smali hook does not need an Activity-typed signature.
 */
@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public class GmsCoreSupportPatch {

    // Matches mobile: app.revanced.android.gms
    private static final String GMS_CORE_PACKAGE_NAME = "app.revanced.android.gms";

    // Original YouTube TV package for spoofed identity check
    private static final String ORIGINAL_PACKAGE_NAME = "com.google.android.youtube.tv";

    // Patched package name (owned by "GmsCore support")
    private static final String MORPHE_PACKAGE_NAME = "app.morphe.android.youtube.tv";

    // MicroG's GSF services content provider authority
    private static final String GMS_CORE_PROVIDER = "content://app.revanced.android.gsf.gservices/prefix";

    // Where to get MicroG-RE. Only shown in a Toast; the patch never downloads anything.
    private static final String MICROG_DOWNLOAD_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest";

    /**
     * Called from injected smali at the top of CobaltActivity.onCreate().
     *
     * Checks:
     * 1. Root/mounted install detection (warns if GmsCore support was used with root install)
     * 2. MicroG-RE installation (shows the download URL if it is not installed)
     * 3. MicroG background service health (via its GSF content provider)
     *
     * TV-specific: the battery optimization check is SKIPPED.
     *
     * @param activity The activity instance (passed from smali via p0), typed as Object so
     *                 no android.app.Activity import is needed.
     */
    public static void checkGmsCore(Object activity) {
        if (activity == null) return;

        // Check 1: Detect root/mounted install
        // If the package name is still the original, the rename was not applied, which
        // means the APK was mounted rather than installed through the patcher.
        if (isPackageNameOriginal(activity) && !isPatched(activity)) {
            showToast(activity,
                "Do not include 'GmsCore support' patch with root install. " +
                "Please repatch the APK with GmsCore support enabled.");
            return;
        }

        // Check 2: Verify MicroG-RE is installed
        if (!isGmsCoreInstalled(activity)) {
            showToast(activity,
                "MicroG RE is not installed. " +
                "Download it from: " + MICROG_DOWNLOAD_URL);
            return;
        }

        // Check 3: Verify MicroG background service is running
        if (!isGmsCoreServiceRunning(activity)) {
            showToast(activity,
                "MicroG RE is installed but not running. " +
                "Please launch MicroG RE and complete the initial setup. " +
                "If the issue persists, reinstall MicroG RE.");
            return;
        }

        // TV-SPECIFIC: Battery optimization check REMOVED.
        // TVs do not aggressively kill background processes, and the TV Settings UI is
        // unsuitable for the dialog-based battery optimization flow that the mobile patch
        // uses (PowerManager.isIgnoringBatteryOptimizations() +
        // Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
        // All checks passed — GmsCore is ready.
    }

    // ── Package name checks ─────────────────────────────────────────────────

    /**
     * Checks if the package name is still the original one.
     * Uses reflection to call Activity.getPackageName().
     */
    private static boolean isPackageNameOriginal(Object activity) {
        try {
            Method getPackageName = activity.getClass().getMethod("getPackageName");
            Object packageName = getPackageName.invoke(activity);
            return ORIGINAL_PACKAGE_NAME.equals(packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks that the patched package name is in use.
     */
    private static boolean isPatched(Object activity) {
        try {
            Method getPackageName = activity.getClass().getMethod("getPackageName");
            Object packageName = getPackageName.invoke(activity);
            return MORPHE_PACKAGE_NAME.equals(packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // ── MicroG installation check ───────────────────────────────────────────

    /**
     * Verifies that MicroG-RE (app.revanced.android.gms) is installed.
     *
     * Uses reflection to obtain the PackageManager and query for the GmsCore package.
     */
    private static boolean isGmsCoreInstalled(Object activity) {
        try {
            // Activity.getPackageManager() → PackageManager
            Method getPackageManager = activity.getClass().getMethod("getPackageManager");
            Object pm = getPackageManager.invoke(activity);
            if (pm == null) return false;

            // PackageManager.getPackageInfo(String, int) → PackageInfo
            Method getPackageInfo = pm.getClass().getMethod(
                "getPackageInfo", String.class, int.class
            );
            Object info = getPackageInfo.invoke(pm, GMS_CORE_PACKAGE_NAME, 0);
            return info != null;
        } catch (Exception e) {
            // NameNotFoundException or any reflection failure → not installed
            return false;
        }
    }

    // ── MicroG background service check ─────────────────────────────────────

    /**
     * Verifies that MicroG's GSF content provider is reachable, which means MicroG is
     * running and allowed to serve the app.
     *
     * Reflectively calls:
     *   1. Activity.getContentResolver() → ContentResolver
     *   2. Uri.parse(String) → Uri (the constant is a full content:// URI, not an authority)
     *   3. ContentResolver.acquireContentProviderClient(Uri) → ContentProviderClient
     *   4. ContentProviderClient.close()
     */
    private static boolean isGmsCoreServiceRunning(Object activity) {
        try {
            // Activity.getContentResolver() → ContentResolver
            Method getContentResolver = activity.getClass().getMethod("getContentResolver");
            Object resolver = getContentResolver.invoke(activity);
            if (resolver == null) return false;

            // acquireContentProviderClient(Uri), not the String overload: the String form
            // takes an authority ("app.revanced.android.gsf.gservices"), while this constant
            // is a full URI. Passing the URI to the String overload returns null on every
            // launch, which would report "MicroG not running" even when it is.
            Class<?> uriClass = Class.forName("android.net.Uri");
            Object uri = uriClass.getMethod("parse", String.class).invoke(null, GMS_CORE_PROVIDER);
            Method acquireClient = resolver.getClass().getMethod(
                "acquireContentProviderClient", uriClass
            );
            Object client = acquireClient.invoke(resolver, uri);

            if (client != null) {
                // ContentProviderClient.close()
                Method close = client.getClass().getMethod("close");
                close.invoke(client);
                return true;
            }
            return false;
        } catch (Exception e) {
            // SecurityException (signature spoof mismatch) or any other failure →
            // MicroG is not running or not properly configured
            return false;
        }
    }

    // ── Toast display ───────────────────────────────────────────────────────

    /**
     * Shows a Toast message on Android TV using reflection.
     *
     * Toast-based UX is preferred over dialog-based UX on TV because:
     * - Toasts render natively on Android TV
     * - They don't require directional navigation (D-pad)
     * - They auto-dismiss without user interaction
     *
     * The Toast is posted to the UI thread via Activity.runOnUiThread().
     */
    private static void showToast(Object activity, String message) {
        try {
            // Post to UI thread via Activity.runOnUiThread(Runnable)
            Method runOnUiThread = activity.getClass().getMethod(
                "runOnUiThread", Runnable.class
            );

            runOnUiThread.invoke(activity, (Runnable) () -> {
                try {
                    // Toast.makeText(Context, CharSequence, int) → Toast
                    Class<?> toastClass = Class.forName("android.widget.Toast");
                    Method makeText = toastClass.getMethod(
                        "makeText",
                        Class.forName("android.content.Context"),
                        CharSequence.class,
                        int.class
                    );

                    // Toast.LENGTH_LONG = 1
                    Object toast = makeText.invoke(null, activity, message, 1);

                    // Toast.show()
                    Method show = toastClass.getMethod("show");
                    show.invoke(toast);
                } catch (Exception e) {
                    // Silent fail — Toast is non-critical UX
                }
            });
        } catch (Exception e) {
            // Silent fail — Toast is non-critical UX
        }
    }
}
