package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ===========================================================================
// Pluto TV — ad architecture (confirmed by dex disassembly of 5.66.0-leanback)
//
// Pluto delivers ads via SERVER-SIDE AD STITCHING (SSAI). The player fetches a
// "stitcher session" whose HLS manifest already has the ad segments spliced
// into the content timeline server-side. The client parses that session
//   StitcherSessionMapper.map(SwaggerStitcherSessionInformation) -> StitcherSession
// into a client-side ad-break timeline
//   StitcherSessionMapperUtil.toAdBreak(SwaggerStitcherAdBreak) -> playercommon/data/AdBreak
// and that timeline drives THREE things, none of which is the ad video itself:
//   1. ID3-tag beacon tracking   (DefaultSlotAdBreakController.process / BeaconTracker.fire)
//   2. ad-experience UI overlays (pause ads, ad-pod progress, clickable ads)
//   3. seek-blocking during breaks
//
// Consequence for patching:
//   - The in-stream ad VIDEO cannot be removed by bytecode; it is baked into
//     the manifest the same CDN serves the content from. LIVE TV ads occupy
//     real broadcast wall-clock time in the linear stream and are unremovable
//     at this layer (same class of problem as Prime Video's native mid-roll).
//   - What bytecode CAN cleanly kill is the ad-experience chrome + tracking:
//     pause ads, tracking beacons, and clickable-ad overlays. Those are the
//     three hooks wired below.
//   - A VOD-only auto-skip remains a Tier-2 candidate (see note at bottom),
//     deferred until it can be validated on a device.
// ===========================================================================

