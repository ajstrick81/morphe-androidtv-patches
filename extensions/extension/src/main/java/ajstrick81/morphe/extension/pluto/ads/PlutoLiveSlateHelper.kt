package ajstrick81.morphe.extension.pluto.ads

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Pluto TV — LIVE commercial-break MASK (issue #152).
 *
 * Live/linear Pluto ads are real broadcast time stitched into the dynamic DASH feed:
 * they can't be removed the way VOD ad periods are (that's the shipped "Skip ads" strip,
 * which deliberately passes live manifests through untouched). The accepted ceiling for
 * live is to **mask** the break — cover the player with a black screen and mute the audio
 * for the duration, then restore when the show returns. This is the ESPN/Paramount live
 * slate pattern applied to Pluto.
 *
 * DETECTOR (decoded on-device 2026-09-07, real live break). Pluto's own
 * {@code ID3AdsBeaconTracker.consumeID3(ID3Tag)} fires ONLY while an ad is playing (it is
 * silent during the show): each ad announces {@code START_OF_MEDIA} then ticks
 * {@code GENERIC_WTA}/{@code QUARTILE_*} every ~2.5–5s; back-to-back ads chain by new
 * {@code creativeId}. So every {@code consumeID3} call = "an ad is on screen right now."
 * We treat each call as an ad tick: show the mask + mute and (re)arm a short hide timer;
 * when the ticks stop (content resumed), the timer fires and we lift the mask + unmute.
 * Event-driven, no polling, and it survives "Skip ads" (that only neuters
 * {@code BeaconTracker.fire}, a different class).
 *
 * All app/player calls are reflection BY NAME so the extension never compiles against the
 * app or Avia modules (and to survive R8 renaming of shared types across the merge).
 */
object PlutoLiveSlateHelper {

    private const val TAG = "MORPHE-PLUTO-SLATE"
    private const val DEBUG = false

    // Runtime opt-out: an empty file named `slate_off` in the app's external files dir
    // disables the live mask entirely (ads play normally). Lets a user turn it off
    // without re-patching. VOD ad removal is unaffected (different patch).
    private const val DISABLE_MARKER = "slate_off"

    // Layout selector: `pluto_slate_mode` marker contents pick the behaviour.
    //   both  (default) — black cover + mute
    //   black          — black cover only (audio left alone)
    //   mute           — mute only (no cover)
    private const val MODE_MARKER = "pluto_slate_mode"

    // Ad-state ticks arrive at most ~5s apart; if none arrives for this long the break
    // is over. A little slack past the tick cadence so the mask never lifts mid-break.
    private const val HIDE_DELAY_MS = 6_500L
    // Absolute backstop: never leave the mask up longer than this without a tick (guards
    // against a stuck state if teardown is missed).
    private const val FAILSAFE_MS = 15L * 60L * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity> = WeakReference(null) // the stable player host Activity
    @Volatile private var aviaPlayer: Any? = null
    private var exoPlayer: Any? = null
    private var mGetVolume: Method? = null
    private var mSetVolume: Method? = null

    private var overlay: FrameLayout? = null
    private var shown = false
    private var muted = false
    private var savedVolume = 1.0f

    private enum class Mode { BOTH, BLACK, MUTE }

    private val hideRunnable = Runnable { lift("no ad ticks (break ended)") }
    private val failsafeRunnable = Runnable { lift("failsafe") }

    // ───────────────────────── injected entry points ─────────────────────────

    /** Injected at AviaPlaybackController.<init> (p1 = the AviaPlayer). Captures the player for muting. */
    @JvmStatic
    fun setAviaPlayer(player: Any?) {
        aviaPlayer = player
        exoPlayer = null // re-resolve lazily against the new player
        mGetVolume = null
        mSetVolume = null
        if (DEBUG) Log.d(TAG, "setAviaPlayer(${player?.javaClass?.simpleName})")
    }

    /**
     * Injected at LeanbackMainHostActivity.onCreate (p0 = the Activity). The stable
     * single-Activity host whose content root covers the player for the whole session
     * (unlike the live-controls fragment, whose view is destroyed when controls auto-hide).
     */
    @JvmStatic
    fun registerActivity(activity: Activity?) {
        if (activity != null) activityRef = WeakReference(activity)
        if (DEBUG) Log.d(TAG, "registerActivity(${activity?.javaClass?.simpleName})")
    }

    /** Injected at LeanbackMainHostActivity.onDestroy. Host gone — lift and clear. */
    @JvmStatic
    fun unregisterActivity(activity: Activity?) {
        if (activityRef.get() === activity) {
            mainHandler.post { lift("host activity destroyed") }
            activityRef = WeakReference(null)
        }
    }

