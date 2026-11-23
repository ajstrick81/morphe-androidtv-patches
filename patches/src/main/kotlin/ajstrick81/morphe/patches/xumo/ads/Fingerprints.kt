package ajstrick81.morphe.patches.xumo.ads

import app.morphe.patcher.Fingerprint

// ===========================================================================
// Xumo Play Android TV (com.xumo.xumo.tv) — ad-removal fingerprints.
//
// The app is UNOBFUSCATED (readable class/method names) as of 4.9.147, so we
// anchor on the real names. Xumo's player managers run a cue-driven ad flow:
// a manifest CUE-OUT is detected, the client fetches a VAST/AMS ad and plays it
// as a separate media item, then resumes content. playAmsOrImaAd(PlayAms) is the
// final "now play the fetched ad" step; no-opping it means the client never
// switches to the ad media item, so content plays straight through.
//
// playAmsOrImaAd is defined on BOTH player managers:
//   • com.xumo.xumo.tv.manager.ExoPlayerManager            (VOD — primary target)
//   • com.xumo.xumo.tv.component.tif.XumoTvInputPlayer      (Live TV via TIF)
// so there is one fingerprint per defining class. Live ads are often FreeWheel
// SSAI (baked into the stream — not removable client-side); hooking the Live
// definition only suppresses the client-fetch path, which is harmless if that
// stream is SSAI.
//
// NOTE: FreeWheel SSAI avails (handleCueOutDetected / SSAIState, tag "SSAI-FW")
// are server-stitched and NOT addressed here — this first patch targets the
// client-fetch (CSAI, AMS/IMA/VAST) path only.
// ===========================================================================

// playAmsOrImaAd(PlayAms): V on ExoPlayerManager (VOD). [primary]
internal object ExoPlayerPlayAmsOrImaAdFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/xumo/xumo/tv/data/response/PlayAms;"),
    custom = { method, _ ->
        method.name == "playAmsOrImaAd" &&
            method.definingClass.endsWith("/ExoPlayerManager;")
    },
)

// playAmsOrImaAd(PlayAms): V on XumoTvInputPlayer (Live TV). [optional]
internal object TifPlayAmsOrImaAdFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/xumo/xumo/tv/data/response/PlayAms;"),
    custom = { method, _ ->
        method.name == "playAmsOrImaAd" &&
            method.definingClass.endsWith("/XumoTvInputPlayer;")
    },
)
