/*
 * Injects a JavaScript payload (TvScriptsPayload) into the youtube.com/tv leanback web app.
 *
 * The injection technique — native WebContentsImpl.evaluateJavaScript reached through
 * org.jni_zero.GEN_JNI, with the native pointer found by field type — comes from:
 *   - ysamjo/morphe-patches (GPLv3), CobaltScriptInjector
 *   - DylanDuff/utv-runtime (MIT), RuntimeObserver
 *
 * The smali patch that calls these entry points is TvScriptsPatch.kt (a dependency of "Skip ads").
 *
 * Licensed under GPLv3, see the NOTICE file.
 */
package ajstrick81.morphe.extension.youtubetv.scripts;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public final class TvScripts {
    private static final String TAG = "MorpheYTTV";
    /** The script reports through document.title: "tvp:<message>". */
    private static final String LOG_PREFIX = "tvp:";

    private static Object webContents;
    private static Field pointerField;
    private static Method evaluateJavaScript;

    private TvScripts() {
    }

    /** Called from the patched StartupGuardNavigationObserver constructor. */
    public static void setWebContents(Object contents) {
        webContents = contents;
        pointerField = null;
    }

    /** Called from primaryMainDocumentElementAvailable and didFinishNavigationInPrimaryMainFrame. */
    public static void onPageEvent(String event) {
        if (inject()) {
            Log.d(TAG, "scripts injected (" + event + ")");
        }
    }

    /** The script logs by setting document.title to "tvp:<message>". */
    public static void onTitle(String title) {
        if (title != null && title.startsWith(LOG_PREFIX)) {
            Log.i(TAG, title.substring(LOG_PREFIX.length()));
        }
    }

    private static boolean inject() {
        return evaluate(TvScriptsPayload.SCRIPT);
    }

    /** Runs JavaScript in the page. Call on the main thread. */
    private static boolean evaluate(String javaScript) {
        try {
            Object contents = webContents;
            if (contents == null) return false;

            if (pointerField == null) pointerField = findNativePointerField(contents);
            if (pointerField == null) {
                Log.w(TAG, "native pointer field not found");
                return false;
            }
            long pointer = pointerField.getLong(contents);
            if (pointer == 0L) return false;

            if (evaluateJavaScript == null) evaluateJavaScript = findEvaluateJavaScript();
            if (evaluateJavaScript == null) {
                Log.w(TAG, "evaluateJavaScript native not found");
                return false;
            }

            evaluateJavaScript.invoke(null, pointer, javaScript, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "script injection failed", t);
            return false;
        }
    }

    /**
     * The only long field of WebContentsImpl holds the native pointer. Its name is obfuscated
     * (b in 7.05.301), so scan by type instead, including superclasses.
     */
    private static Field findNativePointerField(Object contents) {
        for (Class<?> type = contents.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != long.class) continue;
                try {
                    field.setAccessible(true);
                    if (field.getLong(contents) != 0L) return field;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** R8 removed the Java wrapper, but the JNI stub is still registered in GEN_JNI. */
    private static Method findEvaluateJavaScript() throws ClassNotFoundException {
        Class<?> genJni = Class.forName("org.jni_zero.GEN_JNI");
        for (Method method : genJni.getDeclaredMethods()) {
            if (!method.getName().endsWith("WebContentsImpl_evaluateJavaScript")) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0] == long.class
                    && parameters[1] == Object.class
                    && parameters[2] == Object.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
}
