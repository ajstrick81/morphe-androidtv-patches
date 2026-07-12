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
// ─────────────────────────────────────────────────────────────────────────────
object InnovidStartAdFingerprint : Fingerprint(
    definingClass = "Lcom/univision/descarga/videoplayer/utilities/innovid/InnovidHelper;",
    strings = listOf("innovidAd"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

// ─────────────────────────────────────────────────────────────────────────────
// LuraPlayer ad-break scheduler — linear (client-side VAST/VMAP) ad trigger
// classes6.dex / com/akta/luraplayer/implementations/ads/analyzers/
//
// The abstract base analyzer's TIME_UPDATED handler is the single entry point
// that decides when a linear ad break fires. On every time update it dispatches
// to the VOD subclass's per-tick evaluator (l(J)), which launches the
// LuraVodAdAnalyzer coroutine that locates the due break and plays it, or to
// the live subclass for SGAI/DASH timing. Returning void at index 0 stops the
// VOD client-side ad-break evaluation at its root, so no VAST creative is
// requested or played. Content playback is unaffected — the analyzer is an
// event subscriber, separate from the media pipeline.
//
// ViX uses provider = "generic" (client-side VAST), so the ad creative is a
// separate playback the analyzer triggers; cutting the trigger removes the ad.
// (Live SGAI ads are server-stitched into the manifest and are NOT removable
// from the client — that requires a network/manifest-layer block.)
//
// Robustness: both the analyzer class and method names are R8-obfuscated
// single letters, so they are NOT matched on. The handler is pinned by its
// parameter type — Lcom/akta/luraplayer/api/event/LuraEventData$TimeUpdate; —
// which lives in LuraPlayer's un-obfuscated public `api` package and is a
// stable anchor. This method (taking exactly that single param, returning V)
// is unique across the entire analyzers package.
// ─────────────────────────────────────────────────────────────────────────────
object LuraAdBreakSchedulerFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.type.startsWith("Lcom/akta/luraplayer/implementations/ads/analyzers/") &&
            method.returnType == "V" &&
            method.parameters.size == 1 &&
            method.parameters[0].type ==
            "Lcom/akta/luraplayer/api/event/LuraEventData\$TimeUpdate;"
    }
)
