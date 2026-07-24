package ajstrick81.morphe.extension.primevideo.nativehook;

import android.util.Log;

/**
 * Prime Video ATV — native ad-strip hook loader.
 *
 * The whole in-process ad strip lives in the native library libpvhook.so
 * (SSL_read / inflate interception + manifest filtering). This class is the
 * only Java surface: it loads that library early in Application.onCreate so
 * JNI_OnLoad installs the hooks before the first playback session builds its
 * native MediaPipelineBackend.
 *
 * Called from patched bytecode injected by loadNativeHookPatch:
 *
 *   invoke-static {}, Lajstrick81/morphe/extension/primevideo/nativehook/NativeHookLoader;->load()V
 *
 * "Fail loud" on purpose: a missing/misaligned .so throws UnsatisfiedLinkError,
 * which we log to TAG rather than swallow, so logcat immediately shows whether
 * the library loaded during bring-up. We never crash the app over it — the app
 * still runs (ads unblocked) if the hook can't load.
 *
 * This deliberately mirrors the existing SkipAdsPatch extension convention:
 * small, self-contained, logs to a stable TAG, catches everything.
 */
@SuppressWarnings("unused")
public final class NativeHookLoader {

    private static final String TAG = "PVNativeHook";
    private static final String LIB = "pvhook"; // -> libpvhook.so in lib/<abi>/

    private static boolean loaded = false;

    private NativeHookLoader() {}

    /** Idempotent: safe if the injected call site somehow runs more than once. */
    public static synchronized void load() {
        if (loaded) return;
        try {
            System.loadLibrary(LIB);
            loaded = true;
            Log.i(TAG, "NativeHookLoader: loaded lib" + LIB + ".so");
        } catch (UnsatisfiedLinkError e) {
            // Most likely causes during bring-up:
            //   - .so not bundled into lib/<abi>/ (bundle resource patch didn't run)
            //   - ABI mismatch (built arm64 but device/app is armeabi-v7a)
            //   - extractNativeLibs alignment issue (see BundleNativeHookPatch)
            Log.e(TAG, "NativeHookLoader: could not load lib" + LIB + ".so", e);
        } catch (Throwable t) {
            Log.e(TAG, "NativeHookLoader: unexpected failure loading lib" + LIB + ".so", t);
        }
    }
}
