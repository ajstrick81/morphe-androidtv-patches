/*
 * Paramount+ Android TV — Ad Patch Fingerprints
 *
 * Validated against v16.17.0 (versionCode 520000758) — com.cbs.ott.
 * See ParamountPatch.kt for the mechanism write-up.
 */

package app.morphe.patches.paramount

import app.morphe.patcher.Fingerprint

// ---------------------------------------------------------------------------
// Patch 1: VOD ad gate — AviaDAIResourceProvider.shouldPlayAd(AviaAdPod)
//
// The AVIA DAI provider gates each ad pod through shouldPlayAd(); its position
// monitor (AviaDAIResourceProvider$3) skips the pod natively when this returns
// false. Forcing false removes VOD pre-roll ads while the DAI stream keeps
// playing. Private direct method, matched by name + defining class.
// ---------------------------------------------------------------------------

internal object ShouldPlayAdFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.name == "shouldPlayAd" &&
            method.definingClass.endsWith(
                "/player/resource/dai/AviaDAIResourceProvider;",
            )
    },
)

// ---------------------------------------------------------------------------
// Patch 2: Pause ads — CbsPauseWithAdsOverlay state machine
// ---------------------------------------------------------------------------

internal object PauseAdOverlayFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("renderState: ", " not updating overlay."),
    custom = { method, _ ->
        method.definingClass.endsWith("CbsPauseWithAdsOverlay;")
    },
)
