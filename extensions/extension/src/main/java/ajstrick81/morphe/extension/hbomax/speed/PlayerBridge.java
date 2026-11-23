package ajstrick81.morphe.extension.hbomax.speed;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds HBO Max's ExoPlayer instances and changes their playback speed.
 *
 * Players are handed over by a one-instruction hook on the return path of every
 * class implementing media3's ExoPlayer interface (on com.wbd.hbomax 7.x that is
 * the single implementor androidx.media3.exoplayer.d — ExoPlayerImpl, which is
 * package-private final and exposes only an obfuscated
 * setPlaybackParameters(PlaybackParameters), so both reflection routes below are
 * needed).
 *
 * Two things make this less obvious than it looks:
 *
 *  1. Threading. media3 refuses any call from a thread other than the one the
 *     player was built on ("Player is accessed on the wrong thread"). The call
 *     is posted to the player's own application looper.
 *  2. Which player. HBO builds several ExoPlayer-shaped objects (the real one,
 *     wrappers, sometimes a preload/trailer player). The speed is applied to
 *     every live one; setting the speed of an idle player does nothing.
 *
 * Ported from aimal-patches' streaming/PlayerBridge (same Morphe/media3 lineage),
 * repackaged into this project's namespace. Nothing here touches DRM, licensing,
 * entitlement or ad code.
 */
public final class PlayerBridge {
    private static final String METHOD_NAME = "setPlaybackSpeed";

    /** Enough for the handful HBO builds, without growing forever. */
    private static final int MAX_PLAYERS = 8;

    /**
     * Weak so finished players are collected normally. A leaked player in a
     * streaming app shows up as an OOM on a long binge session.
     */
    private static final List<WeakReference<Object>> players = new ArrayList<>();

    private static Method resolvedMethod;
    private static Class<?> resolvedFor;

    /** Cached setPlaybackParameters wiring, keyed on the player class. */
    private static Class<?> parametersFor;
    private static Constructor<?> parametersConstructor;
    private static Method parametersSetter;

    private PlayerBridge() {
    }

    /**
     * Called from the player's constructor return path. Must not throw: it runs
     * while the player is being built, and an exception here would take playback
     * down.
     */
    public static void onPlayerCreated(Object player) {
        try {
            if (player == null) return;

            int live;
            synchronized (players) {
                boolean known = false;
                for (int i = players.size() - 1; i >= 0; i--) {
                    Object existing = players.get(i).get();
                    if (existing == null) players.remove(i);
                    else if (existing == player) known = true;
                }
                if (!known) players.add(new WeakReference<>(player));
                while (players.size() > MAX_PLAYERS) players.remove(0);
                live = players.size();
            }

            Logger.i("Player captured (" + live + " live): " + player.getClass().getName());

            float saved = Prefs.speed();
            if (saved != 1f) {
                // A new player always starts at 1x, so re-apply the user's
                // choice — but not from inside the constructor, where the
                // player is not usable yet.
                HboSpeedController.postToMainThread(new Runnable() {
                    @Override
                    public void run() {
                        setSpeed(Prefs.speed());
                    }
                });
            }
        } catch (Throwable t) {
            Logger.e("onPlayerCreated failed", t);
        }
    }

    static int capturedCount() {
        return livePlayers().size();
    }

    /**
     * @return how many live players the change was dispatched to.
     */
    static int setSpeed(final float speed) {
        List<Object> live = livePlayers();
        if (live.isEmpty()) {
            Logger.i("setSpeed(" + speed + "): no player captured");
            return 0;
        }

        for (final Object player : live) {
            Looper looper = applicationLooper(player);

            if (looper == null || looper == Looper.myLooper()) {
                apply(player, speed);
            } else {
                // media3 rejects calls from any other thread.
                new Handler(looper).post(new Runnable() {
                    @Override
                    public void run() {
                        apply(player, speed);
                    }
                });
            }
        }

        Logger.i("setSpeed(" + speed + "): dispatched to " + live.size() + " player(s)");
        return live.size();
    }

    private static void apply(Object player, float speed) {
        Method method = resolve(player);

        if (method != null) {
            try {
                method.invoke(player, speed);
                Logger.i("Speed " + speed + " applied to " + player.getClass().getSimpleName());
                return;
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                Logger.e("Reflective " + METHOD_NAME + " failed on "
                        + player.getClass().getName(), cause);
            }
        }

        // No setPlaybackSpeed(float) survived HBO's obfuscation — ExoPlayerImpl
        // (androidx.media3.exoplayer.d) exposes only setPlaybackParameters, so
        // speed goes through PlaybackParameters.
        if (applyViaPlaybackParameters(player, speed)) return;

        try {
            setSpeedNative(player, speed);
            Logger.i("Speed " + speed + " applied via patched hook");
        } catch (Throwable t) {
            Logger.e("Patched " + METHOD_NAME + " failed", t);
        }
    }

    /**
     * media3's {@code Player.setPlaybackParameters(PlaybackParameters)} route.
     *
     * Nothing here is looked up by name, because HBO obfuscates the value type
     * (e.g. {@code lt6}) and the setter. They are found by shape:
     *
     *  * PlaybackParameters is the only type returned by a no-argument getter on
     *    the player that also has a public {@code (float, float)} constructor and
     *    two public float fields. media3's other value types (Timeline, Tracks,
     *    MediaItem, VideoSize, …) have no such constructor.
     *  * the setter is then the one void method taking exactly that type.
     *
     * @return true only if the speed was actually applied.
     */
    private static boolean applyViaPlaybackParameters(Object player, float speed) {
        try {
            if (!resolveParameters(player)) return false;

            // (speed, pitch) — pitch stays at 1 so voices are not chipmunked.
            Object parameters = parametersConstructor.newInstance(speed, 1f);
            parametersSetter.invoke(player, parameters);

            Logger.i("Speed " + speed + " applied via setPlaybackParameters on "
                    + player.getClass().getSimpleName());
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Logger.e("setPlaybackParameters failed on " + player.getClass().getName(), cause);
            return false;
        }
    }

