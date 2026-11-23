package ajstrick81.morphe.patches.vix.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprints for ViX Android TV (com.univision.prendetv) v4.46.0_tv
//
// Ad stack: LuraPlayer SDK (com.akta.luraplayer) + Innovid SSAI overlay.
// Class/method names confirmed via baksmali disassembly of the shipped dex
// set (classes.dex … classes11.dex).
//
// NOTE on the former LuraFreewheelConfiguration constructor hook (removed):
//   classes5.dex analysis showed LuraFreewheelConfiguration has THREE public
//   constructors (the kotlinx.serialization synthetic <init>(IZ…x2), the
//   primary <init>(Z…), and the default-args synthetic), so an unparameterised
//   [PUBLIC, CONSTRUCTOR] fingerprint bound ambiguously. More importantly the
//   hook was a no-op for ad suppression: the `enabled` field (a:Z) is read only
//   inside the class, and getEnabled() is invoked solely by a config merge()
//   helper (LuraFreewheelConfigurationKt) — nothing in the ad scheduler gates
//   delivery on it. The app also constructs its client-side ad config with
//   FreeWheel = null and provider = "generic" (videoplayer/player/g.smali).
//   Injecting return-void at index 0 of the constructor additionally skipped
//   the mandatory super.<init>() call, risking a VerifyError at class load.
//   The hook was therefore removed rather than retargeted.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// InnovidHelper — ad overlay mount entry point
// com/univision/descarga/videoplayer/utilities/innovid/InnovidHelper
//
// Method `h(boolean, WebView, VideoModel, Flow)` is the call that mounts the
// Innovid SSAI ad WebView overlay. Its sole call site in the player's ad-event
// switch dispatches it; returning void at index 0 stops the overlay before any
// WebView is attached or network request fires.
//
// Anchored on the "innovidAd" string constant, which appears only inside this
// mount method within InnovidHelper — so definingClass + that string uniquely
// identify it. The method name (`h`) is R8-obfuscated and NOT matched on.
//
// Version note (4.46.0_tv → 4.47.2_tv): the earlier fingerprint pinned the
// full parameter list, but R8 renamed the obfuscated VideoModel param type
// (…/models/video/v → …/models/video/t) between builds, which broke the exact
// match. The string anchor is immune to that obfuscation drift and matches
// both builds, so it replaces the parameter list. returnType + [PUBLIC, FINAL]
// are kept as cheap extra constraints (the other public-final-void methods —
// the a/b accessors and c(DisposeReason) teardown — don't carry this string).
//
// Version note (4.47.2_tv → 4.50.1_tv): the mount method was renamed h → u,
// and a SECOND public-final-void method (t(WebView, VideoModel)) in the same
// class now also carries the "innovidAd" string — so string + [PUBLIC, FINAL]
// + V is no longer unique and binds ambiguously. The mount method is the
// 4-arg overload whose first parameter is the boolean gate and last is the
// coroutine Flow; the decoy t() takes 2 params starting with the WebView. A
// custom predicate pins parameter count == 4 with a leading boolean (Z) —
// both stable across the VideoModel (v→t) obfuscation drift — to select the
// mount method unambiguously.
// ─────────────────────────────────────────────────────────────────────────────
object InnovidStartAdFingerprint : Fingerprint(
    definingClass = "Lcom/univision/descarga/videoplayer/utilities/innovid/InnovidHelper;",
    strings = listOf("innovidAd"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { method, _ ->
        method.parameters.size == 4 &&
            method.parameters[0].type == "Z" &&
            method.parameters[1].type == "Landroid/webkit/WebView;"
    }
)

// ─────────────────────────────────────────────────────────────────────────────
// LuraPlayer interstitials (ad-break) schedule — client-VMAP ad source
// com/akta/luraplayer/api/event/LuraEventData$InterstitialsData->getBreaks()
//
// ViX serves its VOD ads client-side (provider = "generic", VMAP from the GAM
// ad unit /6881/vix.tv/viewer/vod/androidtv/ondemand, confirmed on-device by the
// LuraAdPingExecutor AD_BREAK_START trace). After the VMAP is fetched and parsed,
// the ad engine emits a LuraEvent carrying an InterstitialsData payload whose
// getBreaks() is the List<LuraAdBreak> schedule. The player state machine (c5)
// ingests it — getData() → instanceof InterstitialsData → getBreaks() — and
// stores it into its schedule holder (j4 fields G/J, read back via c5.x() /
// getGetIntersititals()); the media3 layer (f5) then inserts those breaks as
// timeline ad-periods and plays the VAST creatives.
//
// Returning an EMPTY list (java.util.Collections.emptyList — NOT null: the
// ingestion site skips on a null but ingests-empty on an empty list) empties the
// schedule at its single ingestion point. With no breaks the player inserts no
// ad-periods → content plays through, seamless, no pre/mid-roll. This is the
// "empty the ad DATA, never the flow the player waits on" rule (RTE/Tubi/FoxOne
// IMA precedent): content playback runs on a separate path and is untouched.
//
// Why this seam and not the earlier attempts: the 4.51.0 ad IMPLEMENTATION was
// R8-flattened into com/akta/lura/<short>, so every impl-side anchor drifted or
// hit the wrong seam — d3.a→g$g is the MEDIA-FRAME producer (crashed content),
// m2 (LuraAdPingExecutor) and the quartile reporters fire DOWNSTREAM of delivery
// (device-proven: ad still played), and the LuraGam*.getAdTagUrl() config getters
// are never read at request time (device-proven: emptied to "" and the VMAP
// still fetched). getBreaks() lives in the RETAINED public `api/event` package —
// class + method names survive R8 — and sits UPSTREAM of the schedule store, so
// it is both obfuscation-proof and the true data source.
//
// Anchored on returnType + method name + enclosing class suffix (smallest unique
// set). The `$` in the nested class name is escaped for the Kotlin string.
// ─────────────────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────────
// media3 DashManifestParser.parse(Uri, InputStream) — server-stitched VOD ads
//
// On-demand ads are stitched by the Lura platform into a multi-period DASH
// manifest (clear ad periods around the Widevine content periods), so the
// manifest itself is the ad source. The injected AdPeriodFilter rewrites the
// manifest text before this method parses it (see Hook 1b in SkipAdsPatch).
//
// Device-proven dead end this replaces: emptying
// LuraGenericConfiguration.getBreaks() (the app-supplied "preroll" GAM tag)
// changed nothing — the pre-roll is not requested by the client at all.
//
// The parser class is R8-renamed (dash/manifest/d on 4.52.0) but keeps its
// overridable method names, so: the public non-synthetic parse(Uri, InputStream)
// on the class in media3's dash.manifest package. The bridge overload returning
// Object is excluded by the synthetic flag check.
// ─────────────────────────────────────────────────────────────────────────────
object DashManifestParseFingerprint : Fingerprint(
    custom = { method, classDef ->
        method.name == "parse" &&
            classDef.type.startsWith("Landroidx/media3/exoplayer/dash/manifest/") &&
            method.parameterTypes.map { it.toString() } ==
            listOf("Landroid/net/Uri;", "Ljava/io/InputStream;") &&
            method.returnType != "Ljava/lang/Object;"
    }
)

object InterstitialsDataGetBreaksFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    custom = { method, classDef ->
        method.name == "getBreaks" &&
            classDef.type.endsWith("/LuraEventData\$InterstitialsData;")
    }
)
