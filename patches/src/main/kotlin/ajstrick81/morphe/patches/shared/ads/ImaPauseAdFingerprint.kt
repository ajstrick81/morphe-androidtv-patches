package ajstrick81.morphe.patches.shared.ads

import app.morphe.patcher.Fingerprint

// ===========================================================================
// Google IMA pause-ad display handler — shared across IMA apps.
//
// Credit: anchor found by semicoloncolonel (mobile-morphe-patches, GPL-3.0),
// https://github.com/semicoloncolonel/mobile-morphe-patches
//
// The method that receives IMA's "displayPauseAd" message and puts the creative
// in the pause-ad slot (com.google.ads.interactivemedia.v3.impl.zzda.zza in Pluto
// TV 17.2.0). IMA renames its internals per SDK version, so the anchor is the
// handler's own error message, not the class or method name. Returning at entry
// means the pause ad is never displayed and its display impression never sent.
//
// Apps on an IMA build without pause ads simply won't match, so callers apply
// it with methodOrNull.
// ===========================================================================
internal object ImaPauseAdDisplayFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/google/ads/interactivemedia/v3/impl/data/JavaScriptMsgData;"),
    strings = listOf("No pause ad slot in display container."),
)
