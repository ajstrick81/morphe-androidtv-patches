package ajstrick81.morphe.extension.espn.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * ESPN Android TV — "COMMERCIAL BREAK / WE'LL BE RIGHT BACK" slate + mute over
 * live passthrough-SSAI commercials, cleared when the game returns.
 *
 * TRIGGER (learned on-device 2026-09-04): live passthrough breaks emit NO Kotlin
 * PlaybackSessionEvent at all — not BreakStartedEvent, not BreakContentStartedEvent
 * (both zero across a full game log). The DMP engine handles passthrough purely
 * internally. The ONLY app-reachable source of the break windows is
 * DisneyMediaPlaybackSession.getBreaks() (returns every scheduled BreakInfo with
 * getStartPosition()/getDuration() in the playhead timeline). Nothing in ESPN
 * calls it during passive playback, so we poll it ourselves and check whether the
 * live playhead is inside a window:
 *   - [setSession] captures the session (injected at MediaPlayerViewModel
 *     .attachSession) and starts a 1 s poll of getBreaks() (via reflection +
 *     Continuation, to avoid compiling against com.disney.dmp / the app module).
 *   - [onPlayerEvent] gets every PlaybackSessionEvent (injected at
 *     sessionListener.onEvent); TimelineProgressEvent carries the playhead, which
 *     we test against the polled windows.
 * Playhead in a window → slate + mute; out → clear. Failsafe bounds the slate.
 */
object EspnAdBreakOverlayHelper {

    private const val TAG = "MORPHE-ESPN-SLATE"
    private const val DEBUG = false

    // Runtime opt-out: if the viewer drops an empty file named `slate_off` in the
    // app's external files dir, the live-break slate is disabled (ads play as
    // normal); VOD/interstitial suppression via isAdDisabled is unaffected. Lets
    // users turn the slate off without repatching.
    private const val DISABLE_MARKER = "slate_off"

