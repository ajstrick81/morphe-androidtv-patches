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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

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
    private var overlayWebView: android.webkit.WebView? = null
    private var rotationIndex = 0            // advances each break for media round-robin
    private var didMute = false
    private var shown = false

    // On-screen slate picker (D-pad menu). currentMode tracks the live mode.
    private var currentMode: SlateMode = SlateMode.VIDEO
    private var pickerView: LinearLayout? = null
    private var pickerIndex = 0
    // Picker offers only the four viewer-facing choices. CARD/SCOREBOARD are
    // internal (CARD is the silent fallback when video/overlay assets are
    // missing); ADS is marker-only (it releases the remote by design, so it must
    // not be a picker entry or it would strand the viewer).
    private val PICKER_MODES = listOf(
        SlateMode.VIDEO, SlateMode.VIDEO_SCORE, SlateMode.OVERLAY, SlateMode.BLANK,
    )
    private var adsHintPill: View? = null
    private val adsHintFade = Runnable { fadeAdsHint() }

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
    // Remaining ms of the currently-active ad window (matched in evaluate), or -1
    // when unknown. Feeds the OVERLAY countdown so it reflects the real break.
    @Volatile private var currentBreakRemainingMs: Long = -1L
    private var mGetZeroPdt: Method? = null
    private var dbgTick = 0

    // Optional live-score strip (opt-in) — see startScoreStrip().
    private const val SCOREBOARD_MARKER = "scoreboard_on"   // presence = enable; contents = optional "sport/league[,sport/league]"
    private const val SCORE_REFRESH_MS = 15_000L
    private val DEFAULT_LEAGUES = listOf("football/college-football", "football/nfl")

    // ── Selectable slate MODES (task #3) ──────────────────────────────────────
    // Drop a `slate_mode` file in the app's external files dir; its trimmed
    // contents pick the layout. Unknown/absent → default: VIDEO_SCORE if the
    // legacy `scoreboard_on` marker is present (back-compat), else VIDEO.
    //   video        — user slate clip only (card fallback if no media)
    //   card         — built-in ESPN-style "COMMERCIAL BREAK" card (no video)
    //   scoreboard   — card background + live-score strip (no video)
    //   video+score  — user slate clip + live-score strip on top
    private const val SLATE_MODE_MARKER = "slate_mode"
    private const val ADS_HINT_MS = 5_000L   // ADS mode: how long the "slate options" pill lingers
    //   overlay      — animated WebView broadcast graphic (Be Right Back +
    //                  live countdown), loaded from files/espn_overlay/index.html
    private enum class SlateMode { VIDEO, CARD, SCOREBOARD, VIDEO_SCORE, ADS, BLANK, OVERLAY }
    private const val OVERLAY_DIR = "espn_overlay"     // holds index.html + bg-three.js + three.min.js
    private const val OVERLAY_SECS_MARKER = "overlay_secs"  // optional countdown length (seconds)

    private fun readSlateMode(context: Context): SlateMode {
        val explicit = try {
            context.getExternalFilesDir(null)?.let { java.io.File(it, SLATE_MODE_MARKER) }
                ?.takeIf { it.exists() }?.readText()?.trim()?.lowercase()
        } catch (_: Throwable) { null }
        return when (explicit) {
            "card" -> SlateMode.CARD
            "scoreboard", "score" -> SlateMode.SCOREBOARD
            "video+score", "video_score", "videoscore", "video-score" -> SlateMode.VIDEO_SCORE
            "video" -> SlateMode.VIDEO
            "overlay", "webview", "brb" -> SlateMode.OVERLAY
            "ads", "none", "off" -> SlateMode.ADS
            "blank", "black" -> SlateMode.BLANK
            else -> if (scoreboardMarkerPresent(context)) SlateMode.VIDEO_SCORE else SlateMode.VIDEO
        }
    }

    private fun scoreboardMarkerPresent(context: Context): Boolean = try {
        context.getExternalFilesDir(null)?.let { java.io.File(it, SCOREBOARD_MARKER).exists() } == true
    } catch (_: Throwable) { false }

    private fun stripEnabled(mode: SlateMode): Boolean =
        mode == SlateMode.SCOREBOARD || mode == SlateMode.VIDEO_SCORE

    // Leagues for the strip: `scoreboard_on` contents if present+non-empty, else default.
    private fun resolveLeagues(context: Context): List<String> = try {
        val f = context.getExternalFilesDir(null)?.let { java.io.File(it, SCOREBOARD_MARKER) }
        if (f != null && f.exists())
            f.readText().split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_LEAGUES }
        else DEFAULT_LEAGUES
    } catch (_: Throwable) { DEFAULT_LEAGUES }
    private val netExecutor = Executors.newSingleThreadExecutor()
    private var scoreStrip: TextView? = null
    private var activeLeagues: List<String> = emptyList()
    private val scoreRefresh = object : Runnable {
        override fun run() {
            refreshScoreOnce()
            if (scoreStrip != null) mainHandler.postDelayed(this, SCORE_REFRESH_MS)
        }
    }

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
        if (session != null) {
            mainHandler.post(pollRunnable)
            // Delay the recon: getPlaybackSession()/getDataSource() throw if called
            // synchronously here (session not yet wired). ~4s in, the manifest path
            // — our known identity signal — is reachable on the dataSource.
            mainHandler.postDelayed({ this.session?.let { dumpSessionMetadata(it) } }, 4_000L)
        }
    }

    // RECON (task: "scores for the content playing"): reflectively log identifier-
    // like fields on the session graph so we can see whether a clean event/airing
    // id or title is reachable (vs parsing the manifest path). One-shot per stream.
    private var dumpedSession = false
    private val METeresting = Regex("(?i)(title|name|\\bid\\b|uid|guid|url|event|airing|meta|program|asset|content|league|sport|team|score|game|competi|matchup)")
    private fun dumpSessionMetadata(s: Any) {
        if (dumpedSession) return
        dumpedSession = true
        // Per-step try so one throwing getter can't abort the whole walk (that
        // hid playbackSession/dataSource — where the manifest path lives — last time).
        try { logObjIdentifiers("session", s) } catch (t: Throwable) { Log.w(TAG, "dump session failed: $t") }
        val ps = try { byName(s.javaClass, "getPlaybackSession")?.invoke(s) } catch (t: Throwable) { Log.w(TAG, "getPlaybackSession failed: $t"); null }
        if (ps != null) {
            try { logObjIdentifiers("playbackSession", ps) } catch (t: Throwable) { Log.w(TAG, "dump playbackSession failed: $t") }
            val ds = try { byName(ps.javaClass, "getDataSource")?.invoke(ps) } catch (t: Throwable) { Log.w(TAG, "getDataSource failed: $t"); null }
            if (ds != null) try { logObjIdentifiers("dataSource", ds) } catch (t: Throwable) { Log.w(TAG, "dump dataSource failed: $t") }
        }
    }

    private fun logObjIdentifiers(tag: String, o: Any) {
        val ok = setOf("String", "CharSequence", "URI", "URL", "int", "long", "Integer", "Long", "boolean", "Boolean", "Object")
        for (m in o.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            if (!METeresting.containsMatchIn(m.name)) continue
            if (m.returnType.simpleName !in ok) continue
            try {
                val v = m.invoke(o)?.toString()?.take(200) ?: continue
                if (v.isBlank() || v == "0" || v == "false") continue
                Log.d(TAG, "META[$tag] ${m.name}() = $v")
            } catch (_: Throwable) {}
        }
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
        var remainingMs = -1L
        val ws = windowStart; val we = windowEnd
        for (i in ws.indices) {
            if (playhead >= ws[i] - EDGE_TOLERANCE_MS && playhead <= we[i] + EDGE_TOLERANCE_MS) {
                active = true; remainingMs = we[i] - playhead; break
            }
        }
        if (!active && playheadAbs != Long.MIN_VALUE) {
            synchronized(winStartById) {
                for ((id, start) in winStartById) {
                    val end = winEndById[id] ?: continue
                    if (playheadAbs >= start - EDGE_TOLERANCE_MS && playheadAbs <= end + WINDOW_TRAILING_MS) {
                        active = true; remainingMs = end - playheadAbs; break
                    }
                }
            }
        }
        currentBreakRemainingMs = remainingMs
        if (active != lastActive) {
            lastActive = active
            Log.d(TAG, "playhead=$playhead abs=$playheadAbs -> ${if (active) "IN AD WINDOW" else "content"}")
        }
        if (active) mainHandler.post { showSlateNow() } else mainHandler.post { hideSlateNow("out-of-window") }
    }

    // ─────────────────────────────── slate UI ──────────────────────────────
    private fun showSlateNow() {
        if (shown) {
            // Re-assert the mute every tick (~3/s). The DMP player re-applies its
            // own volume when an ad segment starts mid-break, so a one-shot mute at
            // break start leaks ad audio (worst on quieter slate media). Bypass the
            // didMute guard and set it directly — cheap, idempotent, self-healing.
            // ADS mode deliberately leaves the ad audible, so never mute there.
            if (currentMode != SlateMode.ADS) setPlayerMuted(true)
            rearmFailsafe()
            return
        }
        val container = contentRoot() ?: run {
            Log.w(TAG, "no host container — Activity not registered?")
            return
        }
        // Runtime opt-out: user placed a `slate_off` marker → leave the ad alone.
        try {
            if (container.context.getExternalFilesDir(null)?.let { java.io.File(it, DISABLE_MARKER).exists() } == true) return
        } catch (_: Throwable) {}
        // Build a FRESH overlay each break so the media round-robins (and any
        // VideoView gets a clean start/stop lifecycle). Mode picks the layout.
        val mode = readSlateMode(container.context)
        currentMode = mode
        Log.d(TAG, "slate mode=$mode")
        // ADS mode: no cover, no mute — just a brief focusable hint so the picker
        // stays reachable. Ad plays through normally.
        if (mode == SlateMode.ADS) {
            showAdsHint(container)
            shown = true
            rearmFailsafe()
            return
        }
        attachSlate(container, mode)
        mute(container.context)
        shown = true
        rearmFailsafe()
        Log.d(TAG, "slate shown + audio muted")
    }

    // Build + attach the base layer for [mode] into the host container and wire
    // D-pad handling. Shared by the initial show and by live mode-swaps from the
    // on-screen picker (does NOT touch mute / shown / failsafe).
    private fun attachSlate(container: ViewGroup, mode: SlateMode) {
        val overlay = buildSlate(container.context, mode)
        currentOverlay = overlay
        container.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.bringToFront()
        videoView?.let { vv -> vv.start() }   // play; focus stays on the overlay for the remote
        // Live-score strip over the slate for scoreboard / video+score modes.
        if (stripEnabled(mode)) startScoreStrip(overlay, container.context)
        // Take the remote so a D-pad press can open the slate picker.
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true
        overlay.setOnKeyListener { _, keyCode, event -> onSlateKey(container, keyCode, event) }
        overlay.requestFocus()
    }

    // Tear down the current base + picker (keeps mute/shown), then rebuild for
    // [mode] — the live swap invoked when the viewer picks a mode on-screen.
    private fun swapMode(container: ViewGroup, mode: SlateMode) {
        closePicker()
        stopScoreStrip()
        mainHandler.removeCallbacks(adsHintFade)
        adsHintPill = null
        releaseVideo()
        releaseWebView()
        currentOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentOverlay = null
        currentMode = mode
        if (mode == SlateMode.ADS) {
            unmute(container.context)   // let the ad play through
            showAdsHint(container)
            Log.d(TAG, "slate swapped -> mode=ADS")
            return
        }
        attachSlate(container, mode)
        mute(container.context)   // ensure muted (keeps didMute correct across swaps)
        Log.d(TAG, "slate swapped -> mode=$mode")
    }

    // ADS mode overlay: transparent + a brief focusable "slate options" pill so the
    // picker stays reachable by remote; after ADS_HINT_MS with no input it fades and
    // releases focus, leaving the commercial fully uncovered.
    private fun showAdsHint(container: ViewGroup) {
        val ctx = container.context
        val overlay = FrameLayout(ctx).apply { isFocusable = true; isFocusableInTouchMode = true }
        currentOverlay = overlay
        container.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.bringToFront()
        val pill = TextView(ctx).apply {
            text = "▲  Slate options"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(ctx, 18f), dp(ctx, 8f), dp(ctx, 18f), dp(ctx, 8f))
        }
        overlay.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(ctx, 28f) },
        )
        adsHintPill = pill
        overlay.setOnKeyListener { _, keyCode, event -> onSlateKey(container, keyCode, event) }
        overlay.requestFocus()
        mainHandler.removeCallbacks(adsHintFade)
        mainHandler.postDelayed(adsHintFade, ADS_HINT_MS)
        Log.d(TAG, "ads mode — hint shown (no mask, no mute)")
    }

    // Fade the ADS hint and stop intercepting the remote, so the ad is uncovered.
    private fun fadeAdsHint() {
        if (pickerView != null) return   // viewer is mid-menu; keep the overlay live
        adsHintPill?.let { p -> p.animate().alpha(0f).setDuration(400).withEndAction { p.visibility = View.GONE }.start() }
        // Release the overlay so the ad is fully uncovered and the remote returns
        // to the app. (ADS is no longer offered in the picker — it's marker-only —
        // so this can't strand a picker user.)
        currentOverlay?.let { it.isFocusable = false; it.isFocusableInTouchMode = false; it.setOnKeyListener(null) }
        Log.d(TAG, "ads hint faded — ad uncovered")
    }

    private fun hideSlateNow(reason: String) {
        mainHandler.removeCallbacks(hideRunnable)
        if (!shown) return
        closePicker()
        mainHandler.removeCallbacks(adsHintFade)
        adsHintPill = null
        stopScoreStrip()
        releaseVideo()
        releaseWebView()
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

    // ─────────────────────── on-screen slate picker (D-pad) ────────────────────
    // While the slate is up the overlay holds focus; a D-pad press opens a small
    // centered menu. UP/DOWN moves, OK selects (live-swaps + persists), BACK closes.
    private fun onSlateKey(container: ViewGroup, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (pickerView == null) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openPicker(); true }
                else -> false
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { movePicker(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { movePicker(1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { applyPicker(container); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { closePicker(); true }
            else -> false
        }
    }

    private fun openPicker() {
        val overlay = currentOverlay ?: return
        val ctx = overlay.context
        pickerIndex = PICKER_MODES.indexOf(currentMode).coerceAtLeast(0)
        // Scrim opacity is tunable at runtime: drop a `picker_alpha` marker with
        // a 0..100 value (percent). Absent/invalid -> 35%. Read on every open so
        // pushing a new value + reopening the picker shows it with no rebuild.
        val alphaPct = try {
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, "picker_alpha") }
                ?.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                ?.coerceIn(0, 100)
        } catch (_: Throwable) { null } ?: 35
        val scrim = (Math.round(alphaPct * 2.55).toInt() shl 24) or 0x0A0E14
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scrim)
            setPadding(dp(ctx, 32f), dp(ctx, 22f), dp(ctx, 32f), dp(ctx, 22f))
        }
        menu.addView(TextView(ctx).apply {
            text = "SELECT SLATE"; setTextColor(Color.parseColor("#C7CDD6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); letterSpacing = 0.18f
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(ctx, 14f))
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#CC000000")) // legibility over the box-less bg
        })
        PICKER_MODES.forEach { m ->
            menu.addView(
                TextView(ctx).apply {
                    text = modeLabel(m); setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setPadding(dp(ctx, 28f), dp(ctx, 10f), dp(ctx, 28f), dp(ctx, 10f))
                    setShadowLayer(7f, 0f, 2f, Color.parseColor("#DD000000")) // legibility over the box-less bg
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        overlay.addView(
            menu,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ),
        )
        menu.bringToFront()
        pickerView = menu
        renderPicker()
        Log.d(TAG, "picker opened (current=$currentMode)")
    }

    private fun renderPicker() {
        val menu = pickerView ?: return
        for (i in PICKER_MODES.indices) {
            val tv = menu.getChildAt(i + 1) as? TextView ?: continue   // +1: child 0 is the title
            if (i == pickerIndex) {
                tv.setBackgroundColor(Color.parseColor("#D40000")); tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT); tv.setTextColor(Color.parseColor("#C7CDD6"))
            }
        }
    }

    private fun movePicker(delta: Int) {
        pickerIndex = (pickerIndex + delta + PICKER_MODES.size) % PICKER_MODES.size
        renderPicker()
    }

    private fun closePicker() {
        pickerView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pickerView = null
    }

    private fun applyPicker(container: ViewGroup) {
        val chosen = PICKER_MODES[pickerIndex]
        persistMode(container.context, chosen)   // written by the app → readable + sticky
        if (chosen == currentMode) { closePicker(); return }
        swapMode(container, chosen)
    }

    private fun persistMode(context: Context, mode: SlateMode) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, SLATE_MODE_MARKER)
            // A marker pushed via `adb push` is owned by `shell`, so the app (a
            // different uid) can read but not overwrite it — writeText would throw
            // EACCES. The app DOES own the directory, so delete the stale file and
            // recreate it as an app-owned file we can rewrite freely from now on.
            if (f.exists() && !f.canWrite()) f.delete()
            f.writeText(modeToken(mode))
            Log.d(TAG, "slate mode persisted -> ${modeToken(mode)}")
        } catch (t: Throwable) {
            Log.w(TAG, "persistMode failed: $t")   // unconditional — silent failure hid this once
        }
    }

    private fun modeToken(m: SlateMode): String = when (m) {
        SlateMode.VIDEO -> "video"; SlateMode.CARD -> "card"
        SlateMode.SCOREBOARD -> "scoreboard"; SlateMode.VIDEO_SCORE -> "video+score"
        SlateMode.OVERLAY -> "overlay"
        SlateMode.ADS -> "ads"; SlateMode.BLANK -> "blank"
    }

    private fun modeLabel(m: SlateMode): String = when (m) {
        SlateMode.VIDEO -> "Video"; SlateMode.CARD -> "Card"
        SlateMode.SCOREBOARD -> "Scoreboard"; SlateMode.VIDEO_SCORE -> "Video + Score"
        SlateMode.OVERLAY -> "Be Right Back (ESPN Ad-Break Overlay)"
        SlateMode.ADS -> "Ads (no slate)"; SlateMode.BLANK -> "Blank"
    }

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

    // ───────────── optional live-score strip (ESPN public scoreboard API) ─────────────
    // Endpoint shapes courtesy of the community ESPN hidden-API documentation projects:
    //   github.com/pseudo-r/Public-ESPN-API, akeaswaran's gist (b48b02f1...),
    //   cwendt94/espn-api, quantum0813/ESPNSportsAPI, ITIRadio/ESPN-API. Thanks!
    // Opt-in: drop a `scoreboard_on` file in the app's external files dir. Its contents
    // (optional) are a comma-separated list of "sport/league" (e.g. "baseball/mlb");
    // empty = default college-football + nfl. Shows the first in-progress game's
    // score + clock over the slate, refreshed periodically. Public API, no auth.
    private fun startScoreStrip(overlay: FrameLayout, context: Context) {
        val leagues = resolveLeagues(context)
        activeLeagues = leagues
        val strip = TextView(context).apply {
            text = "· LIVE ·"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setBackgroundColor(Color.parseColor("#B3000000")) // ~70% black
            setPadding(dp(context, 26f), dp(context, 10f), dp(context, 26f), dp(context, 10f))
        }
        overlay.addView(
            strip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(context, 28f) },
        )
        strip.bringToFront()
        scoreStrip = strip
        // Diagnostic (unconditional): after layout, report the strip's measured
        // size + on-screen position. Nonzero w/h with the strip still invisible
        // ⇒ z-order/hardware-overlay problem (video plane over UI); zero w/h ⇒
        // it never laid out. Distinguishes the two on the next live break.
        strip.post {
            val loc = IntArray(2); strip.getLocationOnScreen(loc)
            Log.d(TAG, "score strip attached w=${strip.width} h=${strip.height} at(${loc[0]},${loc[1]}) vis=${strip.visibility} leagues=$leagues")
        }
        mainHandler.post(scoreRefresh)
    }

    private fun stopScoreStrip() {
        mainHandler.removeCallbacks(scoreRefresh)
        scoreStrip = null
    }

    private fun refreshScoreOnce() {
        val leagues = activeLeagues
        netExecutor.execute {
            val line = fetchScoreLine(leagues)
            if (line != null) mainHandler.post { scoreStrip?.text = line }
        }
    }

    private fun fetchScoreLine(leagues: List<String>): String? {
        for (lg in leagues) {
            try {
                val json = httpGet("https://site.api.espn.com/apis/site/v2/sports/$lg/scoreboard") ?: continue
                val events = JSONObject(json).optJSONArray("events") ?: continue
                for (i in 0 until events.length()) {
                    val comp = events.getJSONObject(i).optJSONArray("competitions")?.optJSONObject(0) ?: continue
                    val type = comp.optJSONObject("status")?.optJSONObject("type")
                    if (type?.optString("state") != "in") continue   // in-progress only
                    val cs = comp.optJSONArray("competitors") ?: continue
                    var away = ""; var home = ""
                    for (j in 0 until cs.length()) {
                        val c = cs.getJSONObject(j)
                        val ab = c.optJSONObject("team")?.optString("abbreviation") ?: "?"
                        val sc = c.optString("score", "0")
                        if (c.optString("homeAway") == "home") home = "$ab $sc" else away = "$ab $sc"
                    }
                    val detail = type.optString("shortDetail", "")
                    val line = listOf("$away    $home", detail).filter { it.isNotBlank() }.joinToString("   ·   ")
                    Log.d(TAG, "score fetch $lg -> $line")
                    return line
                }
                Log.d(TAG, "score fetch $lg -> no in-progress game (events=${events.length()})")
            } catch (t: Throwable) {
                Log.w(TAG, "score fetch failed ($lg): $t")   // unconditional while diagnosing
            }
        }
        return null
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
                // ESPN's site.api actively 403s browser-family and default-Java
                // UAs (Mozilla/*, Java/*, empty). It answers 200 for recognized
                // HTTP-client UAs — okhttp is a legit Android client string.
                setRequestProperty("User-Agent", "okhttp/4.9.0")
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "httpGet $urlStr -> HTTP $code")
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "httpGet $urlStr -> 200 (${body.length} bytes)")
                body
            }
        } catch (t: Throwable) {
            Log.w(TAG, "httpGet failed ($urlStr): $t")   // unconditional while diagnosing
            null
        } finally {
            conn?.disconnect()
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

    // Clean VideoView/MediaPlayer teardown. Clearing the listeners + suspend()
    // before dropping the reference avoids "mediaplayer went away with unhandled
    // events" and the SurfaceView churn that hitched the DMP player on rapid
    // mode-swaps / back-out+restart.
    private fun releaseVideo() {
        videoView?.let { vv ->
            try {
                vv.setOnPreparedListener(null)
                vv.setOnErrorListener(null)
                vv.setOnCompletionListener(null)
                vv.stopPlayback()
                vv.suspend()
                (vv.parent as? ViewGroup)?.removeView(vv)
            } catch (_: Throwable) {}
        }
        videoView = null
    }

    // Clean WebView teardown — stop JS/RAF, detach, destroy. Mirrors releaseVideo.
    private fun releaseWebView() {
        overlayWebView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.destroy()
            } catch (_: Throwable) {}
        }
        overlayWebView = null
    }

    // OVERLAY mode: the animated "Be Right Back" broadcast graphic rendered in a
    // WebView from files/espn_overlay/index.html (HTML + three.js + live countdown).
    // Returns null if the assets aren't present, so the caller falls back to the card.
    private fun buildOverlay(context: Context): FrameLayout? {
        val dir = try { context.getExternalFilesDir(null)?.let { java.io.File(it, OVERLAY_DIR) } } catch (_: Throwable) { null }
        val index = dir?.let { java.io.File(it, "index.html") }
        if (index == null || !index.exists()) {
            Log.w(TAG, "overlay mode: ${OVERLAY_DIR}/index.html not found — falling back to card")
            return null
        }
        // Countdown length, in priority order:
        //   1. the REAL remaining time of the active ad window (evaluate computed
        //      it from the window end minus the live playhead) — accurate,
        //   2. an `overlay_secs` marker override,
        //   3. 150s default.
        // The graphic loops at 0, and the slate is dismissed when the window ends,
        // so a slightly-off estimate self-corrects.
        val realRemainingSecs = currentBreakRemainingMs.let { if (it in 1_000..1_800_000) (it / 1000).toInt() else null }
        val secs = realRemainingSecs ?: (try {
            context.getExternalFilesDir(null)?.let { java.io.File(it, OVERLAY_SECS_MARKER) }
                ?.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        } catch (_: Throwable) { null } ?: 150)
        Log.d(TAG, "overlay countdown secs=$secs (realRemaining=${realRemainingSecs ?: "n/a"})")
        val wv = android.webkit.WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = false
                @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            }
            webChromeClient = android.webkit.WebChromeClient()
            isFocusable = false; isFocusableInTouchMode = false
        }
        overlayWebView = wv
        val url = "file://${index.absolutePath}?secs=$secs"
        wv.loadUrl(url)
        Log.d(TAG, "overlay webview loading $url")
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true
            addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // Base layer for the current break, chosen by mode. CARD/SCOREBOARD skip the
    // video entirely (SCOREBOARD gets the score strip added over the card by the
    // caller); VIDEO/VIDEO_SCORE play user media, falling back to the card when
    // no media files are present.
    private fun buildSlate(context: Context, mode: SlateMode): FrameLayout {
        if (mode == SlateMode.BLANK) return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true
        }
        if (mode == SlateMode.CARD || mode == SlateMode.SCOREBOARD) return buildCard(context)
        if (mode == SlateMode.OVERLAY) return buildOverlay(context) ?: buildCard(context)

        // VIDEO / VIDEO_SCORE — user media, round-robined per break.
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

        // No media → ESPN-style card fallback.
        return buildCard(context)
    }

    // Built-in ESPN-style "COMMERCIAL BREAK" card. Uses the app's OWN espn_logo
    // drawable loaded by name at runtime — nothing copyrighted is bundled.
    private fun buildCard(context: Context): FrameLayout {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

        // White "ESPN" text wordmark (per design pick) — plain white lettering,
        // no logo image and nothing copyrighted bundled.
        val wordmark = TextView(context).apply {
            text = "ESPN"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; letterSpacing = 0.12f
        }
        column.addView(
            wordmark,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 28f)
            },
        )

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
