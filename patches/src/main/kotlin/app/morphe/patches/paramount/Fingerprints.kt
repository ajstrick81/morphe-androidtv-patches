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

// ---------------------------------------------------------------------------
// Patch 3: LIVE ad slate — AviaNetworkInterceptor.intercept(Chain)
//
// The AVIA player's okhttp NETWORK interceptor. The live DAI stream (manifest
// + every segment) flows through intercept(). Live sports ads are Google DAI
// "pod serving" — separately-addressable ad segments at
// dai.google.com/linear/pods/v1/... that 302-redirect to googlevideo ad media.
// We rewrite each ad-pod request to the SAME pod's "slate" rendition
// (/<pod>/<slot>/<adIdx>/<hash>/ -> /<pod>/slate/0/<hash>/), which is Paramount's
// own server-served "Commercial in Progress" branded card — a real segment on
// the live timeline. Matched by okhttp interface method name + AVIA class.
// okhttp3 is not renamed in this app, so Lokhttp3/* types are used directly.
// ---------------------------------------------------------------------------

internal object AviaNetworkInterceptorFingerprint : Fingerprint(
    returnType = "Lokhttp3/Response;",
    custom = { method, _ ->
        method.name == "intercept" &&
            method.definingClass.endsWith("/network/AviaNetworkInterceptor;")
    },
)
