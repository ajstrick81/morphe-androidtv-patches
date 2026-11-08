package ajstrick81.morphe.patches.future.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.future.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val disableUpdatePromptPatch = bytecodePatch(
    name = "Disable update prompt",
    description = "Stops Fawesome Movies & TV (Android TV) from showing the \"New version " +
        "Available\" dialog on every launch. The app reads the server's latest version code " +
        "(Firebase remote-config key \"versioncode_androidtv_googletv\") and, when it is higher " +
        "than the installed build, shows an update dialog before continuing into the app. This " +
        "patch forces the app's own no-update path instead, so startup is unchanged and the nag " +
        "is gone — nothing is added, the client just stops being told to update. On 19.7 the seam " +
        "is SplashActivity.checkCurrentAppVersion() -> Utilities.updateAlert(); on 20.1 (R8-" +
        "obfuscated, ViewModel rewrite) it is the splash view-model's version-check method, forced " +
        "down its own proceed branch; on 20.8 (the FilmRise MVVM rebase, which switched to Google " +
        "Play In-App Updates) it is the remote-config \"enableForceUpdate\" gate the Play Core " +
        "listener reads, forced off. Whichever matches the installed version is applied.",
) {
    compatibleWith(Constants.UPDATE_COMPATIBILITY)

    execute {
        // Anchor on the single update-dialog call site inside checkCurrentAppVersion()
        // and inject the app's own "continue into the app" sequence immediately before
        // it, then return — so Utilities.updateAlert() is never reached. callRootdata()
        // is a private instance method of SplashActivity (p0 == this), matching the
        // Cancel handler's access$200 -> callRootdata() flow exactly.
        //
        // methodOrNull so a future re-anchor miss degrades gracefully (startup keeps the
        // stock nag) instead of aborting the whole patch run.
        CheckCurrentAppVersionFingerprint.methodOrNull?.apply {
            val updateAlertIndex = implementation!!.instructions.toList().indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    (it as? ReferenceInstruction)?.reference?.toString()
                        ?.let { ref ->
                            ref.startsWith("Lcom/future/util/Utilities;->updateAlert(")
                        } == true
            }
            check(updateAlertIndex >= 0) {
                "disableUpdatePromptPatch: no Utilities.updateAlert call in checkCurrentAppVersion()"
            }

            addInstructions(
                updateAlertIndex,
                """
                    invoke-direct {p0}, Lcom/future/moviesByFawesomeAndroidTV/SplashActivity;->callRootdata()V
                    return-void
                """,
            )
        }

        // ── 20.1 seam ────────────────────────────────────────────────────────────
        // The version-check moved into the splash view-model's e(int versionCode, String).
        // Its first branch is `if (versionCode <= 0) -> proceed`; only `versionCode > 193`
        // reaches the update-dialog state. Zeroing the versionCode parameter at entry forces
        // the method down its own proceed branch (emitting the app's stock no-update state),
        // so the dialog state is never produced. One instruction, no flow change otherwise.
        //
        // p1 is the int versionCode parameter of this instance method. methodOrNull so a
        // version that lacks this seam (e.g. 19.7) degrades gracefully.
        SplashUpdateStateMethodFingerprint.methodOrNull?.addInstructions(
            0,
            "const/4 p1, 0x0",
        )

        // ── 20.8 seam ────────────────────────────────────────────────────────────
        // 20.8 drives the prompt through Google Play In-App Updates instead of its
        // own dialog, gated on a single SplashActivity boolean that the remote
        // config's "enableForceUpdate" sets for the installed version name. Force
        // that field's write to false so the Play Core success listener takes its
        // own proceed branch — the stock behaviour when the backend has force
        // update off.
        //
        // Patch the store, not the field: zero the register the iput-boolean is
        // about to write. That keeps the config parse, the HashMap and the Play
        // Core availability calls intact, so nothing is faked and no other
        // consumer of the parsed config is disturbed.
        SplashForceUpdateConfigFingerprint.methodOrNull?.apply {
            val instructions = implementation!!.instructions.toList()

            // The only iput-boolean in this method that targets a field of the
            // SplashActivity itself is the gate write. Field letters are
            // R8-assigned, so match on the owner and type rather than the name.
            val gateStoreIndex = instructions.indexOfFirst {
                it.opcode == Opcode.IPUT_BOOLEAN &&
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { field ->
                        field.definingClass.endsWith("/SplashActivity;") && field.type == "Z"
                    } == true
            }
            check(gateStoreIndex >= 0) {
                "disableUpdatePromptPatch (20.8): no SplashActivity boolean store in the " +
                    "force-update config parser"
            }

            // iput-boolean is format 22c, whose value register is 4-bit, so the
            // source is always v0..v15 and const/4 can always encode it.
            val valueRegister =
                (instructions[gateStoreIndex] as TwoRegisterInstruction).registerA

            addInstructions(gateStoreIndex, "const/4 v$valueRegister, 0x0")
        }
    }
}
