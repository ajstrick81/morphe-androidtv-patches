package ajstrick81.morphe.patches.hbomax

import ajstrick81.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Playback Speed  (OPT-IN, default OFF)
//
// Requested in issue #258. Adds a remote-navigable speed control to HBO Max on
// Android TV, so dialogue-heavy shows can be sped up (and slowed back down).
//
// Mechanism (ported from hashtagbasit/aimal-patches' phone "Playback speed"
// patch, which shares Morphe/media3 lineage; re-pointed to the ATV app):
//
//   1. Hand the extension a Context at BeamApplication.onCreate (one
//      instruction) so it can watch the activity view-tree and attach a speed
//      chip only on playback screens.
//   2. Capture every media3 ExoPlayer as it is constructed (one instruction on
//      the constructor's return path). The extension then sets the speed
//      through media3's own API at runtime — nothing per-screen or per-version.
//
// This is a FEATURE ADDITION, not ad removal, so per the project's ownership
// rules it ships OPT-IN and clearly labelled. It touches no DRM, licensing,
// entitlement, ad, timeline or data-source code, and adds no network/telemetry.
//
// Isolation from the stable HBO patches:
//   • Different hook sites. The ad patches hook Bolt*/Ssai*/GenerateLiveTimeline/
//     Nowtilus (ad path), DefaultHttpDataSource.open and PlayableKt.getStreamInfo.
//     None is BeamApplication.onCreate or an ExoPlayer <init>, so no shared
//     method is edited twice.
//   • default = false, so it is absent from the default bundle; a future-build
//     fingerprint drift here cannot affect users who ship the stable set.
//   • Fingerprints are resolved null-safely and a miss throws only THIS patch's
//     PatchException — the per-patch executor isolates that from the ad patches.
//
// Verified to apply against com.wbd.hbomax 7.12.0.68 (single ExoPlayer
// implementor androidx.media3.exoplayer.d, one return-void in <init>).
// NOT YET device-verified — needs an HBO Max subscription to confirm the speed
// actually changes and the chip behaves under a real remote.
// ─────────────────────────────────────────────────────────────────────────────

private const val EXTENSION = "Lajstrick81/morphe/extension/hbomax/speed"
private const val CONTROLLER = "$EXTENSION/HboSpeedController;"
private const val PLAYER_BRIDGE = "$EXTENSION/PlayerBridge;"

@Suppress("unused")
val hboPlaybackSpeedPatch = bytecodePatch(
    name = "HBO Max - Playback Speed",
    description = "Adds a remote-navigable on-screen speed control (0.5x–2.0x) to the HBO Max " +
        "player. Opt-in; touches no DRM, ad, or network code. Not device-verified yet.",
    default = false,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    // Merges the HboSpeedController / PlayerBridge classes into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // 1. Hand the extension a Context.
        //
        // Range form because a large method can push p0 past the 4-bit register
        // limit of a plain invoke-static.
        val contextHook = BeamApplicationOnCreateFingerprint.methodOrNull
            ?: throw PatchException(
                "Could not find com.wbd.beam.BeamApplication.onCreate(). This patch " +
                    "targets HBO Max Android TV (com.wbd.hbomax).",
            )

        contextHook.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, " +
                "$CONTROLLER->setContext(Landroid/content/Context;)V",
        )

        // 2. Capture the ExoPlayer on its constructor's normal exit path.
        //
        // Not index 0: a constructor must reach its super constructor before
        // `this` is usable. Every return-void is hooked (not just the first) so
        // an early-exit path cannot slip through; reversed so earlier indices
        // stay valid as instructions are inserted.
        val constructor = ExoPlayerConstructorFingerprint.methodOrNull
            ?: throw PatchException(
                "No androidx.media3 ExoPlayer implementation found — HBO has " +
                    "probably changed its media stack.",
            )

        val returnIndices = constructor.implementation!!.instructions
            .withIndex()
            .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_VOID }
            .map { (index, _) -> index }

        if (returnIndices.isEmpty()) {
            throw PatchException(
                "ExoPlayer constructor has no return-void to hook, so the speed " +
                    "control would silently do nothing.",
            )
        }

        returnIndices.asReversed().forEach { index ->
            constructor.addInstructions(
                index,
                "invoke-static/range { p0 .. p0 }, " +
                    "$PLAYER_BRIDGE->onPlayerCreated(Ljava/lang/Object;)V",
            )
        }
    }
}
