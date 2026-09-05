package app.morphe.patches.espn

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// ─────────────────────────────────────────────────────────────────────────────
// ESPN (com.espn.score_center) — live/VOD SGAI ad kill.
//
// ESPN's player is Disney's DMP ("com.disney.dmp") SGAI plugin — the same ad
// platform family as Disney+. Live interstitials, live ad breaks and VOD
// mid-rolls are all scheduled by com.disney.dmp.sgai.InterstitialManager, and
// EVERY scheduling site is gated on ONE boolean field:
//
//   private final isAdDisabled:Z
//
// Read at 6 sites, each skipping the ad when true:
//   processInterstitialTag   → interstitial never scheduled
//   processBreakTag (2×)     → skips scheduleBreak()
//   scheduleVodMidRoll       → skips VOD mid-roll
//   parseToInsertionsMap     → recipe parser told ads are off
//   toString                 → log only
//
// The field is assigned once, from constructor param p6, in
//   InterstitialManager.<init>(RecipeParser, RemoteAdResolver, Recipe, J, Z, Z,
//                              AdSession, SGAIPlugin, NetTypeProvider, Z, String)
// via `iput-boolean p6, ...->isAdDisabled:Z`. Forcing that written value to 1
// (const/4 into the iput's source register, injected immediately before it)
// makes every instance report ads-disabled → all six gates take the no-ad path.
//
// There is a synthetic bridge <init> too; it merely forwards to the real one and
// does NOT contain the iput. We therefore match the <init> that actually writes
// isAdDisabled (self-validating — see the custom predicate below), so we can't
// accidentally patch the bridge.
//
// Verified against ESPN Android TV 6.11.1 (versionCode 1109107). The whole
// com.disney.dmp.sgai.* package kept clean (non-minified) names, so this field-
// and-class match is stable across minor versions; the patch fails loud if the
// InterstitialManager constructor no longer writes isAdDisabled.
// ─────────────────────────────────────────────────────────────────────────────
internal object InterstitialManagerInitFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "<init>" &&
            method.definingClass == "Lcom/disney/dmp/sgai/InterstitialManager;" &&
            method.implementation?.instructions?.any { insn ->
                insn.opcode == Opcode.IPUT_BOOLEAN &&
                    ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.name == "isAdDisabled"
            } == true
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// Live-slate overlay seam (the passthrough-SSAI mask; see EspnAdBreakOverlayHelper).
//
// Learned on-device (2026-09-03): live passthrough breaks emit NO
// PlaybackSessionEvent.BreakStartedEvent and NO SGAI interstitial session — the
// ONLY ground truth is "the playhead is inside a break window". So we compute
// containment ourselves, exactly like the browser userscript does from the
// manifest, using two feeds:
//
// 1) noteBreak(startPosition, duration) — ProgressViewModel.buildAdBreakData builds
//    an AdBreakData from every BreakInfo returned by session.getBreaks(). We inject
//    a noteBreak() right after that constructor to record each break window.
//    (getBreaks() returns all scheduled breaks; only the current one ever contains
//    the live playhead, so recording them all is safe.)
// 2) onPlayerEvent(event) — sessionListener.onEvent receives every
//    PlaybackSessionEvent; for TimelineProgressEvent the helper reads the playhead
//    position (reflection) and re-evaluates containment.
// ─────────────────────────────────────────────────────────────────────────────

// MediaPlayerViewModel.attachSession(DisneyMediaPlaybackSession): the single clean
// point where the DMP session is handed to the ViewModel (p1 = session). We inject
// setSession(p1) so the helper can poll session.getBreaks() for the break windows.
internal object AttachSessionFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "attachSession" &&
            method.definingClass == "Lcom/espn/video/dmp/model/MediaPlayerViewModel;"
    },
)

// SgaiPlaybackSession.playlistRetrieved(DateTime, List<DateRange>): the DMP-internal
// callback that always fires (~every playlist refresh) with all ad DateRanges, the
// ONLY source of live passthrough windows (getBreaks() is empty for such streams).
// p2 = the DateRange list. Feeds onDateRanges().
internal object PlaylistRetrievedFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "playlistRetrieved" &&
            method.definingClass == "Lcom/disney/dmp/media3/ads/mel/SgaiPlaybackSession;"
    },
)

// sessionListener.onEvent(PlaybackSessionEvent) receives EVERY DMP player event.
// We feed each one to the helper, which extracts the playhead from
// TimelineProgressEvent to drive the containment check. p1 is the event.
internal object SessionListenerOnEventFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "onEvent" &&
            method.definingClass ==
                "Lcom/espn/video/dmp/model/MediaPlayerViewModel\$sessionListener\$1;"
    },
)

// PlayerActivity lifecycle — used to (un)register the slate's host container
// (the Activity's android.R.id.content root) with the overlay helper.
internal object PlayerActivityOnResumeFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "onResume" &&
            method.definingClass == "Lcom/espn/video/dmp/PlayerActivity;"
    },
)

internal object PlayerActivityOnPauseFingerprint : Fingerprint(
    custom = { method, _ ->
        method.name == "onPause" &&
            method.definingClass == "Lcom/espn/video/dmp/PlayerActivity;"
    },
)