    /**
     * Injected at ID3AdsBeaconTracker.consumeID3(ID3Tag). Every call = an ad is on screen.
     * Shows the mask + mutes and (re)arms the hide timer; the timer lifts it when ticks stop.
     */
    @JvmStatic
    fun onAdTick(id3Tag: Any?) {
        mainHandler.post { showOrKeep() }
    }

    // ───────────────────────────── mask lifecycle ────────────────────────────

    private fun showOrKeep() {
        val mode = readMode()
        if (mode == null) { // opt-out marker present
            if (shown) lift("opt-out marker")
            return
        }
        // Re-arm the timers on every tick.
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
        if (!shown) {
            mainHandler.removeCallbacks(failsafeRunnable)
            mainHandler.postDelayed(failsafeRunnable, FAILSAFE_MS)
        }

        if (mode == Mode.BOTH || mode == Mode.MUTE) mute()
        if (mode == Mode.BOTH || mode == Mode.BLACK) showCover()
        if (!shown) Log.d(TAG, "live ad break -> mask ON (mode=$mode)")
        shown = true
    }

    private fun lift(reason: String) {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(failsafeRunnable)
        if (!shown) return
        removeCover()
        unmute()
        shown = false
        Log.d(TAG, "live ad break -> mask OFF ($reason)")
    }

    private fun showCover() {
        if (overlay != null) return
        val root = contentRoot() ?: run {
            Log.w(TAG, "showCover: no content root — fragment activity unavailable")
            return
        }
        val cover = FrameLayout(root.context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = false
            // Sit above the player's video surface: max elevation/translationZ so we win
            // z-order even when the video is a SurfaceView drawn late.
            elevation = 1_000_000f
            translationZ = 1_000_000f
        }
        root.addView(
            cover,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        cover.bringToFront()
        root.invalidate()
        Log.i(TAG, "showCover: cover added to ${root.javaClass.simpleName} childCount=${root.childCount}")
        overlay = cover
    }

    private fun removeCover() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    // ExoPlayer volume is a float 0f..1f with get + set — read once at mute so we can
    // restore exactly (the player normally runs at full volume; TV/system controls the
    // rest). Reflected by name to avoid compiling against Avia/media3-exoplayer.
    private fun mute() {
        if (muted) return
        val exo = resolveExo() ?: return
        try {
            val get = mGetVolume ?: exo.javaClass.getMethod("getVolume").also { mGetVolume = it }
            val set = mSetVolume ?: exo.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                .also { mSetVolume = it }
            savedVolume = (get.invoke(exo) as? Float) ?: 1.0f
            set.invoke(exo, 0.0f)
            muted = true
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "mute failed: $t")
        }
    }

    private fun unmute() {
        if (!muted) return
        val exo = resolveExo() ?: run { muted = false; return }
        try {
            val set = mSetVolume ?: exo.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                .also { mSetVolume = it }
            set.invoke(exo, savedVolume)
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "unmute failed: $t")
        }
        muted = false
    }

    // AviaPlayer.player is a public field of type androidx.media3.exoplayer.ExoPlayer.
    private fun resolveExo(): Any? {
        exoPlayer?.let { return it }
        val avia = aviaPlayer ?: return null
        return try {
            val f = avia.javaClass.getField("player")
            f.get(avia)?.also { exoPlayer = it }
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "resolveExo failed: $t")
            null
        }
    }

    // ─────────────────────────────── helpers ─────────────────────────────────

    private fun contentRoot(): ViewGroup? {
        val activity = activityRef.get() ?: return null
        return try {
            activity.findViewById(android.R.id.content) as? ViewGroup
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "contentRoot failed: $t")
            null
        }
    }

    /** Read the mode marker; null means opt-out (`slate_off` present). Default BOTH. */
    private fun readMode(): Mode? {
        val ctx = activityRef.get()
        val dir = try { ctx?.getExternalFilesDir(null) } catch (_: Throwable) { null } ?: return Mode.BOTH
        try {
            if (java.io.File(dir, DISABLE_MARKER).exists()) return null
        } catch (_: Throwable) {}
        val token = try {
            java.io.File(dir, MODE_MARKER).takeIf { it.exists() }?.readText()?.trim()?.lowercase()
        } catch (_: Throwable) { null }
        return when (token) {
            "black" -> Mode.BLACK
            "mute" -> Mode.MUTE
            else -> Mode.BOTH
        }
    }
}