    private static boolean resolveParameters(Object player) {
        Class<?> type = player.getClass();
        if (parametersFor == type) return parametersSetter != null;

        parametersFor = type;
        parametersConstructor = null;
        parametersSetter = null;

        for (Class<?> owner : publicTypes(type)) {
            for (Method getter : owner.getMethods()) {
                if (getter.getParameterTypes().length != 0) continue;

                Class<?> candidate = getter.getReturnType();
                Constructor<?> constructor = floatPairConstructor(candidate);
                if (constructor == null) continue;

                Method setter = setterTaking(owner, candidate);
                if (setter == null) continue;

                parametersConstructor = constructor;
                parametersSetter = setter;

                Logger.i("Speed bound to " + owner.getName() + "." + setter.getName()
                        + "(" + candidate.getName() + ")");
                return true;
            }
        }

        Logger.i("No setPlaybackParameters found on " + type.getName());
        return false;
    }

    /** Public classes and interfaces in the player's hierarchy, nearest first. */
    private static List<Class<?>> publicTypes(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (Modifier.isPublic(c.getModifiers()) && !out.contains(c)) out.add(c);
            for (Class<?> face : c.getInterfaces()) {
                if (Modifier.isPublic(face.getModifiers()) && !out.contains(face)) out.add(face);
            }
        }
        return out;
    }

    /**
     * The {@code (float, float)} constructor of a public value type that also
     * exposes at least two public float fields — PlaybackParameters' speed and
     * pitch. Null if [type] is not shaped like that.
     */
    private static Constructor<?> floatPairConstructor(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isInterface()) return null;
        if (!Modifier.isPublic(type.getModifiers())) return null;

        try {
            Constructor<?> constructor = type.getConstructor(float.class, float.class);

            int floats = 0;
            for (Field field : type.getFields()) {
                if (field.getType() == float.class) floats++;
            }

            return floats >= 2 ? constructor : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Method setterTaking(Class<?> owner, Class<?> parameter) {
        for (Method method : owner.getMethods()) {
            if (method.getReturnType() != void.class) continue;
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) continue;

            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == parameter) return method;
        }
        return null;
    }

    private static List<Object> livePlayers() {
        List<Object> live = new ArrayList<>();
        synchronized (players) {
            for (int i = players.size() - 1; i >= 0; i--) {
                Object player = players.get(i).get();
                if (player == null) players.remove(i);
                else live.add(player);
            }
        }
        return live;
    }

    /**
     * media3's {@code Player.getApplicationLooper()} — the thread the player
     * insists on being called from. Null when it cannot be determined, in which
     * case the call is made from wherever we are and any complaint shows up in
     * the log.
     */
    private static Looper applicationLooper(Object player) {
        for (Class<?> c = player.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("getApplicationLooper");
                method.setAccessible(true);
                Object looper = method.invoke(player);
                if (looper instanceof Looper) return (Looper) looper;
            } catch (NoSuchMethodException absent) {
                // Keep walking up.
            } catch (Throwable t) {
                Logger.e("getApplicationLooper failed", t);
                return null;
            }
        }
        return null;
    }

    /**
     * Finds a usable {@code setPlaybackSpeed(float)}.
     *
     * The method has to be looked up on a type that is itself public. HBO Max's
     * player class is package-private final, so a Method taken straight from
     * {@code player.getClass()} throws IllegalAccessException on invoke even
     * though the method itself is public. Binding to a public superclass or to
     * the media3 interface avoids that, and virtual dispatch still lands on the
     * real implementation.
     */
    private static Method resolve(Object player) {
        Class<?> type = player.getClass();
        if (resolvedFor == type) return resolvedMethod;

        resolvedFor = type;
        resolvedMethod = null;

        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (!Modifier.isPublic(c.getModifiers())) continue;
            Method method = declaredSetSpeed(c);
            if (method != null) {
                resolvedMethod = method;
                Logger.i("Speed method bound to class " + c.getName());
                return method;
            }
        }

        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> face : c.getInterfaces()) {
                if (!Modifier.isPublic(face.getModifiers())) continue;
                Method method = declaredSetSpeed(face);
                if (method != null) {
                    resolvedMethod = method;
                    Logger.i("Speed method bound to interface " + face.getName());
                    return method;
                }
            }
        }

        Method method = declaredSetSpeed(type);
        if (method != null) {
            try {
                method.setAccessible(true);
                resolvedMethod = method;
                Logger.i("Speed method forced on " + type.getName());
                return method;
            } catch (Throwable ignored) {
                // Falls through to the PlaybackParameters route.
            }
        }

        Logger.i("No reflective speed method on " + type.getName());
        return null;
    }

    private static Method declaredSetSpeed(Class<?> type) {
        try {
            return type.getDeclaredMethod(METHOD_NAME, float.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /**
     * Fallback no-op. Reached only if both reflection routes fail. Left as a
     * clearly-logged dead end rather than throwing into the player.
     */
    public static void setSpeedNative(Object player, float speed) {
        Logger.i("setSpeedNative: no reflective route available for "
                + (player == null ? "null" : player.getClass().getName()));
    }
}
