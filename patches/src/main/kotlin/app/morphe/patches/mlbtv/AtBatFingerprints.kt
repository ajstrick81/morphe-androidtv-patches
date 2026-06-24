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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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
// Patch 5: Ad-Break Overlay
//
// VERIFIED via androguard dex analysis of this build (7 classesN.dex):
//
//   Lb6/h$d;.b(Ll5/q;)Lg6/w; — builds the SSAI display container:
//     invoke-interface ..., Ll5/d;->getAdViewGroup()Landroid/view/ViewGroup;
//     move-result-object v3
//     invoke-static v3, v7, ImaSdkFactory;->createStreamDisplayContainer(
//         Landroid/view/ViewGroup;
//         Lcom/google/ads/interactivemedia/v3/api/player/VideoStreamPlayer;
//     )Lcom/google/ads/interactivemedia/v3/api/StreamDisplayContainer;
//   v7 (the VideoStreamPlayer passed alongside the ViewGroup) is a freshly
//   constructed Lb6/h$i;, confirmed below.
//
//   Lb6/h$i; implements VideoStreamPlayer with no-op bodies (return-void,
//   1 register = this) for:
//     onAdBreakStarted()V
//     onAdBreakEnded()V
//   These are the only two implementations of these method names found in
//   the entire app's dex set (the interface's own abstract declaration is
//   the only other occurrence and has no implementation to match against).
//
// createStreamDisplayContainer is a literal Google IMA SDK method name
// (unobfuscated, since IMA SDK classes ship as a third-party dependency),
// and is called from exactly one place app-wide in this build — so the
// fingerprint below needs no hardcoded obfuscated class name to stay
// unique, only the method-name match.
// ---------------------------------------------------------------------------

internal object SsaiDisplayContainerFingerprint : Fingerprint(
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                ?.reference?.let { (it as? MethodReference)?.name } ==
                "createStreamDisplayContainer"
        } == true
    },
)

internal object AdBreakStartedFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.name == "onAdBreakStarted" &&
            method.parameterTypes.isEmpty() &&
            method.implementation != null
    },
)

internal object AdBreakEndedFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.name == "onAdBreakEnded" &&
            method.parameterTypes.isEmpty() &&
            method.implementation != null
    },
)

// ---------------------------------------------------------------------------
// Patch 6: HLS Manifest Ad-Segment Stripping — Lr5/a;.f(Lq5/i;)J
//
// VERIFIED via androguard dex analysis of this build: Lr5/a; is Media3's
// OkHttpDataSource — its <clinit> calls Ll5/r;.a("media3.datasource.okhttp"),
// a literal androidx.media3 telemetry tag string that ships unobfuscated
// with the library and is stable across app rebuilds, unlike the renamed
// class/method names around it.
//
// f(Lq5/i;)J is OkHttpDataSource.open(DataSpec): it issues the network
// request and stores the resulting InputStream into the data source's
// `k` field. This is the actual fetch path for both the HLS manifest and
// every media segment (live and VOD) — confirmed empirically that the
// IMA SDK method-blocks in Patches 2/3/4 do NOT stop dclk_video_ads
// segments from being fetched here, because they're stitched into the
// manifest server-side rather than requested via a separate client-side
// "ad request" call.
//
// Matched by: method name "f", single parameter (the DataSpec), and the
// class-level "media3.datasource.okhttp" <clinit> string, rather than any
// obfuscated name — keeping this fingerprint stable across rebuilds even
// though Lr5/a;'s own member names are renamed per-build by R8.
// ---------------------------------------------------------------------------

internal object OkHttpDataSourceOpenFingerprint : Fingerprint(
    returnType = "J",
    custom = { method, classDef ->
        method.name == "f" &&
            method.parameterTypes.size == 1 &&
            classDef.methods.any { candidate ->
                candidate.name == "<clinit>" &&
                    candidate.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.CONST_STRING &&
                            ((instruction as ReferenceInstruction).reference as? StringReference)
                                ?.string == "media3.datasource.okhttp"
                    } == true
            }
    },
)
