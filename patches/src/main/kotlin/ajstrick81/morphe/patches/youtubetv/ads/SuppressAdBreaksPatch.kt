package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val suppressAdBreaksPatch = bytecodePatch(
    name = "Suppress ad breaks",
    description = "EXPERIMENTAL / UNVALIDATED — pending on-device test on the Onn (armeabi-v7a). " +
        "Drives YouTube TV into its OWN sanctioned \"DAI disabled by no config\" native state at " +
        "provider creation, rather than fighting the ad pipeline. YouTube TV uses Google " +
        "Server-Stitched DAI whose ad-break scheduling is owned by the native player core " +
        "(libgoogle3.so, CuePointDataProviderImpl); the core exposes " +
        "nativeSetDaiDisabledByNoConfig(nativePtr) which sets a single native flag (obj+0x71) that " +
        "turns DAI off. The app normally calls this only when server hotconfig 45731777 is enabled; " +
        "this patch calls it unconditionally in CuePointDataProviderWrapper.<init>, right after " +
        "createNative sets nativePtr and before any cuepoint can arrive. Chosen over dropping " +
        "cuepoints because the native core ships SERVER-DRIVEN ad-blocker enforcement " +
        "(FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT via StreamProtectionStatus + PoToken): the " +
        "sanctioned \"no config\" state is a legitimate state the server already models, so it is " +
        "the suppression least likely to trip the enforcement wall. Whether it (a) fully stops " +
        "stitched breaks and (b) avoids enforcement is UNKNOWN until validated on-device against a " +
        "live break. See analysis/youtubetv/notes/native-core.md.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Hook — CuePointDataProviderWrapper.<init>(Executor, Consumer)
        //
        // Layout of the constructor (verified in 10.33.2 smali):
        //   invoke-static {..}, ..->createNative(..)J
        //   move-result-wide vVal
        //   iput-wide       vVal, vThis, ..->nativePtr:J      <-- inject AFTER this
        //   iput-object     p1,   vThis, ..->b:..Executor;
        //   ...
        //
        // We locate the `iput-wide` that writes the `nativePtr` field and, right
        // after it, emit the wrapper's own disable call reusing the still-live
        // registers (vThis = object, vVal:vVal+1 = the wide nativePtr):
        //   invoke-virtual {vThis, vVal, vValHi}, ..->nativeSetDaiDisabledByNoConfig(J)V
        //
        // Reusing the iput-wide's own registers means no new registers are
        // needed (verifier-safe) and the exact value just stored is passed
        // straight into the native call.
        CuePointDataProviderWrapperInitFingerprint.method.apply {
            val insns = implementation!!.instructions.toList()
            val putIndex = insns.indexOfFirst { insn ->
                insn.opcode == Opcode.IPUT_WIDE &&
                    ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.name == "nativePtr"
            }
            check(putIndex >= 0) {
                "suppressAdBreaksPatch: nativePtr iput-wide not found in CuePointDataProviderWrapper.<init>"
            }
            val put = insns[putIndex] as TwoRegisterInstruction
            val valueReg = put.registerA   // low half of the wide nativePtr
            val thisReg = put.registerB    // the wrapper instance
            addInstructions(
                putIndex + 1,
                "invoke-virtual { v$thisReg, v$valueReg, v${valueReg + 1} }, " +
                    "Lcom/google/android/libraries/youtube/media/interfaces/CuePointDataProviderWrapper;" +
                    "->nativeSetDaiDisabledByNoConfig(J)V",
            )
        }
    }
}
