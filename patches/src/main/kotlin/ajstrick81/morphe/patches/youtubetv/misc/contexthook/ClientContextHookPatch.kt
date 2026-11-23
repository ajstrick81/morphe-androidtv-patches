package ajstrick81.morphe.patches.youtubetv.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

// Leyn.c(Context):Z — checks PackageManager for "android.hardware.type.automotive".
// Returns true if the device is Android Automotive. Making it always return true
// spoofs the OS name to "Android Automotive" in Lffr.<init>, suppressing YouTube ads.
private val automotiveCheckFingerprint = Fingerprint(
    strings = listOf("android.hardware.type.automotive"),
    returnType = "Z",
    custom = { method, _ ->
        method.name == "c" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Landroid/content/Context;"
    },
)

@Suppress("unused")
val clientContextHookPatch = bytecodePatch(
    name = "Client context hook",
    description = "Spoofs the OS name to Android Automotive to suppress video ads.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        automotiveCheckFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}
