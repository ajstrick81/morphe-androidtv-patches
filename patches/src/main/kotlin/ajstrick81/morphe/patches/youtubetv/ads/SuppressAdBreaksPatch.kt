package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

@Suppress("unused")
val suppressAdBreaksPatch = bytecodePatch(
    name = "Suppress ad breaks",
    description = "EXPERIMENTAL / UNVALIDATED. Empties YouTube TV's cuepoint ad-break list at the " +
        "single JNI->Java choke point (CuePointDataProviderWrapper\$NativeCallback.onCuepointList) " +
        "so no cuepoint-driven breaks are dispatched to the player's ad scheduler. Because YouTube " +
        "TV uses Google Server-Stitched DAI (ads baked into the content segments), it is NOT yet " +
        "confirmed on-device whether the native player core schedules breaks independently of this " +
        "callback; if it does, the stitched ad video may still play and this hook only removes the " +
        "client-side scheduling/markers. When effective, it drops ALL breaks indiscriminately — " +
        "including gambling/adult ads and the 'Enjoy the zen' unsold-break filler — but it cannot " +
        "selectively filter ad categories (that is decided server-side in DAI). Validate against a " +
        "live break before relying on it. See analysis/youtubetv/notes/recon.md.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Hook — onCuepointList([B)V -> return-void
        //
        // The method is `.registers 7` and returns void, so an unconditional
        // return-void at offset 0 needs no live registers and is always
        // verifier-safe. It skips proto parsing, the CuepointContext iteration,
        // and every amxc/apow dispatch to the ad-break Consumer.
        OnCuepointListFingerprint.method.addInstructions(0, "return-void")
    }
}
