/*
 * MLB At Bat Android TV — Ad Patch Fingerprints
 *
 * Validated against:
 *   v26.8.1  (versionCode 1750000022) — com.bamnetworks.mobile.android.gameday
 *
 * ALL FINGERPRINTS VERIFIED via full APK bytecode analysis (androguard).
 *
 * CURRENT STATE (confirmed via logcat 06-18):
 *   ✅ MLB EVI (/EVI/ segments): BLOCKED — zero EVI segments in break log
 *   ✅ TXXX dispatch: BLOCKED — zero TXXX entries in log
 *   ❌ Google DAI (dclk_video_ads): STILL FETCHING — 22 segments in break
 *
 * WHY dclk_video_ads PERSISTS:
 *   Lb6/k;.b(Uri) returns empty zzdm ✅ (our patch fires)
 *   BUT zzan.requestStream(emptyZzdm) SUCCEEDS anyway — the IMA SDK uses
 *   server-side session state from AdsLoader initialization, not just the
 *   StreamRequest parameters. The empty zzdm passes the null check
 *   ("StreamRequest cannot be null") and IMA SDK returns a valid DAI
 *   manifest URL regardless of missing contentSourceId/videoId/assetKey.
 *
 * CORRECT FIX — Lb6/h;.b0(Lq5/w;)V:
 *   This is called when ImaServerSideAdInsertionMediaSource starts up.
 *   It constructs Lb6/h$g; (which holds AdsLoader + StreamRequest) and
 *   fires requestStream() via Ll6/l.f(). return-void here prevents the
 *   entire SSAI media source from initializing — no requestStream() call,
 *   no DAI manifest URL, no dclk_video_ads segments.
 *
 *   VERIFIED: string "ImaServerSideAdInsertionMediaSource" is UNIQUE to
 *   this method in the entire APK. proto=(Lq5/w;)V, registers=10.
 *
 *   Also patching Lb6/h;.m0(StreamManager)V — the DAI StreamManager
 *   event handler ("IMA DAI Stream Event: ", "GSTREAM:DAI"). Belt-and-
 *   suspenders: even if b0() is somehow bypassed, m0() returning void
 *   prevents the StreamManager from processing the DAI stream and
 *   scheduling ad segments.
 *
 * IMA SDK StreamRequest (verified from createVodStreamRequest bytecode):
 *   Class:       Lcom/google/ads/interactivemedia/v3/impl/zzdm;
 *   Constructor: <init>(Lcom/google/ads/interactivemedia/v3/internal/zzafs;)V
 *   VOD type:    Lcom/google/ads/interactivemedia/v3/internal/zzafs;->zzd
 */

package app.morphe.patches.mlbtv

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// ---------------------------------------------------------------------------
// Patch 1a: VOD SSAI — createVodStreamRequest (3-arg)
// ---------------------------------------------------------------------------

internal object VodStreamRequest3ArgFingerprint : Fingerprint(
    returnType = "Lcom/google/ads/interactivemedia/v3/api/StreamRequest;",
    custom = { method, _ ->
        method.name == "createVodStreamRequest" &&
            method.definingClass ==
                "Lcom/google/ads/interactivemedia/v3/api/ImaSdkFactory;" &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes.all { it == "Ljava/lang/String;" }
    },
)

// ---------------------------------------------------------------------------
// Patch 1b: VOD SSAI — createVodStreamRequest (4-arg)
// ---------------------------------------------------------------------------

internal object VodStreamRequest4ArgFingerprint : Fingerprint(
    returnType = "Lcom/google/ads/interactivemedia/v3/api/StreamRequest;",
    custom = { method, _ ->
        method.name == "createVodStreamRequest" &&
            method.definingClass ==
                "Lcom/google/ads/interactivemedia/v3/api/ImaSdkFactory;" &&
            method.parameterTypes.size == 4 &&
            method.parameterTypes.all { it == "Ljava/lang/String;" }
    },
)

// ---------------------------------------------------------------------------
// Patch 2: SSAI MediaSource Startup — Lb6/h;.b0(Lq5/w;)V
//
// VERIFIED: proto=(Lq5/w;)V, registers=10
//   String: "ImaServerSideAdInsertionMediaSource" (UNIQUE in entire APK)
//
// Called when ImaServerSideAdInsertionMediaSource (Lb6/h;) starts up.
// Constructs Lb6/h$g; (AdsLoader + StreamRequest holder) and fires
// requestStream() via Ll6/l.f(). return-void prevents the SSAI source
// from initializing — no requestStream() call, no DAI manifest URL,
// no dclk_video_ads segments fetched.
//
// This replaces the previous SsaiStreamRequestFingerprint (Lb6/k;.b())
// which returned empty zzdm but IMA SDK still generated a valid manifest
// via server-side session state regardless of empty StreamRequest params.
// ---------------------------------------------------------------------------

internal object SsaiMediaSourceStartupFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("ImaServerSideAdInsertionMediaSource"),
    custom = { method, _ ->
        method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Lq5/w;"
    },
)

// ---------------------------------------------------------------------------
// Patch 3: DAI StreamManager Event Handler — Lb6/h;.m0(StreamManager)V
//
// VERIFIED: proto=(StreamManager)V, registers confirmed present in Lb6/h;
//   Strings: "IMA DAI Stream Event: ", "GSTREAM:DAI", ", streamId: "
//
// Called when the IMA SDK StreamManager fires events (stream initialized,
// ad started, etc.). return-void prevents the StreamManager from processing
// the DAI stream, scheduling ad segments, or updating player state.
// Belt-and-suspenders with Patch 2.
// ---------------------------------------------------------------------------

internal object DaiStreamManagerHandlerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "IMA DAI Stream Event: ",
        "GSTREAM:DAI",
    ),
    custom = { method, _ ->
        method.parameterTypes.size == 1 &&
            method.parameterTypes[0] ==
                "Lcom/google/ads/interactivemedia/v3/api/StreamManager;"
    },
)

// ---------------------------------------------------------------------------
// Patch 4: TXXX Metadata Dispatcher — Lu70/i;.onMetadata(Ll5/t;)V
//
// VERIFIED: registers=5, string="[ExoMediaPlayer] metadata received from stream"
// UNIQUE: only onMetadata in APK with this exact string.
//
// Upstream dispatcher for ALL HLS timed metadata. return-void stops all
// downstream listener dispatch — MLB EVI coroutines and IMA SSAI callbacks
// never fire. Confirmed working: zero TXXX and zero EVI in logcat.
// ---------------------------------------------------------------------------

internal object ExoMediaPlayerMetadataFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("[ExoMediaPlayer] metadata received from stream"),
    custom = { method, _ ->
        method.name == "onMetadata" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Ll5/t;"
    },
)

// ---------------------------------------------------------------------------
// Patch 5: "Commercial Break in Progress" overlay
//
// Rather than fighting the SSAI/DAI plumbing itself (Patches 2/3, whose
// effect on live games is unconfirmed per AtBatPatch.kt's risk analysis),
// this suppresses harmful between-innings gambling ad *content* at the
// presentation layer: the underlying ad break still plays exactly as the
// app and IMA SDK intend (safe for VOD and live alike, since nothing in
// the playback pipeline is touched), but the viewer never sees or hears it.
//
// Lb6/h$d;->b(Ll5/q;)Lg6/w; (the SSAI MediaSource.Factory) builds the IMA
// StreamDisplayContainer from the app's ad-overlay ViewGroup
// (Ll5/d;->getAdViewGroup()) — the same ViewGroup IMA itself uses for
// companion ads/friendly obstructions. Matched structurally on the
// createStreamDisplayContainer call rather than by class/method name,
// since Lb6/h$d and its method names are R8-obfuscated and could shift
// between builds; createStreamDisplayContainer is a fixed, external IMA
// SDK API name.
// ---------------------------------------------------------------------------

internal object SsaiDisplayContainerFingerprint : Fingerprint(
    returnType = "Lg6/w;",
    custom = { method, _ ->
        method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Ll5/q;" &&
            method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name ==
                    "createStreamDisplayContainer"
            } == true
    },
)

// Lb6/h$i;->onAdBreakStarted()V / onAdBreakEnded()V — the IMA
// VideoStreamPlayer's ad-break lifecycle callbacks. Both are no-op stubs in
// the unmodified app. onAdBreakStarted/onAdBreakEnded are fixed names from
// the external VideoStreamPlayer interface, so R8 cannot rename them even
// though Lb6/h$i itself is obfuscated.

internal object AdBreakStartedFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.name == "onAdBreakStarted" && method.parameterTypes.isEmpty()
    },
)

internal object AdBreakEndedFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.name == "onAdBreakEnded" && method.parameterTypes.isEmpty()
    },
)
