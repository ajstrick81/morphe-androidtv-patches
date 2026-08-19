package ajstrick81.morphe.patches.youtubetv.misc.contexthook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

// Approach originally demonstrated for 7.05.301 in the community fork
// andersonlucasg3/morphe-androidtv-patches; re-verified and retargeted here for
// 7.11.300 (class Leyn → Lfam, builder Lffr → Lfht). See Fingerprints.kt for the
// version-resilient anchoring.
@Suppress("unused")
val clientContextHookPatch = bytecodePatch(
    name = "Client context hook",
    description = "Spoofs the InnerTube OS name to Android Automotive so YouTube " +
        "does not serve video ads.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Force the automotive-feature check to return true. The result is
        // cached in a Boolean field and read by the client-context builder,
        // which then reports OS = "Android Automotive" → no video ads.
        //
        // Injecting `const/4 v0, 0x1 / return v0` at index 0 short-circuits the
        // method before its PackageManager lookup. This is an ordinary static
        // method (not a constructor), so an index-0 early return is verifier-safe.
        automotiveCheckFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}
