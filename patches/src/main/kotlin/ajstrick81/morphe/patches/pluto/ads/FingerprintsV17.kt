package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.Fingerprint

// ===========================================================================
// Pluto TV v17.x (Paramount+/AVIA rebuild) — ad-removal fingerprints.
//
// v17 replaced the leanback stack with Paramount's AVIA player. media3 and okhttp
// are R8-minified, but AVIA's own classes keep their names, so the manifest hook
// anchors on AVIA's readable class + method name. The other seams in
// SkipAdsV17Patch.kt are found by their Kotlin toString() labels.
//
// Issue #300: the earlier hook set (forcing AVIA's DAI/IMA shouldPlayAd to false,
// suppressing the CLID3 CSAI call, the beacon tracker's consumeAds, emptying
// AdsData.adBreaks, zeroing ThumbnailTemplate.adBreaksDurationInSec, the
// CbsPauseWithAdsOverlay render and the getContentDuration fix) was replaced by
// semicoloncolonel's smaller hook set, which keeps Continue Watching working.
// The removed fingerprints and their reasoning are in the git history.
// ===========================================================================

// AviaDashParser.parse(Uri, InputStream): <DashManifest>  [required]
//
// The mid-roll ads are stitched into the multi-period DASH manifest, so the ad
// <Period>s must be removed from the manifest itself. media3's parser is R8-renamed,
// so AVIA's own parse() is hooked and its InputStream swapped for a cleaned one.
//
// R8 also emits a synthetic bridge with the same parameters returning Object; the
// predicate excludes it so the real DASH parser is matched.
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
