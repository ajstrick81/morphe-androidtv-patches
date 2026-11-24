package ajstrick81.morphe.extension.hbomax.speed;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the audio on a path that can actually change speed while the user has
 * picked anything other than 1x (issue #314).
 *
 * media3's DefaultAudioSink can only time-stretch decoded PCM. When the audio
 * is passed through undecoded (E-AC-3 5.1 sent straight to an HDMI soundbar or
 * receiver) or when tunneled playback is on, it silently resets the speed to
 * 1x, and the video clock follows the audio, so the whole player stays at 1x
 * while the chip shows the new value. "Prefer Ad-Free Stream" played the
 * stereo-AAC FALLBACK, so this never showed; "Remove Ad Periods (keep 5.1)"
 * keeps the E-AC-3 PRIMARY stream, and live-event replays have no fallback,
 * so both hit it.
 *
 * Fix: while the speed is not 1x, ask HBO's own track selector wrapper
 * (com.discovery.player.tracks.DefaultExoPlayerTrackSelectorWrapper, an
 * unobfuscated public API) to prefer AAC and to stop tunneling. AAC is decoded
 * to PCM, so the speed applies. Back at 1x, HBO's own codec preference is
 * restored, so 5.1 comes back. Language choice is unaffected: media3 ranks
 * language above MIME type. If a title has no AAC track, nothing changes.
 *
 * Every call is reflective and fails soft: a renamed method just logs.
 */
public final class AudioPathGuard {
    /** media3 MimeTypes.AUDIO_AAC. */
    private static final String AAC = "audio/mp4a-latm";

    /** Wrapper -> HBO's own preferred audio codecs, saved before we overrode them. */
    private static final Map<Object, List<String>> saved = new WeakHashMap<>();
    /** Live wrappers. Weak keys; the value is unused. */
    private static final Map<Object, Boolean> wrappers = new WeakHashMap<>();

    private AudioPathGuard() {
    }

    /**
     * Called from the patched DefaultExoPlayerTrackSelectorWrapper constructor.
     * Must not throw.
     */
    public static void onTrackSelectorCreated(Object wrapper) {
        try {
            if (wrapper == null) return;
            synchronized (wrappers) {
                wrappers.put(wrapper, Boolean.TRUE);
            }
            Logger.i("Track selector captured: " + wrapper.getClass().getName());
        } catch (Throwable t) {
            Logger.e("onTrackSelectorCreated failed", t);
        }
    }

    /** @return true while some wrapper still carries our AAC override. */
    static boolean overriding() {
        synchronized (wrappers) {
            return !saved.isEmpty();
        }
    }

    /**
     * Brings every live wrapper in line with [speed]. Cheap when nothing needs
     * to change, so it is safe to call on every enforcer tick: HBO may re-apply
     * its own codec preference when a new title starts, and this puts ours back.
     */
    static void sync(float speed) {
        List<Object> live;
        synchronized (wrappers) {
            live = new ArrayList<>(wrappers.keySet());
        }
        boolean normal = Math.abs(speed - 1f) < 0.01f;
        for (Object wrapper : live) {
            try {
                if (normal) restore(wrapper);
                else preferPcm(wrapper);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                Logger.e("Audio path sync failed on " + wrapper.getClass().getName(), cause);
            }
        }
    }

    private static void preferPcm(Object wrapper) throws Exception {
        List<String> current = currentCodecs(wrapper);
        if (current != null && current.size() == 1 && AAC.equals(current.get(0))) return;

        synchronized (wrappers) {
            if (!saved.containsKey(wrapper)) {
                // Copy: HBO hands out its live list and clears it on every set.
                saved.put(wrapper, current == null
                        ? new ArrayList<String>() : new ArrayList<>(current));
            }
        }

        invoke(wrapper, "disableTunneling", null);
        invoke(wrapper, "setPreferredAudioCodecs",
                new ArrayList<>(Collections.singletonList(AAC)));
        Logger.i("Speed != 1x: preferring AAC (PCM) audio and disabling tunneling; "
                + "HBO's codec preference was " + current);
    }

    private static void restore(Object wrapper) throws Exception {
        List<String> original;
        synchronized (wrappers) {
            original = saved.remove(wrapper);
        }
        if (original == null) return;

        invoke(wrapper, "setPreferredAudioCodecs", new ArrayList<>(original));
        Logger.i("Speed back to 1x: restored HBO's audio codec preference " + original);
    }

    @SuppressWarnings("unchecked")
    private static List<String> currentCodecs(Object wrapper) throws Exception {
        Object value = method(wrapper, "getCurrentPreferredAudioCodecs", false).invoke(wrapper);
        return value instanceof List ? (List<String>) value : null;
    }

    private static void invoke(Object wrapper, String name, List<String> argument)
            throws Exception {
        Method method = method(wrapper, name, argument != null);
        if (argument == null) method.invoke(wrapper);
        else method.invoke(wrapper, argument);
    }

    /** Public method by name, looked up via public types so invoke is allowed. */
    private static Method method(Object wrapper, String name, boolean takesList)
            throws NoSuchMethodException {
        Class<?>[] parameters = takesList ? new Class<?>[]{List.class} : new Class<?>[0];
        try {
            return wrapper.getClass().getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            for (Class<?> face : wrapper.getClass().getInterfaces()) {
                try {
                    return face.getMethod(name, parameters);
                } catch (NoSuchMethodException ignored) {
                    // Try the next interface.
                }
            }
            throw absent;
        }
    }
}