// Hook 1 — BeaconTracker.fire(String adEventName, List urls)
//
// Single choke point for ALL SSAI tracking beacons. Every impression/quartile/
// complete beacon for stitched ads funnels through this one fire-and-forget
// method (it builds an RxJava Completable and subscribes, returning void — no
// caller reads a result). Neutering it silences all ad analytics with zero
// risk to the playback pipeline. Confirmed: public, returns V.
object BeaconTrackerFireFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/library/adsbeaconstracking/BeaconTracker;",
    name = "fire",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/util/List;"
    ),
    returnType = "V",
    strings = listOf("adEventName", "urls"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// Hook 2 — PauseAdsImageBinder.showPauseAdImageAfterInactivity(Bitmap, List)
//
// Display entry point for pause ads (the full-screen ad image shown when
// playback is paused). The method both renders the ad bitmap and fires its
// tracking beacons; returning void up front suppresses the graphic and the
// beacons in one cut. Pure UI side effect — safe to no-op. Confirmed:
// public final, returns V.
object PauseAdsShowFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/feature/leanbackpauseads/binder/PauseAdsImageBinder;",
    name = "showPauseAdImageAfterInactivity",
    parameters = listOf(
        "Landroid/graphics/Bitmap;",
        "Ljava/util/List;"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

// Hook 3 — ClickableAdsBinder.Companion.bind(...): Disposable
//
// Wiring entry point for clickable ("interactive") ad overlays — the prompts
// shown over content during ad breaks (e.g. "add to watchlist / go to
// channel"). bind() sets up the whole RxJava subscription graph and returns
// the Disposable that owns it; the caller just adds that Disposable to a
// CompositeDisposable. Returning an already-disposed Disposable at offset 0
// means the feature is never wired at all — no overlay, no clickable-ad
// analytics — while still honoring the method's contract (the caller gets a
// valid, harmless Disposable to manage).
//
// Verified present in-app: io.reactivex.disposables.Disposables.disposed()
// returns Lio/reactivex/disposables/Disposable;. Confirmed: public final.
//
// NOTE: definingClass uses \$ so Kotlin does not treat $Companion as a string
// template — it is the literal inner-class name ClickableAdsBinder$Companion.
object ClickableAdsBindFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/android/appcommon/clickableads/ClickableAdsBinder\$Companion;",
    name = "bind",
    returnType = "Lio/reactivex/disposables/Disposable;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

// Hook 4 — StitcherSession.getAdBreaks(): List
//
// The parsed stitcher-session model. Its `adBreaks` list is the client-side
// ad-break timeline that AdGuard Premium strips on the wire
// (||pluto.tv/*/session.json$jsonprune=$.adBreaks.*) to suppress Pluto ads.
// This getter is the single choke point every consumer reads it through
// (timebar AdBreakPositionSource, DefaultSlotAdBreakController /
// ID3AdsBeaconTracker, and the player's ad handling). Forcing it to return an
// empty list is the in-app equivalent of that jsonprune. Confirmed present in
// 5.66.0-leanback at tv/pluto/library/playercommon/data/StitcherSession;
// public final, .locals 1, returns Ljava/util/List;.
object StitcherSessionGetAdBreaksFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/library/playercommon/data/StitcherSession;",
    name = "getAdBreaks",
    parameters = listOf(),
    returnType = "Ljava/util/List;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)


// ---------------------------------------------------------------------------
// media3 DASH manifest PARSER — the safe seam for DASH period surgery (VOD removal).
//
// DECISIVE on-device results (2026-07-31):
//   1. The clip-strip returned a session.json with ZERO clips, yet the movie played
//      its full ad-laden runtime — so clips[] is UI/seek metadata, not the playback
//      timeline. The real timeline is the stitched multi-period DASH manifest media3
//      fetches directly.
//   2. Wrapping AviaPlayerFactory's media3 OkHttpClient with ANY interceptor
//      black-screens playback — media3 never even initializes (no request reaches
//      the interceptor). The streaming/network layer is the WRONG seam here.
//
// The right seam is media3's manifest PARSER — and media3 is NOT obfuscated in this
// Pluto build (real class names survive):
//   Landroidx/media3/exoplayer/dash/manifest/DashManifestParser;->parse(
//       Landroid/net/Uri; Ljava/io/InputStream;)
//     Landroidx/media3/exoplayer/dash/manifest/DashManifest;
//
// This runs AFTER the manifest is downloaded and parsed, returning a DashManifest
// whose `periods` list we can read (probe) or rewrite (strip). It never touches the
// streaming OkHttp client, so it cannot break playback the way the wrap did. Ad
// periods are identifiable by a `_ad/creative` BaseUrl on their representations
// (Period.adaptationSets -> AdaptationSet.representations -> Representation.baseUrls
// -> BaseUrl.url). The (Uri, InputStream) -> DashManifest signature is the single
// public parse entry (the other parse* methods are XmlPullParser element helpers).
object DashManifestParserParseFingerprint : Fingerprint(
    definingClass = "Landroidx/media3/exoplayer/dash/manifest/DashManifestParser;",
    name = "parse",
    parameters = listOf(
        "Landroid/net/Uri;",
        "Ljava/io/InputStream;",
    ),
    returnType = "Landroidx/media3/exoplayer/dash/manifest/DashManifest;",
)

// ---------------------------------------------------------------------------
// Hook 6 — AviaPlayer.startExoplayer(MediaSource) — the RESUME-SEEK seam (issue #147).
//
// Pluto plays through Paramount's Avia SDK on top of media3/ExoPlayer (both
// un-obfuscated in this build). When the media is loaded, startExoplayer reads the
// resume bookmark off the media asset (getStartPosition(), in the ORIGINAL
// ad-inclusive timeline) and applies it with a single
//   Landroidx/media3/common/Player;->seekTo(J)V
// call — the ONLY seekTo(J) in this method. Because Hook 5 shortened the content
// timeline, a bookmark past the new (shorter) duration makes ExoPlayer seek past
// the end -> STATE_ENDED -> Pluto autoplay-advances to the next episode (the
// one-off skip in #147). The parsed timeline is already live here, so the player's
// current (stripped) duration is readable. Hook 6 routes the start position through
// PlutoDashManifestProbe.mapResumePosition(player, pos) just before that seekTo,
// re-mapping an original-timeline bookmark into stripped-timeline coordinates.
// Confirmed present in 5.66.0-leanback:
//   com/paramount/android/avia/player/player/core/AviaPlayer;
//     ->startExoplayer(Landroidx/media3/exoplayer/source/MediaSource;)V
object AviaStartExoplayerFingerprint : Fingerprint(
    definingClass = "Lcom/paramount/android/avia/player/player/core/AviaPlayer;",
    name = "startExoplayer",
    parameters = listOf("Landroidx/media3/exoplayer/source/MediaSource;"),
    returnType = "V",
)

// ===========================================================================
// LIVE ad-break MASK (issue #152) — fingerprints for the liveSlatePatch.
//
// Live/linear Pluto ads are real broadcast time in the dynamic DASH feed and can't be
// removed (the VOD strip passes live manifests through untouched). Instead we MASK the
// break: black cover + mute, lifted when the show returns. Detector + host seams below,
// all confirmed on 5.66.0-leanback and against a real live break (2026-09-07).
// ===========================================================================

// Detector — ID3AdsBeaconTracker.consumeID3(ID3Tag): fires ONLY while an ad is playing
// (silent during the show). Each call = "an ad is on screen now"; the helper re-arms a
// short hide timer per call and lifts the mask when the ticks stop. Synchronous, void,
// single param — a clean offset-0 hook. Survives "Skip ads" (that neuters BeaconTracker,
// a different class).
object Id3ConsumeFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/kmm/ads/adsbeacontracker/ID3AdsBeaconTracker;",
    name = "consumeID3",
    parameters = listOf("Ltv/pluto/kmm/ads/adsbeacontracker/model/ID3Tag;"),
    returnType = "V",
)

// Overlay host — the single-Activity leanback host (LeanbackMainHostActivity). Its content
// root covers the player for the WHOLE session, unlike the live-controls fragment whose view
// is destroyed when the on-screen controls auto-hide (which prematurely tore the mask down).
// onCreate captures the Activity (p0); onDestroy clears it. The cover is added lazily at
// break time, so registering at onCreate (before the content view exists) is fine.
object LeanbackHostOnCreateFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/android/ui/main/LeanbackMainHostActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
)

object LeanbackHostOnDestroyFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/android/ui/main/LeanbackMainHostActivity;",
    name = "onDestroy",
    parameters = listOf(),
    returnType = "V",
)

// Mute — capture the AviaPlayer (constructor arg p1) so the helper can reflect its
// media3 ExoPlayer `player` field and get/set its volume (mute = 0f, restored on lift).
object AviaControllerInitFingerprint : Fingerprint(
    definingClass = "Ltv/pluto/library/player/impl/avia/AviaPlaybackController;",
    name = "<init>",
    parameters = listOf(
        "Lcom/paramount/android/avia/player/player/core/AviaPlayer;",
        "Ltv/pluto/library/player/impl/avia/IAviaAccessor;",
        "Ltv/pluto/library/player/api/IContentController;",
        "Ltv/pluto/library/player/IAdGroupsDispatcher;",
        "Ltv/pluto/library/player/api/IPlayerRxEventsAdapter;",
        "Lio/reactivex/disposables/CompositeDisposable;",
        "Lio/reactivex/Scheduler;",
        "Ltv/pluto/library/player/utils/IThreadPoster;",
    ),
    returnType = "V",
)

// ---------------------------------------------------------------------------
// Tier 2 candidate — VOD auto-skip (NOT wired; requires on-device validation)
//
// Because the ad-break timeline is fully client-side, VOD ads *may* be made
// invisible by seeking past each break as it starts. The pieces confirmed
// present in this build:
//   - Ad-break model + positions:
//       tv/pluto/library/playercommon/data/AdBreak (+ AdBreakLocation)
//       built by StitcherSessionMapperUtil.toAdBreak(...)
//   - A player handle to seek with: IPlayerMediator / IPlayer, exposed to
//       tv/pluto/feature/leanbackplayercontrols/ui/livetv/usecase/AdBreakUseCase
//       and to ClickableAdsBinder.bind(...) — both receive the mediator/player,
//       and IPlayer exposes seekTo.
// Approach: observe playback progress + the AdBreak list; on entering a break,
// seekTo(break end).
//
// Left unwired on purpose:
//   - Cannot work for LIVE TV (ads are real broadcast time in the linear feed).
//   - Pluto stitches ads INTO the segment timeline, so whether a seek to
//     break-end lands cleanly on buffered content or triggers a rebuffer / ad
//     re-entry loop is unknown without a device. A bad seek loop is a worse
//     experience than the ads. This is exactly the suspend/seek regression
//     class the Tubi and Peacock notes document — validate on hardware first.
// ---------------------------------------------------------------------------
