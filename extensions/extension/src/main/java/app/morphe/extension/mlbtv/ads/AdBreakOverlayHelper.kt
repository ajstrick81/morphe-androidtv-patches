package app.morphe.extension.mlbtv.ads

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Shows/hides a full-screen "Commercial Break in Progress" overlay across
 * IMA SSAI/DAI ad breaks, so harmful gambling ad content is never seen or
 * heard while still letting the underlying ad break play out untouched
 * (safe for VOD and live alike).
 *
 * registerAdViewGroup() is invoked once per playback session from
 * Lb6/h$d;->b() with the same ViewGroup the IMA SDK itself overlays
 * companion ads/friendly obstructions onto — guaranteed to be sized and
 * positioned over the video surface already. showOverlay()/hideOverlay()
 * are invoked from Lb6/h$i;->onAdBreakStarted()/onAdBreakEnded(), which
 * the IMA SDK calls on the main thread per the VideoStreamPlayer contract.
 */
object AdBreakOverlayHelper {

    private var adViewGroupRef: WeakReference<ViewGroup>? = null
    private var overlay: FrameLayout? = null

    @JvmStatic
    fun registerAdViewGroup(adViewGroup: ViewGroup) {
        adViewGroupRef = WeakReference(adViewGroup)
    }

    @JvmStatic
    fun showOverlay() {
        if (overlay != null) return
        val container = adViewGroupRef?.get() ?: return

        val label = TextView(container.context).apply {
            text = "Commercial Break in Progress"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val layout = FrameLayout(container.context).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }

        container.addView(
            layout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay = layout
    }

    @JvmStatic
    fun hideOverlay() {
        val layout = overlay ?: return
        adViewGroupRef?.get()?.removeView(layout)
        overlay = null
    }
}
