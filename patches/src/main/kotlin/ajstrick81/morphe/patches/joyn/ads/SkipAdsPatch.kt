package ajstrick81.morphe.patches.joyn.ads

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.joyn.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Removes ads before and during videos in the Joyn Android TV app. It turns off " +
        "the app's own \"ads enabled\" flag, so the player is built without Joyn's Ad SDK. " +
        "Joyn is geo-restricted, so playback still needs Germany, Austria or Switzerland.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        val logLambdaType = AdsDisabledLogFingerprint.method.definingClass
        val setup = adSdkSetupFingerprint(logLambdaType).method
        val insns = setup.implementation!!.instructions.toList()

        val firstLambda = insns.newInstanceIndexOf(logLambdaType)
        val boolIndex = (firstLambda downTo 0).firstOrNull { i ->
            val insn = insns[i]
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                (insn as ReferenceInstruction).reference.toString() ==
                "Ljava/lang/Boolean;->booleanValue()Z" &&
                insns.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT
        } ?: throw PatchException("Joyn: ads-enabled Boolean not found in ${setup.definingClass}->${setup.name}")

        val register = (insns[boolIndex + 1] as OneRegisterInstruction).registerA
        // move-result vX -> const/4 vX, 0: the if-eqz that follows now always
        // takes the app's own "Ads are disabled" branch.
        val const = if (register < 16) "const/4" else "const/16"
        setup.replaceInstruction(boolIndex + 1, "$const v$register, 0x0")
    }
}
