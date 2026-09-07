package app.morphe.patches.espn

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// ESPN live commercial-break SLATE (the passthrough-SSAI mask).
//
// The core "ESPN Android TV" patch forces DMP SGAI isAdDisabled = true, which
// kills SCHEDULED interstitials / DATERANGE ads (VOD). But ESPN LIVE ads are
// native passthrough SSAI played straight from the main manifest — isAdDisabled
// is inert against them (proven on-device) and NO Kotlin break event fires.
// Since there's no content under a live national commercial, the accepted
// ceiling is to MASK it: detect the break window and cover the player with a
// full-screen slate (video / image / animated "Be Right Back" overlay) + mute.
//
// This is a SEPARATE, deselectable patch so users who only want the VOD ad
// suppression (no on-screen slate during live breaks) can leave it off. It
// depends on the core ESPN patch (which provides the DMP suppression) and bundles
// the extension that the slate hooks call into.
//
// Window truth comes from two feeds (playhead + manifest DateRanges); the helper
// (EspnAdBreakOverlayHelper) computes containment and drives the overlay + mute.
// Slate media is user-supplied local files in the app's external files dir —
// NOTHING is bundled. Runtime opt-out: a `slate_off` marker disables it without
// re-patching; `slate_mode` picks the layout; the animated overlay lives under
// files/espn_overlay/. See slates/espn_overlay/README.md.
//
// Verified on Onn 4K, ESPN 6.11.1: live breaks masked with correct rotation,
// clean lifts, player muted.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val espnLiveSlatePatch = bytecodePatch(
    name = "ESPN live commercial-break slate",
    description = "Masks LIVE ESPN commercial breaks (which are passthrough SSAI and can't be " +
        "removed) with a full-screen slate + mute: your own video/image clips, or the animated " +
        "\"Be Right Back\" overlay with a live countdown. Includes an on-screen D-pad picker to " +
        "switch styles. Leave OFF if you only want the VOD ad suppression and no on-screen slate " +
        "during live breaks. Runtime opt-out: a `slate_off` marker file.",
) {
    compatibleWith(AppCompatibilities.ESPN_TV)
    // The core patch provides the DMP isAdDisabled suppression; run after it.
    dependsOn(espnAdsPatch)
    // Bundle the extension the slate hooks call into.
    extendWith("extensions/extension.mpe")

    execute {
        // 1) Register / unregister the slate's host container (PlayerActivity's
        //    android.R.id.content root) around the visible lifecycle.
        PlayerActivityOnResumeFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;" +
                "->registerActivity(Landroid/app/Activity;)V",
        )
        PlayerActivityOnPauseFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;" +
                "->unregisterActivity(Landroid/app/Activity;)V",
        )

        // 2) Feed A — playhead position. sessionListener.onEvent(event) receives
        //    every PlaybackSessionEvent; the helper reads the playhead out of
        //    TimelineProgressEvent and re-evaluates window containment.
        SessionListenerOnEventFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;" +
                "->onPlayerEvent(Ljava/lang/Object;)V",
        )

        // 3) Feed B1 — capture the DMP session so the helper can poll
        //    session.getBreaks() (works on streams that surface interstitial
        //    breaks, e.g. with the on-screen countdown). p1 = the session.
        AttachSessionFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;" +
                "->setSession(Ljava/lang/Object;)V",
        )

        // 4) Feed B2 — manifest DateRanges. On pure-passthrough streams getBreaks()
        //    is empty, but SgaiPlaybackSession.playlistRetrieved(DateTime, List<DateRange>)
        //    always carries the ad windows (absolute dates). p2 = the DateRange list.
        PlaylistRetrievedFingerprint.method.addInstructions(
            0,
            "invoke-static { p2 }, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;" +
                "->onDateRanges(Ljava/lang/Object;)V",
        )
    }
}
