package ajstrick81.morphe.patches.joyn.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// ─────────────────────────────────────────────────────────────────────────────
// Joyn Android TV (de.prosiebensat1digital.seventv, 6.5.2-ATV) — ad seam.
//
// Joyn's ads come from ProSiebenSat.1's own "Ads Wrapper" SDK: a JS bundle in
// assets driven over a JS bridge (AdSdkSender/AdSdkReceiver), not Google IMA.
// While building the player, the app reads an "ads enabled" Boolean and either
// attaches the Ad SDK instance to the player or logs
//   "Ads are disabled; skipping Ad SDK setup on the player"
// and carries on without it. That second branch is the app's own ad-free path.
//
// This is the same seam hxreborn's phone patch ("Hide ads") targets. The phone
// patch finds the log lambda, strips its `$` suffix to get the player factory
// class, then forces the Boolean false. On the TV build R8 merged the three log
// lambdas into one top-level switch class (6.5.2: Lr30/y;, invoke() switches on
// an int field) and inlined the factory into its caller (6.5.2: Lv30/e;->c()).
// There is no `$`, so the phone patch looks for "Lr30/y;;" and fails (#328).
//
// So we anchor on references instead of names:
//   1. the class holding the "Ads are disabled…" string = the log lambda type;
//   2. the method that does `new-instance <lambda>` AND calls
//      Boolean.booleanValue() = the player setup method;
//   3. inside it, the last booleanValue()+move-result before the first
//      `new-instance <lambda>` is the ads-enabled flag. Force it to 0.
// ─────────────────────────────────────────────────────────────────────────────

internal object AdsDisabledLogFingerprint : Fingerprint(
    strings = listOf("Ads are disabled; skipping Ad SDK setup on the player"),
)

internal fun Iterable<com.android.tools.smali.dexlib2.iface.instruction.Instruction>.newInstanceIndexOf(
    type: String,
): Int = indexOfFirst {
    it.opcode == Opcode.NEW_INSTANCE &&
        ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == type
}

internal fun adSdkSetupFingerprint(logLambdaType: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type != logLambdaType &&
            method.implementation?.instructions?.let { insns ->
                insns.newInstanceIndexOf(logLambdaType) >= 0 &&
                    insns.any {
                        it.opcode == Opcode.INVOKE_VIRTUAL &&
                            (it as ReferenceInstruction).reference.toString() ==
                            "Ljava/lang/Boolean;->booleanValue()Z"
                    }
            } == true
    },
)
