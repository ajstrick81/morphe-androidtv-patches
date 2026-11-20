package ajstrick81.morphe.patches.discoveryplus.ads

import app.morphe.patcher.Fingerprint

// All targets below are verified in the smali of discovery+ (Android TV) 22.1.0.71.
// Class names in these WBD packages are not obfuscated (they are kept for Kotlin
// serialization / reflection), so exact class + method names are stable identifiers.

// ─────────────────────────────────────────────────────────────────────────────
// PlayableKt.getStreamInfo(Playable, StreamInfo$Type) — resolves a stream by type.
// Same method HBO Max's "Prefer Ad-Free Stream" hooks. classes2.dex.
// ─────────────────────────────────────────────────────────────────────────────
internal object DplayGetStreamInfoFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass == "Lcom/discovery/player/common/models/PlayableKt;" &&
            method.name == "getStreamInfo"
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// BoltNonLinearAdsRequest.write$Self$_libraries_adtech_bolt_ad_fetcher — serializer for
// the nonlinear (overlay / pause) ad request. classes3.dex. Prefix match, as on HBO.
// ─────────────────────────────────────────────────────────────────────────────
internal object DplayBoltNonLinearAdsRequestWriteSelfFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass == "Lcom/wbd/adtech/bolt/BoltNonLinearAdsRequest;" &&
            method.name.startsWith("write\$Self")
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// BoltDynamicAdFetcher$fetchNonLinearAds$1.invokeSuspend — coroutine that receives the
// nonlinear ad list. On 22.1.0.71: invoke-virtual/range {v0..v6} fetchNonLinearAds-hUnOzRk,
// then move-result-object p0. classes3.dex.
// ─────────────────────────────────────────────────────────────────────────────
internal object DplayBoltDynamicAdFetcherInvokeSuspendFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/wbd/adtech/bolt/BoltDynamicAdFetcher\$fetchNonLinearAds\$1;" &&
            method.name == "invokeSuspend"
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// GenerateLiveTimelineEntriesForAdBreakKt.generateLiveTimelineEntriesForAdBreak(List, I)List
// — live-stream preroll ad entry builder. classes.dex.
// ─────────────────────────────────────────────────────────────────────────────
internal object DplayGenerateLiveTimelineEntriesForAdBreakFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/discovery/adtech/core/models/timeline/GenerateLiveTimelineEntriesForAdBreakKt;" &&
            method.name == "generateLiveTimelineEntriesForAdBreak"
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// TimelineStateKt.isAdBreakAlreadyWatched(TimelineState, I)Z — the single "has this ad
// break been watched?" check of the Beam GMSS (VOD) seek mediator. classes4.dex.
//
// Every watched-state decision routes through it:
//   • DefaultAdSkipManager.isAdBreakAlreadyWatched(I)  → GmssSeekMediator.onRangeEnded:
//     when the next leaf range is a LinearAdRange of a WATCHED break, the app issues a
//     ProgrammaticSeek(SKIP_ALREADY_WATCHED_AD_BREAK) to getNextContentStartPosition.
//   • DefaultAdSkipManager.getFirstUnwatchedMidrollAdBreak / redirectSeekIntoAdBreak:
//     forward seeks over an UNWATCHED break are redirected into it (seek lock).
// ─────────────────────────────────────────────────────────────────────────────
internal object DplayIsAdBreakAlreadyWatchedFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/wbd/beam/player/timelinemanager/seekmediator/gmssseekmediator/models/TimelineStateKt;" &&
            method.name == "isAdBreakAlreadyWatched"
    },
)
