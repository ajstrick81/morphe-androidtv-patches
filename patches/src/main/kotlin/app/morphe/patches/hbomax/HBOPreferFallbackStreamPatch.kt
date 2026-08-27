package app.morphe.patches.hbomax.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Prefer Ad-Free Stream  (DEFAULT ON — field-verified 7.9.0.61)
//
// The clean SSAI fix, using HBO's OWN ad-free manifest.
//
// An ad-supported HBO Playable carries TWO streams (StreamInfo): a PRIMARY stream
// whose DASH manifest is ad-stitched (…/dash.mpd) and a FALLBACK stream whose
// manifest is the clean, non-stitched variant (…/<hash>_fallback.mpd on the
// alternate CDN). Captured on-device (7.9.0.61):
//   PRIMARY : https://<token>.cf.prd.media.h264.io/gcs/<uuid>/dash.mpd
//   FALLBACK: https://akm.prd.media.h264.io/gcs/<uuid>/<hash>_fallback.mpd
// HBO only switches to FALLBACK through its resiliency recovery after a load
// failure — which is why the "Block SSAI Ad Origins" approach reaches it only on a
// fresh start and fatals (39999) on a resumed mid-roll.
//
// PlayableKt.getStreamInfo(Playable, StreamInfo$Type) resolves a stream by type.
// This patch injects HboStreamSelector.preferFallbackType() at its entry to remap a
// PRIMARY request to FALLBACK — but ONLY when the Playable actually advertises a
// FALLBACK stream (reflection-guarded, fail-open), so titles without one are left
// untouched. The player then loads the ad-free manifest from the very first
// request, on fresh start AND resume: no ad periods exist, so no ads, no SSAI
// markers/countdown, no seekbar lock, no timeline gaps, and no post-start -free
// failure to fatal on. This is HBO's own sanctioned ad-free stream, so playback
// machinery stays consistent.
//
// Field-verified on 7.9.0.61 (Onn 4K): 20-min Health Monitor run with 4
// resume→seek stress cycles — zero ad leaks, no 39999, no stalls, faster load;
// the remap fires on every resume. Default ON — this is the clean fix for the
// returning-ads / resume-39999 reports (#125) and supersedes the downstream
// approaches (Block SSAI Ad Origins fatal-on-resume).
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val hboPreferFallbackStreamPatch = bytecodePatch(
    name = "HBO Max - Prefer Ad-Free Stream",
    description = "Loads HBO's own ad-free FALLBACK manifest instead of the ad-stitched " +
        "PRIMARY one by remapping the stream selection in getStreamInfo (only when a " +
        "FALLBACK stream exists). Ad-free on fresh start and resume with no markers, no " +
        "timeline gaps, and no 'Couldn't Play Content' (39999) error — and loads faster " +
        "since no ads are stitched. On by default; field-verified on 7.9.0.61.",
    default = true,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    // Merges HboStreamSelector into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // getStreamInfo is static: p0=Playable, p1=StreamInfo$Type. Remap p1 at
        // entry so a PRIMARY lookup resolves the FALLBACK (ad-free) stream when one
        // is present; the returned type is cast back to StreamInfo$Type.
        GetStreamInfoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, Lajstrick81/morphe/extension/hbomax/ads/HboStreamSelector;->preferFallbackType(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, Lcom/discovery/player/common/models/StreamInfo${'$'}Type;
            """.trimIndent(),
        )
    }
}
