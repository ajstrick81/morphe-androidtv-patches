package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ===========================================================================
// Pluto TV v17.x (Paramount+/AVIA rebuild) — ad-removal fingerprints.
//
// v17 replaced the leanback stack with Paramount's AVIA player (same internals
// as Paramount+ / CBS). media3 and okhttp are R8-minified, but AVIA's OWN classes
// are NOT name-obfuscated, so every anchor below matches AVIA's readable leaf
// class + method name (the hardening pattern proven on the Paramount+ patch).
// See SkipAdsV17Patch.kt for the mechanism and PlutoV17ManifestProbe for the
// manifest surgery. All four confirmed present in 17.2.0 (versionCode 520000962).
// ===========================================================================

// Hook 1 — AviaDAIResourceProvider.shouldPlayAd(AviaAdPod): Z  [required]
//
// The AVIA DAI provider gates each VOD ad pod through shouldPlayAd(); its position
// monitor skips the pod natively when this returns false. Forcing false removes the
// DAI pre-roll while the stream keeps playing. Private, boolean return; matched by
// its (readable, stable) name + Z return + unique leaf class name.
internal object AviaDaiShouldPlayAdFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.name == "shouldPlayAd" &&
            method.definingClass.endsWith("/AviaDAIResourceProvider;")
    },
)

// Hook 2 — AviaIMAResourceProvider.shouldPlayAd(AviaAdPod): Z  [optional]
//
// The IMA counterpart of Hook 1. Not all titles route through it, so the patch
// applies it via methodOrNull — a build without this provider simply skips it.
internal object AviaImaShouldPlayAdFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.name == "shouldPlayAd" &&
            method.definingClass.endsWith("/AviaIMAResourceProvider;")
    },
)

// Hook 3 — AviaDashParser.parse(Uri, InputStream): <DashManifest>  [required]
//
// The mid-roll ads are stitched into the multi-period DASH manifest (FreeWheel SSAI),
// so emptying ad metadata is not enough — the ad <Period>s must be removed from the
// manifest itself. media3's parser is R8-renamed (can't be hooked by name like
// leanback), so we hook AVIA's OWN un-renamed parse() and swap its InputStream for a
// cleaned one (PlutoV17ManifestProbe.cleanManifestStream) at entry.
//
// parse(Uri, InputStream) is NOT unique — R8 also emits a synthetic bridge with the
// same params returning Ljava/lang/Object;. The custom predicate excludes that bridge
// (returnType != Object) so we anchor the REAL DASH parser (returns the obfuscated
// DashManifest type, whose exact R8 name varies per build and so is never hardcoded).
internal object AviaDashParserParseFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/net/Uri;",
        "Ljava/io/InputStream;",
    ),
    custom = { method, _ ->
        method.name == "parse" &&
            method.definingClass.endsWith("/AviaDashParser;") &&
            method.returnType != "Ljava/lang/Object;"
    },
)

// Hook 4 — CLID3Processor.processAdPodStartAsync(Map, long): V  [required]
//
// On VOD, the ID3/CLID3 beacon path also fires a CSAI ad call (processAdPodStartAsync),
// which can re-introduce ad fetches independent of the manifest. When Hook 3 has seen a
// static (VOD) manifest, PlutoV17ManifestProbe.shouldSuppressDynamicAdFetch() is true;
// prepending that to the method's early-return guard suppresses the CSAI call on VOD
// while leaving live/linear (dynamic) untouched. Readable AVIA name + Map,long params.
internal object Clid3ProcessAdPodStartFingerprint : Fingerprint(
    parameters = listOf(
        "Ljava/util/Map;",
        "J",
    ),
    returnType = "V",
    custom = { method, _ ->
        method.name == "processAdPodStartAsync" &&
            method.definingClass.endsWith("/CLID3Processor;")
    },
)

// Hook 6 — CbsPauseWithAdsOverlay render dispatch: static V (overlay, state)  [optional]
//
// v17 TV draws pause ads in Paramount's own CbsPauseWithAdsOverlay, not through
// IMA's pause-ad slot (Hook 5 is a no-op on TV). The static render method takes
// (overlay, state) and, per state, loads the ad image and fades the overlay in.
// R8 renames the method and the state type and strips the log strings the
// Paramount+ fingerprint uses, so it is matched by shape: static, void, two
// params, the first being the overlay itself. Overlay starts at alpha 0, so
// returning at entry keeps it invisible.
internal object CbsPauseOverlayRenderFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.definingClass.endsWith("/CbsPauseWithAdsOverlay;") &&
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0].toString() == method.definingClass
    },
)

// Hook 7 — ad beacon tracker consumeAds(adsData): V  [optional]
//
// With the ad periods stripped (Hook 3) no ad plays, but the beacon tracker
// (tag "_BeaconFiring") still receives the stitcher's ad-break data and fires
// IMPRESSION_SLOT_START beacons to FreeWheel (fwmrm.net) and k.pluto.tv at each
// break position (device-observed 17.2.0). consumeAds already returns early when
// the feature is disabled; returning at entry takes that same path, so no ad
// timeline is loaded and no impressions are reported. Class and method are
// R8-renamed; anchored on the method's own disabled-path message.
internal object BeaconConsumeAdsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Feature is disabled, can't consume the adsData"),
)

