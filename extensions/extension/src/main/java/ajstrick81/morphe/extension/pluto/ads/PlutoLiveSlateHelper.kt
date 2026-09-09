package ajstrick81.morphe.extension.pluto.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
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
    // Stream-level mute state (belt for ad audio that bypasses the content ExoPlayer — e.g.
    // a separate live-ad player on some channels; see issue #152 field report).
    private var streamMuted = false
    private var savedStreamVolume = -1

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
        // Belt-and-suspenders: silence the media STREAM first (covers ad audio that plays
        // through a separate pipeline from the content ExoPlayer — the #152 field failure),
        // then also mute the content ExoPlayer we hold (cheap, and restores exact gain).
        muteStream()
        muteExo()
    }

    private fun unmute() {
        unmuteExo()
        unmuteStream()
    }

    private fun muteExo() {
        if (muted) return
        val exo = resolveExo() ?: run {
            Log.w(TAG, "muteExo: no ExoPlayer resolved (aviaPlayer=${aviaPlayer?.javaClass?.name})")
            return
        }
        try {
            val get = mGetVolume ?: exo.javaClass.getMethod("getVolume").also { mGetVolume = it }
            val set = mSetVolume ?: exo.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                .also { mSetVolume = it }
            savedVolume = (get.invoke(exo) as? Float) ?: 1.0f
            set.invoke(exo, 0.0f)
            val after = (get.invoke(exo) as? Float)
            muted = true
            Log.i(TAG, "muteExo: OK exo=${exo.javaClass.name} saved=$savedVolume after=$after")
        } catch (t: Throwable) {
            Log.w(TAG, "muteExo failed on ${exo.javaClass.name}: $t")
        }
    }

    private fun unmuteExo() {
        if (!muted) return
        val exo = resolveExo() ?: run { muted = false; return }
        try {
            val set = mSetVolume ?: exo.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                .also { mSetVolume = it }
            set.invoke(exo, savedVolume)
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "unmuteExo failed: $t")
        }
        muted = false
    }

    // Stream-level mute of STREAM_MUSIC (the app's media output). Instance- and pipeline-
    // agnostic: silences whatever player produces the ad audio. Save/restore the exact level
    // so we return the user to their prior volume; the failsafe/host-destroy teardown lifts
    // this too, so a missed unmute never leaves the device muted for long.
    private fun muteStream() {
        if (streamMuted) return
        val am = audioManager() ?: return
        try {
            savedStreamVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            streamMuted = true
            Log.i(TAG, "muteStream: OK saved=$savedStreamVolume now=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        } catch (t: Throwable) {
            Log.w(TAG, "muteStream failed: $t")
        }
    }

    private fun unmuteStream() {
        if (!streamMuted) return
        val am = audioManager()
        if (am != null && savedStreamVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamVolume, 0)
                Log.i(TAG, "unmuteStream: restored=$savedStreamVolume")
            } catch (t: Throwable) {
                if (DEBUG) Log.w(TAG, "unmuteStream failed: $t")
            }
        }
        streamMuted = false
        savedStreamVolume = -1
    }

    private fun audioManager(): AudioManager? {
        val ctx = activityRef.get() ?: return null
        return try {
            ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "audioManager unavailable: $t")
            null
        }
    }

    // AviaPlayer.player is a public field of type androidx.media3.exoplayer.ExoPlayer.
    private fun resolveExo(): Any? {
        exoPlayer?.let { return it }
        val avia = aviaPlayer ?: run {
            Log.w(TAG, "resolveExo: aviaPlayer is null (init hook never fired?)")
            return null
        }
        // Try the public `player` field first; if absent, dump candidate fields so we can
        // see what the ExoPlayer is actually stored as on this build/content.
        return try {
            val f = avia.javaClass.getField("player")
            f.get(avia)?.also { exoPlayer = it }
        } catch (t: Throwable) {
            Log.w(TAG, "resolveExo: no public `player` field on ${avia.javaClass.name}: $t")
            try {
                avia.javaClass.declaredFields.joinToString { "${it.name}:${it.type.simpleName}" }
                    .let { Log.w(TAG, "resolveExo: declaredFields = $it") }
            } catch (_: Throwable) {}
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
