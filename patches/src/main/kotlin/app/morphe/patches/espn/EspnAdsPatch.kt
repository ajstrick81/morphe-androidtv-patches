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
    description = "Suppresses ESPN Android TV VOD/scheduled ads by forcing DMP SGAI isAdDisabled = " +
        "true (that one boolean gates all six ad-scheduling sites). Live passthrough-SSAI " +
        "commercial breaks can't be removed — to MASK them with a slate + mute, also select the " +
        "\"ESPN live commercial-break slate\" patch. No DNS dependency.",
) {
    compatibleWith(AppCompatibilities.ESPN_TV)

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
    }
}
