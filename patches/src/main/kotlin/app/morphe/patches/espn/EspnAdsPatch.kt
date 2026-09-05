package app.morphe.patches.espn

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// ─────────────────────────────────────────────────────────────────────────────
// ESPN Android TV — mask live commercial breaks + suppress VOD ads.
//
// TWO layers (see docs/LIVE_SPORTS_AD_SLATE_PLAYBOOK.md for the full method):
//
// 1) VOD / scheduled-interstitial suppression — force
//    com.disney.dmp.sgai.InterstitialManager.isAdDisabled = true at construction.
//    That single boolean gates all six ad-scheduling sites (see Fingerprints.kt),
//    so scheduled interstitials / DATERANGE VOD mid-rolls aren't inserted.
//
// 2) LIVE commercial-break SLATE — ESPN live ads are native passthrough SSAI
//    (mel::break_session content_type: PassthroughAds), stitched into the main
//    stream; isAdDisabled is inert against them and NO Kotlin break event fires
//    (device-proven). So instead of removing the ad (there's nothing underneath),
//    we MASK it: detect the break window and cover the player with a full-screen
//    slate + mute. The window comes from the manifest DateRanges delivered to
//    SgaiPlaybackSession.playlistRetrieved (com.disney.media.break.v1, absolute
//    dates); we compare the live playhead (TimelineProgressEvent +
//    zeroPositionProgramDateTime) against accumulated, id-keyed windows, and
//    mute via the player's own AudioControl. Slate media is user-supplied local
//    files (looping video-with-audio or image) in the app's external files dir,
//    round-robined per break, with an ESPN-logo fallback card — NOTHING is bundled.
//
// Opt-out: drop an empty file named `slate_off` in the app's external files dir
// to disable the live slate (VOD suppression stays on). See EspnAdBreakOverlayHelper.
//
// Verified on Onn 4K, ESPN 6.11.1 (2026-09-05): 4/4 live breaks masked with correct
// video rotation, clean lifts, player muted, zero errors over ~47 min.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val espnAdsPatch = bytecodePatch(
    name = "ESPN Android TV",
    description = "Suppresses ESPN Android TV VOD/scheduled ads (DMP SGAI isAdDisabled) and masks LIVE " +
        "passthrough-SSAI commercial breaks with a full-screen \"Commercial Break\" slate + mute " +
        "(the live ad can't be removed, only covered). Optional slate media is user-supplied local " +
        "files; disable the live slate with a `slate_off` marker file. No DNS dependency.",
) {
    compatibleWith(AppCompatibilities.ESPN_TV)
    extendWith("extensions/extension.mpe")

    execute {
        // Locate the `iput-boolean pX, ...->isAdDisabled:Z` in the constructor and
        // read its SOURCE register (registerA of the two-register iput). Injecting
        // `const/16 <thatReg>, 0x1` immediately before the iput overwrites whatever
        // the constructor was about to store with `true`, regardless of how the
        // param register was allocated — robust across recompiles.
        // NOTE: const/16 (8-bit register field), not const/4 — isAdDisabled is a
        // high param register (p6 in a .locals 24 ctor, well above v15).
        val method = InterstitialManagerInitFingerprint.method
        val instructions = method.implementation!!.instructions.toList()

        val iputIndex = instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.IPUT_BOOLEAN &&
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.name == "isAdDisabled"
        }
        require(iputIndex >= 0) { "ESPN: isAdDisabled iput-boolean not found in InterstitialManager.<init>" }

        val valueRegister = (instructions[iputIndex] as TwoRegisterInstruction).registerA

        method.addInstructions(
            iputIndex,
            "const/16 v$valueRegister, 0x1",
        )

        // ─────────────────────────────────────────────────────────────────────
        // LIVE SLATE OVERLAY (the passthrough-SSAI mask).
        //
        // isAdDisabled above kills SCHEDULED interstitials / DATERANGE ads (VOD),
        // but ESPN live ads are native passthrough SSAI played straight from the
        // main manifest — isAdDisabled is inert against them (proven on-device).
        // Since there is no content under a live national commercial, the accepted
        // ceiling is to COVER the ad with an ESPN-style "COMMERCIAL BREAK / WE'LL
        // BE RIGHT BACK" slate and mute audio.
        //
        // Live passthrough breaks emit NO break/interstitial event (proven on-
        // device 2026-09-03) — the only truth is "playhead inside a break window",
        // so the helper computes containment from two feeds (see below).
        // All-bytecode; no native/segment work.
        // ─────────────────────────────────────────────────────────────────────

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
