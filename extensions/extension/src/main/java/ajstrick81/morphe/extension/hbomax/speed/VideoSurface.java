package ajstrick81.morphe.extension.hbomax.speed;

import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * Finds the view the video is rendered into, so the speed chip is only shown on
 * a playback screen and not over the browse UI.
 *
 * HBO Max renders through media3, which means either a SurfaceView (the default,
 * and the only option for L1-protected output) or a TextureView. Rather than
 * fingerprinting the app's player view — a different obfuscated class in every
 * release — the surface is located by walking the Activity's view tree, which is
 * framework API and cannot be obfuscated. The largest candidate wins, because a
 * player screen can legitimately contain more than one (thumbnail scrubber, ad
 * surface, PiP stub).
 */
final class VideoSurface {
    /** Anything smaller than this is a thumbnail, not the video. */
    private static final int MINIMUM_EDGE_PIXELS = 160;

    private VideoSurface() {
    }

    static View find(View root) {
        return find(root, MINIMUM_EDGE_PIXELS, MINIMUM_EDGE_PIXELS);
    }

    /**
     * Largest visible surface at least {@code minWidth} x {@code minHeight}.
     * The controller passes ~75% of the window so home-screen hero/trailer
     * surfaces don't count as "the player" (issue #258 launch flicker).
     */
    static View find(View root, int minWidth, int minHeight) {
        return findLargest(root, null,
                Math.max(minWidth, MINIMUM_EDGE_PIXELS), Math.max(minHeight, MINIMUM_EDGE_PIXELS));
    }

    private static View findLargest(View view, View best, int minWidth, int minHeight) {
        if (view == null) return best;

        if (view instanceof SurfaceView || view instanceof TextureView) {
            if (view.getWidth() >= minWidth
                    && view.getHeight() >= minHeight
                    && view.isShown()
                    && (best == null || area(view) > area(best))) {
                best = view;
            }
            return best;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = findLargest(group.getChildAt(i), best, minWidth, minHeight);
            }
        }

        return best;
    }

    private static long area(View view) {
        return (long) view.getWidth() * view.getHeight();
    }
}
