package app.morphe.patches.hbomax.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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

// ─────────────────────────────────────────────────────────────────────────────
// media3 DefaultHttpDataSource.open(DataSpec) — the HTTP fetch path for the
// manifest and every media segment. HBO wires this via
// com.discovery.player.datasource.HttpURLConnectionDataSourceFactoryCreator,
// which builds a media3 DefaultHttpDataSource$Factory.
//
// The class is R8-renamed per build (was Lx02; on 7.9.0.61), so it is matched
// by content, not name: the media3 DefaultHttpDataSource is the only class that
// carries the literal log tag "DefaultHttpDataSource" AND declares the
// interface method open(DataSpec)J (returns a long, single DataSpec param).
// This mirrors MLB At Bat's OkHttpDataSourceOpenFingerprint, which anchors on
// the media3 "media3.datasource.okhttp" tag for the OkHttp variant.
//
// The guard is injected at index 0 of open(), before the method's internal
// try-block, so a thrown IOException propagates straight to the ExoPlayer
// loader (resiliency-v5.1 CDN failover) rather than being swallowed here.
// ─────────────────────────────────────────────────────────────────────────────

internal object DefaultHttpDataSourceOpenFingerprint : Fingerprint(
    returnType = "J",
    custom = { method, classDef ->
        method.name == "open" &&
            method.parameterTypes.size == 1 &&
            classDef.methods.any { candidate ->
                candidate.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.CONST_STRING &&
                        ((instruction as ReferenceInstruction).reference as? StringReference)
                            ?.string == "DefaultHttpDataSource"
                } == true
            }
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// PlayableKt.getStreamInfo(Playable, StreamInfo$Type) — resolves a stream by type
// from the Playable's stream list. An ad-supported Playable carries a PRIMARY
// (ad-stitched, …/dash.mpd) and a FALLBACK (ad-free, …/<hash>_fallback.mpd on the
// alternate CDN) stream. Remapping a PRIMARY request to FALLBACK (when present)
// makes the player load the ad-free manifest from the first request — verified
// primary/fallback URL pair captured on-device 7.9.0.61. Class name not obfuscated.
// ─────────────────────────────────────────────────────────────────────────────

internal object GetStreamInfoFingerprint : Fingerprint(
    custom = { method, _ ->
        method.definingClass ==
            "Lcom/discovery/player/common/models/PlayableKt;" &&
            method.name == "getStreamInfo"
    },
)
