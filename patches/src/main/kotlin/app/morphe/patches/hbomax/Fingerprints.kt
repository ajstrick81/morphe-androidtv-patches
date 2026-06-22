package app.morphe.patches.hbomax.ads

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// BoltNonLinearAdsRequest — Nonlinear (overlay) ad request serializer
// classes4.dex — exact class path and method name are sufficient identifiers.
// write$Self is the serialization write path — suppresses advertisingInfo
// (field index 2) and zeroes playbackId (field index 5) from the JSON body.
// ─────────────────────────────────────────────────────────────────────────────

internal object BoltNonLinearAdsRequestWriteSelfFingerprint : Fingerprint(
    custom = { method, _ ->
        // Newer builds mangle this to write$Self$_libraries_adtech_bolt_ad_fetcher
        // (a stable Kotlin Multiplatform module-disambiguation suffix), so match
        // by prefix rather than exact name to survive both naming schemes.
        method.definingClass == "Lcom/wbd/adtech/bolt/BoltNonLinearAdsRequest;" &&
            method.name.startsWith("write\$Self")
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// BoltDynamicAdFetcher — Nonlinear ad fetch coroutine continuation
// classes4.dex — exact class path and method name are sufficient identifiers.
// invokeSuspend discards the real ad list after fetchNonLinearAds returns,
// causing the coroutine collector to receive null instead of a populated list.
// ─────────────────────────────────────────────────────────────────────────────

internal object BoltDynamicAdFetcherInvokeSuspendFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/wbd/adtech/bolt/BoltDynamicAdFetcher\$fetchNonLinearAds\$1;" &&
            method.name == "invokeSuspend"
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// SsaiInfoTimelineBuilder — GMSS/AdSparx SSAI linear ad timeline builder
// classes4.dex — method names survive R8 as they are referenced by lambdas.
// buildAdBreaksFromAdSparxAdBreaks registers linear ad breaks with the
// RangeBuilder. Patched with return-void to suppress all SSAI ad break
// timeline registration for VOD and movies.
// access$buildAdBreaksFromAdSparxAdBreaks is the synthetic accessor used
// by buildTimeline inner lambdas — patched to close that call path too.
// ─────────────────────────────────────────────────────────────────────────────

internal object SsaiInfoTimelineBuilderBuildAdBreaksFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/wbd/beam/player/timelinemanager/timelineprovider/gmsstimelineprovider/timelinebuilder/SsaiInfoTimelineBuilder;" &&
            method.name == "buildAdBreaksFromAdSparxAdBreaks"
    },
)

internal object SsaiInfoTimelineBuilderAccessorFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/wbd/beam/player/timelinemanager/timelineprovider/gmsstimelineprovider/timelinebuilder/SsaiInfoTimelineBuilder;" &&
            method.name == "access\$buildAdBreaksFromAdSparxAdBreaks"
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// GenerateLiveTimelineEntriesForAdBreakKt — Live stream preroll ad entry builder
// classes.dex — exact class path and method name are sufficient identifiers.
// Returns empty ArrayList instead of building AdBreakEntry/AdEntry objects.
// The caller (generateLiveTimelineEntries) does addAll() on the result —
// empty list means no ad entries added to live timeline while chapter/content
// entries are built normally. Suppresses "1 of 2" countdown prerolls on
// live and episodic TV content.
// ─────────────────────────────────────────────────────────────────────────────

internal object GenerateLiveTimelineEntriesForAdBreakFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/discovery/adtech/core/models/timeline/GenerateLiveTimelineEntriesForAdBreakKt;" &&
            method.name == "generateLiveTimelineEntriesForAdBreak"
    },
)

internal object NowtilusEnabledFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass == "Lcom/mediamelon/core/qubit/ep/RegisterAPI;" &&
            method.name == "isNowtilusEnabled"
    },
)