// Hook 8 — AviaResourceProvider.getContentDuration(): J  [all optional]  (issue #300)
//
// The seekbar's scale is AviaPlayerInfo.contentDuration, which AbstractAviaPlayer sets
// once from the active provider's getContentDuration(). In 17.2.0 the DAI provider
// computes it as getAbsoluteDuration() minus the summed durations of adPodList — ad time
// subtracted from the player's duration. After Hook 3 the player's duration already
// excludes the ads, but adPodList is still populated, so the deduction happens twice and
// the bar is scaled shorter than the content. Hooking each provider's returns lets the
// extension substitute the duration the strip actually measured.
//
// Anchored on the readable AVIA leaf class + method name; the return type is J and there
// are no parameters, so no obfuscated type is referenced. Each is resolved optionally:
// only the provider actually in use for a given title matters, and a build or title that
// routes through a different one simply skips the others.
internal object AviaDaiContentDurationFingerprint : Fingerprint(
    parameters = emptyList(),
    returnType = "J",
    custom = { method, _ ->
        method.name == "getContentDuration" &&
            method.definingClass.endsWith("/AviaDAIResourceProvider;")
    },
)

internal object AviaImaContentDurationFingerprint : Fingerprint(
    parameters = emptyList(),
    returnType = "J",
    custom = { method, _ ->
        method.name == "getContentDuration" &&
            method.definingClass.endsWith("/AviaIMAResourceProvider;")
    },
)

internal object AviaUriContentDurationFingerprint : Fingerprint(
    parameters = emptyList(),
    returnType = "J",
    custom = { method, _ ->
        method.name == "getContentDuration" &&
            method.definingClass.endsWith("/AviaUriResourceProvider;")
    },
)

// REMOVED — Hook 8 (first attempt): AviaPlayer.seek(long, boolean): V
//
// Kept as a note because the reasoning is easy to repeat. The idea was that the UI
// computed seek targets against Pluto's ad-inclusive metadata duration, so they needed
// remapping onto the stripped clock. The caller audit supported half of that — only
// AviaDecor and AbstractAviaDecor call seek(J,Z), so it is purely a UI entry point —
// but the coordinate assumption was never verified and turned out to be wrong.
//
// On-device (17.2.0, Burn Notice): three successive forward nudges of ~12s each moved
// playback BACKWARDS ~138s every time, because each requested target was derived from
// the player's own (already stripped) position and the hook subtracted the ad time
// again. The displayed position snapped back by exactly the removed ad time. Only the
// SCALE was ever wrong, not the needle, so the duration is corrected instead and no
// position is remapped. Do not reintroduce a seek remap without first proving, on a
// device, which clock the UI's targets are actually in.
//
// The original rationale is preserved below, since the parts of it that were correct
// (the caller audit, the register encoding) are worth keeping:
//
// Hook 3 shortens the player's timeline but not Pluto's own duration metadata, so
// the seekbar is drawn against the ad-INCLUSIVE length while playback runs on the
// content-only one. A seek the UI computes therefore arrives in the wrong clock:
// it overshoots by all the ad time before it, which is the wrong-seek half of #300.
// Routing the requested position through PlutoV17ManifestProbe.toStrippedMs puts it
// back on the player's clock.
//
// WHY THIS METHOD AND NOT _seek(J): the two are split exactly along the coordinate
// boundary in 17.2.0, confirmed by every caller in the decomp.
//   seek(J,Z)  <- AviaDecor$1 and AbstractAviaDecor ONLY (the player controls), so
//                 every caller is the UI, in original/metadata coordinates.
//   _seek(J)   <- AviaDAIResourceProvider, AviaIMAResourceProvider,
//                 AviaUriResourceProvider and AbstractAviaPlayer.seekToLive, i.e.
//                 ad-pod transitions, retries and live-edge jumps. Those positions
//                 are read back FROM the player and are already stripped, so
//                 remapping them would subtract the ad time a second time and send
//                 playback backwards. _seek must stay untouched.
//
// seek(J,Z) is declared abstract on AbstractAviaPlayer and implemented on AviaPlayer,
// so the predicate anchors the leaf class to get a method with a body. Note that
// "/AviaPlayer;" does not match "AbstractAviaPlayer;" (the preceding character is
// 't', not '/'), so the abstract declaration is excluded.
//
// This covers scrubbing, NOT the restoration of a Continue Watching bookmark: that
// arrives through AbstractAviaMediaAsset.setStartPosition(J), which the app calls
// before the manifest is fetched — so the strip has not run yet and no map exists at
// that moment. That seam needs a post-parse application point and is not wired here.
//     internal object AviaPlayerSeekFingerprint : Fingerprint(
//         parameters = listOf("J", "Z"),
//         returnType = "V",
//         custom = { method, _ ->
//             method.name == "seek" &&
//                 method.definingClass.endsWith("/AviaPlayer;")
//         },
//     )
