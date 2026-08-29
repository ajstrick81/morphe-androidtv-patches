/*
 * Paramount+ Android TV — Ad Patch Fingerprints
 *
 * Validated against v16.17.0 (versionCode 520000758) and v16.19.0
 * (versionCode 520000827) — com.cbs.ott. All three targets survived the
 * 16.17 -> 16.19 bump unchanged. See ParamountPatch.kt for the mechanism.
 *
 * HARDENING NOTES (survive future version bumps):
 *   - Each class anchor matches the UNIQUE leaf class name (e.g.
 *     "/AviaDAIResourceProvider;") rather than the full package path, so a
 *     package reshuffle inside the AVIA library doesn't break the match. Each
 *     leaf name was confirmed unique in the APK, and inner classes end in
 *     "$N;" so they can't be caught by the outer-class anchor.
 *   - Method-name anchors are used ONLY where the name is stable: shouldPlayAd
 *     and intercept are readable (AVIA is not name-obfuscated; intercept is a
 *     fixed okhttp interface method). CbsPauseWithAdsOverlay's render method IS
 *     R8-renamed (a single letter that changes per version), so it can only be
 *     found by its log string — see Patch 2.
 *   - String anchors are kept to the SINGLE most distinctive literal to halve
 *     the string-drift failure surface (two required strings = two ways to
 *     break); the chosen literal is specific enough to stay unique in-class.
 */

package app.morphe.patches.paramount

import app.morphe.patcher.Fingerprint

// ---------------------------------------------------------------------------
// Patch 1: VOD ad gate — AviaDAIResourceProvider.shouldPlayAd(AviaAdPod)
//
// The AVIA DAI provider gates each ad pod through shouldPlayAd(); its position
// monitor (AviaDAIResourceProvider$3) skips the pod natively when this returns
// false. Forcing false removes VOD pre-roll ads while the DAI stream keeps
// playing. Private direct method — matched by its (readable, stable) name +
// boolean return + unique leaf class name.
// ---------------------------------------------------------------------------

internal object ShouldPlayAdFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.name == "shouldPlayAd" &&
            method.definingClass.endsWith("/AviaDAIResourceProvider;")
    },
)

// ---------------------------------------------------------------------------
// Patch 2: Pause ads — CbsPauseWithAdsOverlay state machine
//
// The overlay-render method is R8-renamed (a per-version single letter), so it
// cannot be matched by name. It is instead anchored by its distinctive render
// log literal + void return + the (stable, resource-referenced) leaf class
// name. " not updating overlay." is the more distinctive of the two original
// log strings and is unique within the class; matching it alone means an edit
// to the "renderState: " prefix no longer breaks the patch.
// ---------------------------------------------------------------------------

internal object PauseAdOverlayFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(" not updating overlay."),
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
// the live timeline. Matched by the fixed okhttp interface method name
// (intercept), the okhttp3.Response return, and the unique leaf class name.
// okhttp3 is not renamed in this app, so Lokhttp3/* types are used directly.
// ---------------------------------------------------------------------------

internal object AviaNetworkInterceptorFingerprint : Fingerprint(
    returnType = "Lokhttp3/Response;",
    custom = { method, _ ->
        method.name == "intercept" &&
            method.definingClass.endsWith("/AviaNetworkInterceptor;")
    },
)