    private const val POLL_INTERVAL_MS = 1_000L
    private const val FAILSAFE_HIDE_DELAY_MS = 300_000L
    private const val EDGE_TOLERANCE_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideSlateNow("failsafe") }

    private var activityRef: WeakReference<Activity> = WeakReference(null)
    private var currentOverlay: FrameLayout? = null
    private var videoView: android.widget.VideoView? = null
    private var rotationIndex = 0            // advances each break for media round-robin
    private var didMute = false
    private var shown = false

    // Captured DMP session + cached reflection handles.
    @Volatile private var session: Any? = null
    private var mGetPlaybackSession: Method? = null
    private var mGetDataSource: Method? = null
    private var mDsGetBreaks: Method? = null
    private var mGetAudioControl: Method? = null
    private var mSetMuted: Method? = null
    private var mBreakStart: Method? = null
    private var mBreakDuration: Method? = null
    private var mGetTimeline: Method? = null
    private var mGetPlayhead: Method? = null

    // Feed B1 — getBreaks() windows [startMs, endMs] in the playhead timeline.
    @Volatile private var windowStart = LongArray(0)
    @Volatile private var windowEnd = LongArray(0)
    // Feed B2 — DateRange windows in ABSOLUTE epoch-ms (from playlistRetrieved),
    // keyed by break id and ACCUMULATED across refreshes: a break can drop out of
    // a later playlist while its ad is still playing, so we never wholesale-clear;
    // windows persist until the playhead passes their end (+margin) or they expire.
    // START-DATE sets [start, start+plannedDuration]; a later END-DATE refines end.
    private val winStartById = HashMap<String, Long>()
    private val winEndById = HashMap<String, Long>()
    // Extra trailing hold after a window's end, so the slate doesn't lift a beat
    // before live content actually resumes.
    private const val WINDOW_TRAILING_MS = 4_000L

    private var lastPlayhead = Long.MIN_VALUE       // media-timeline ms
    private var lastPlayheadAbs = Long.MIN_VALUE    // epoch ms (zeroPDT + playhead)
    private var lastActive = false
    private var mGetZeroPdt: Method? = null
    private var dbgTick = 0

    // ─────────────────────────── lifecycle / host ──────────────────────────
    @JvmStatic
    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        Log.d(TAG, "registerActivity() — host container registered")
    }

    @JvmStatic
    fun unregisterActivity(activity: Activity) {
        if (activityRef.get() === activity) {
            mainHandler.post { hideSlateNow("activity-paused") }
            activityRef = WeakReference(null)
        }
    }

    // ─────────────────────────── session + polling ─────────────────────────
    @JvmStatic
    fun setSession(session: Any?) {
        this.session = session
        Log.d(TAG, "setSession(${session?.javaClass?.simpleName})")
        mainHandler.removeCallbacks(pollRunnable)
        if (session != null) mainHandler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollBreaks()
            if (session != null) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun pollBreaks() {
        val s = session ?: return
        try {
            // EspnPlaybackSession.getBreaks() is just:
            //   getPlaybackSession().getDataSource().getBreaks()
            // and that chain is fully SYNCHRONOUS (no Continuation) — call it
            // directly. Look up by name with 0 params to avoid the suspend
            // overloads / R8-renamed Continuation param types.
            val gps = mGetPlaybackSession ?: byName(s.javaClass, "getPlaybackSession")
                ?.also { mGetPlaybackSession = it } ?: return debugNoMethod("getPlaybackSession", s)
            val ps = gps.invoke(s) ?: return
            val gds = mGetDataSource ?: byName(ps.javaClass, "getDataSource")
                ?.also { mGetDataSource = it } ?: return debugNoMethod("getDataSource", ps)
            val ds = gds.invoke(ps) ?: return
            val gb = mDsGetBreaks ?: byName(ds.javaClass, "getBreaks")
                ?.also { mDsGetBreaks = it } ?: return debugNoMethod("dataSource.getBreaks", ds)
            handleBreaks(gb.invoke(ds))
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "getBreaks poll failed: $t")
        }
    }

    private fun byName(cls: Class<*>, name: String): Method? =
        cls.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }

    private fun debugNoMethod(name: String, obj: Any) {
        if (DEBUG) Log.w(TAG, "$name() not found on ${obj.javaClass.name}")
    }

    private fun handleBreaks(result: Any?) {
        val list = result as? List<*> ?: return
        val starts = LongArray(list.size)
        val ends = LongArray(list.size)
        var n = 0
        for (bi in list) {
            if (bi == null) continue
            try {
                val ms = mBreakStart ?: bi.javaClass.getMethod("getStartPosition").also { mBreakStart = it }
                val md = mBreakDuration ?: bi.javaClass.getMethod("getDuration").also { mBreakDuration = it }
                val start = (ms.invoke(bi) as? Long) ?: continue
                val dur = (md.invoke(bi) as? Long) ?: 60_000L
                starts[n] = start
                ends[n] = start + dur
                n++
            } catch (t: Throwable) {
                if (DEBUG) Log.w(TAG, "BreakInfo reflection failed: $t")
                return
            }
        }
        windowStart = starts.copyOf(n)
        windowEnd = ends.copyOf(n)
        if (DEBUG) {
            val detail = if (n > 0) "first=[${windowStart[0]}..${windowEnd[0]}] last=[${windowStart[n - 1]}..${windowEnd[n - 1]}]" else ""
            Log.d(TAG, "getBreaks -> $n breaks $detail playhead=$lastPlayhead")
        }
        if (lastPlayhead != Long.MIN_VALUE) evaluate(lastPlayhead, lastPlayheadAbs)
    }

    // ───────────────────── Feed B2: manifest DateRanges ────────────────────
    /**
     * Injected at SgaiPlaybackSession.playlistRetrieved(DateTime, List<DateRange>);
     * receives the DateRange list (p2). Each DateRange is just {id, map}; we read
     * the map for CLASS=com.disney.media.break.v1 + START-DATE + PLANNED-DURATION
     * and store the absolute [startMs, endMs] window.
     */
    @JvmStatic
    fun onDateRanges(list: Any?) {
        val ranges = list as? List<*> ?: return
        var updated = 0
        for (dr in ranges) {
            if (dr == null) continue
            try {
                val map = (dr.javaClass.getMethod("getMap").invoke(dr)) as? Map<*, *> ?: continue
                if (map["CLASS"]?.toString() != "com.disney.media.break.v1") continue
                val id = map["ID"]?.toString() ?: continue
                val startStr = map["START-DATE"]?.toString()
                val endStr = map["END-DATE"]?.toString()
                if (startStr != null) {
                    val startMs = java.time.Instant.parse(startStr).toEpochMilli()
                    val durSec = map["PLANNED-DURATION"]?.toString()?.toDoubleOrNull() ?: 60.0
                    synchronized(winStartById) {
                        winStartById[id] = startMs
                        // only seed end from duration if we don't already have a real END-DATE
                        winEndById.putIfAbsent(id, startMs + (durSec * 1000).toLong())
                    }
                    updated++
                }
                if (endStr != null) {
                    val endMs = java.time.Instant.parse(endStr).toEpochMilli()
                    synchronized(winStartById) { winEndById[id] = endMs }  // END-DATE is authoritative
                    updated++
                }
            } catch (t: Throwable) {
                if (DEBUG) Log.w(TAG, "DateRange parse failed: $t")
            }
        }
        pruneWindows()
        if (DEBUG) Log.d(TAG, "onDateRanges -> updated=$updated live-windows=${winStartById.size} playheadAbs=$lastPlayheadAbs")
        if (lastPlayhead != Long.MIN_VALUE) evaluate(lastPlayhead, lastPlayheadAbs)
    }

    // Drop windows whose end is well behind the playhead so the maps don't grow.
    private fun pruneWindows() {
        val ph = lastPlayheadAbs
        if (ph == Long.MIN_VALUE) return
        synchronized(winStartById) {
            val it = winEndById.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value + WINDOW_TRAILING_MS + 60_000L < ph) { winStartById.remove(e.key); it.remove() }
            }
        }
    }

    // ─────────────────────────── playhead feed ─────────────────────────────
    @JvmStatic
    fun onPlayerEvent(event: Any?) {
        if (event == null || event.javaClass.simpleName != "TimelineProgressEvent") return
        try {
            val gt = mGetTimeline ?: event.javaClass.getMethod("getTimeline").also { mGetTimeline = it }
            val timeline = gt.invoke(event) ?: return
            val gp = mGetPlayhead ?: timeline.javaClass.getMethod("getPlayheadPosition").also { mGetPlayhead = it }
            val pos = gp.invoke(timeline) as? Long ?: return
            val gz = mGetZeroPdt ?: timeline.javaClass.getMethod("getZeroPositionProgramDateTime").also { mGetZeroPdt = it }
            val zero = gz.invoke(timeline) as? Long ?: 0L
            lastPlayhead = pos
            lastPlayheadAbs = if (zero != 0L) zero + pos else Long.MIN_VALUE
            if (DEBUG && (dbgTick++ % 10 == 0)) {
                val w0 = synchronized(winStartById) {
                    winStartById.entries.firstOrNull()?.let { "win0=[${it.value}..${winEndById[it.key]}]" } ?: "win0=none"
                }
                Log.d(TAG, "raw playhead=$pos zeroPDT=$zero abs=$lastPlayheadAbs live-wins=${winStartById.size} $w0")
            }
            evaluate(pos, lastPlayheadAbs)
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "playhead reflection failed: $t")
        }
    }

    private fun evaluate(playhead: Long, playheadAbs: Long) {
        var active = false
        val ws = windowStart; val we = windowEnd
        for (i in ws.indices) {
            if (playhead >= ws[i] - EDGE_TOLERANCE_MS && playhead <= we[i] + EDGE_TOLERANCE_MS) { active = true; break }
        }
        if (!active && playheadAbs != Long.MIN_VALUE) {
            synchronized(winStartById) {
                for ((id, start) in winStartById) {
                    val end = winEndById[id] ?: continue
                    if (playheadAbs >= start - EDGE_TOLERANCE_MS && playheadAbs <= end + WINDOW_TRAILING_MS) { active = true; break }
                }
            }
        }
        if (active != lastActive) {
            lastActive = active
            Log.d(TAG, "playhead=$playhead abs=$playheadAbs -> ${if (active) "IN AD WINDOW" else "content"}")
        }
        if (active) mainHandler.post { showSlateNow() } else mainHandler.post { hideSlateNow("out-of-window") }
    }

    // ─────────────────────────────── slate UI ──────────────────────────────
    private fun showSlateNow() {
        if (shown) { rearmFailsafe(); return }
        val container = contentRoot() ?: run {
            Log.w(TAG, "no host container — Activity not registered?")
            return
        }
        // Runtime opt-out: user placed a `slate_off` marker → leave the ad alone.
        try {
            if (container.context.getExternalFilesDir(null)?.let { java.io.File(it, DISABLE_MARKER).exists() } == true) return
        } catch (_: Throwable) {}
        // Build a FRESH overlay each break so the media round-robins (and any
        // VideoView gets a clean start/stop lifecycle).
        val overlay = buildSlate(container.context)
        currentOverlay = overlay
        container.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.bringToFront()
        videoView?.let { vv -> vv.requestFocus(); vv.start() }
        mute(container.context)
        shown = true
        rearmFailsafe()
        Log.d(TAG, "slate shown + audio muted")
    }

    private fun hideSlateNow(reason: String) {
        mainHandler.removeCallbacks(hideRunnable)
        if (!shown) return
        videoView?.let { try { it.stopPlayback() } catch (_: Throwable) {} }
        videoView = null
        currentOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentOverlay = null
        activityRef.get()?.let { unmute(it) }
        shown = false
        Log.d(TAG, "slate hidden — $reason")
    }

    private fun rearmFailsafe() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, FAILSAFE_HIDE_DELAY_MS)
    }

    private fun contentRoot(): ViewGroup? =
        activityRef.get()?.findViewById(android.R.id.content) as? ViewGroup

    // Mute the DMP player itself via its AudioControl:
    //   session.getPlaybackSession().getAudioControl().setMuted(bool)   [all synchronous]
    // AudioManager.STREAM_MUSIC muting does NOT affect the player's audio track.
    private fun setPlayerMuted(muted: Boolean) {
        val s = session ?: return
        try {
            val ps = (mGetPlaybackSession ?: byName(s.javaClass, "getPlaybackSession")
                ?.also { mGetPlaybackSession = it })?.invoke(s) ?: return
            val ac = (mGetAudioControl ?: byName(ps.javaClass, "getAudioControl")
                ?.also { mGetAudioControl = it })?.invoke(ps) ?: return
            val sm = mSetMuted ?: ac.javaClass.methods.firstOrNull {
                it.name == "setMuted" && it.parameterTypes.size == 1
            }?.also { mSetMuted = it } ?: return
            sm.invoke(ac, muted)
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, "setPlayerMuted failed: $t")
        }
    }

    private fun mute(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (didMute) return
        setPlayerMuted(true)
        didMute = true
    }

    private fun unmute(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (!didMute) return
        setPlayerMuted(false)
        didMute = false
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private val SLATE_EXT_VIDEO = setOf("mp4", "mkv", "webm", "3gp")
    private val SLATE_EXT_IMAGE = setOf("png", "jpg", "jpeg", "webp")

    // User-supplied slate media, round-robined one per break. Nothing is bundled
    // in the patch — the viewer drops files on their own device. Discovered from
    // the app's external files dir (no storage permission needed), sorted by name:
    //   /sdcard/Android/data/com.espn.score_center/files/espn_slate_1.mp4
    //   /sdcard/Android/data/com.espn.score_center/files/espn_slate_2.mp4  ...
    // (a single legacy /sdcard/Download/espn_slate.png is also honored).
    private fun slateMediaFiles(context: Context): List<java.io.File> {
        val out = ArrayList<java.io.File>()
        try {
            context.getExternalFilesDir(null)?.listFiles { f ->
                f.isFile && f.name.lowercase().startsWith("espn_slate") &&
                    (SLATE_EXT_VIDEO.contains(f.extension.lowercase()) || SLATE_EXT_IMAGE.contains(f.extension.lowercase()))
            }?.let { out.addAll(it) }
        } catch (_: Throwable) {}
        val legacy = java.io.File("/sdcard/Download/espn_slate.png")
        if (out.isEmpty() && legacy.canRead()) out.add(legacy)
        return out.sortedBy { it.name.lowercase() }
    }

    // Full-screen looping video overlay with its own audio. The DMP player is
    // muted via AudioControl (not the system STREAM_MUSIC), so this video's sound
    // plays through normally. Stopped/released in hideSlateNow().
    private fun buildVideoOverlay(context: Context, file: java.io.File): FrameLayout {
        Log.d(TAG, "slate media (video) ${file.name}")
        val vv = android.widget.VideoView(context).apply {
            setVideoPath(file.absolutePath)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                start()
            }
            setOnErrorListener { _, what, extra ->
                if (DEBUG) Log.w(TAG, "video overlay error what=$what extra=$extra (${file.name})")
                true
            }
        }
        videoView = vv
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true
            addView(
                vv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER,
                ),
            )
        }
    }

    private fun buildSlate(context: Context): FrameLayout {
        // 1) User media, round-robined per break.
        val media = slateMediaFiles(context)
        if (media.isNotEmpty()) {
            val file = media[(rotationIndex % media.size + media.size) % media.size]
            rotationIndex++ // advance for the next break
            val ext = file.extension.lowercase()
            try {
                if (SLATE_EXT_VIDEO.contains(ext)) return buildVideoOverlay(context, file)
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    Log.d(TAG, "slate media (image) ${file.name}")
                    return FrameLayout(context).apply {
                        setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true
                        addView(
                            android.widget.ImageView(context).apply {
                                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            },
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                        )
                    }
                }
            } catch (t: Throwable) {
                if (DEBUG) Log.w(TAG, "slate media load failed (${file.name}): $t")
            }
        }

        // 2) Fallback: ESPN-logo card.
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

        // ESPN's own logo asset (drawable-nodpi/espn_logo), loaded by name from the
        // app's resources — no bundled/copyrighted image.
        try {
            val id = context.resources.getIdentifier("espn_logo", "drawable", context.packageName)
            if (id != 0) {
                val logo = android.widget.ImageView(context).apply {
                    setImageResource(id)
                    adjustViewBounds = true
                }
                column.addView(
                    logo,
                    LinearLayout.LayoutParams(dp(context, 140f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(context, 28f)
                    },
                )
            }
        } catch (_: Throwable) { /* logo optional */ }

        val title = TextView(context).apply {
            text = "COMMERCIAL BREAK"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; letterSpacing = 0.06f
        }
        val accent = View(context).apply {
            setBackgroundColor(Color.parseColor("#D40000"))
            layoutParams = LinearLayout.LayoutParams(dp(context, 120f), dp(context, 3f)).apply {
                topMargin = dp(context, 16f); bottomMargin = dp(context, 16f)
            }
        }
        val subtitle = TextView(context).apply {
            text = "WE'LL BE RIGHT BACK"; setTextColor(Color.parseColor("#C7CDD6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER; letterSpacing = 0.18f
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(accent)
        column.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0A0E14")); isClickable = true; isFocusable = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }
}
