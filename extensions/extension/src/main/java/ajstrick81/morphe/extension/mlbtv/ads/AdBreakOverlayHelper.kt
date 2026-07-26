package ajstrick81.morphe.extension.mlbtv.ads

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Shows/hides a full-screen "Commercial Break in Progress" overlay over the
 * IMA SSAI ad-view-group during live commercial breaks, shielding viewers from
 * gambling ad content without touching any playback/DAI request plumbing.
 *
 * Triggering: the PRIMARY, IMA-independent trigger is [signalAdBreak], called
 * by [MlbManifestRewriter] whenever it sees ad markers in a live media
 * playlist — the ground-truth signal that a break is on air. The IMA
 * onAdBreakStarted()/onAdBreakEnded() hooks ([showOverlay]/[hideOverlay],
 * AtBatPatch Patch 5b/5c) are kept as a secondary trigger; they were never
 * observed firing in field tests, most likely because Patches 2/3/4 suppress
 * the IMA machinery that emits them, which is exactly why the manifest path
 * now drives the overlay.
 */
object AdBreakOverlayHelper {

    private const val TAG = "MORPHE-MLB-ADBREAK"

    /**
     * How long after the most recent ad-break signal to auto-hide the overlay.
     *
     * During a live break the media playlist is re-fetched roughly every
     * target-duration (typically 2-6s) and every fetch that still carries ad
     * markers re-arms this timer, so the overlay stays up for the whole break
     * and clears ~this long after manifests stop carrying ad markers (i.e.
     * content has resumed). Sized comfortably above the playlist refresh
     * interval so a single briefly-clean refresh mid-break can't cause flicker.
     * This timer also protects against IMA firing onAdBreakStarted() without a
     * matching onAdBreakEnded() — the overlay self-heals instead of sticking.
     */
    private const val AD_BREAK_HIDE_DELAY_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var adViewGroup: ViewGroup? = null
    private var overlayView: FrameLayout? = null

    private val hideRunnable = Runnable { hideOverlayNow() }

    @JvmStatic
    fun registerAdViewGroup(viewGroup: ViewGroup) {
        adViewGroup = viewGroup
        Log.d(TAG, "registerAdViewGroup() — ad view group registered")
    }

    /**
     * Primary, IMA-independent ad-break trigger. Called from
     * [MlbManifestRewriter] each time a media playlist carrying ad markers is
     * observed. Shows the overlay (if not already shown) and (re)arms the
     * auto-hide timer. Safe to call from any thread.
     */
    @JvmStatic
    fun signalAdBreak() {
        mainHandler.post {
            showOverlayNow()
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, AD_BREAK_HIDE_DELAY_MS)
        }
    }

    /**
     * Secondary trigger for IMA's onAdBreakStarted() hook (AtBatPatch Patch
     * 5b). Routed through the same timer-armed path as [signalAdBreak] so a
     * missing onAdBreakEnded() can't leave the overlay stuck on screen.
     */
    @JvmStatic
    fun showOverlay() = signalAdBreak()

    /**
     * IMA's onAdBreakEnded() hook (AtBatPatch Patch 5c). Cancels the auto-hide
     * timer and hides immediately.
     */
    @JvmStatic
    fun hideOverlay() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            hideOverlayNow()
        }
    }

    private fun showOverlayNow() {
        val container = adViewGroup
        if (container == null) {
            // If this fires during a known break, the IMA display-container
            // path (Patch 5a) never ran — the overlay has nothing to attach to.
            Log.w(TAG, "ad break signaled but no ad view group registered — overlay cannot be shown")
            return
        }
        val overlay = overlayView ?: buildOverlay(container).also { overlayView = it }
        if (overlay.parent == null) container.addView(overlay)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        Log.d(TAG, "overlay shown — commercial break in progress")
    }

    private fun hideOverlayNow() {
        overlayView?.visibility = View.GONE
        Log.d(TAG, "overlay hidden — commercial break ended")
    }

    private fun buildOverlay(container: ViewGroup): FrameLayout {
        val context = container.context
        val label = TextView(context).apply {
            text = "Commercial Break in Progress"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }
}
