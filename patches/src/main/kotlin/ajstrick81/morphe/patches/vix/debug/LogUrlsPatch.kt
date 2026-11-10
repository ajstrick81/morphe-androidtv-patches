package ajstrick81.morphe.patches.vix.debug

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.vix.shared.Constants

// Research-only diagnostic. Off by default; nothing leaves the device — it only
// writes to the local logcat (tag VIXURL) so `adb logcat -s VIXURL` shows which
// URLs the player and the Lura SDK request. Used to trace how an ad reaches the
// timeline when the app itself logs nothing about it.
@Suppress("unused")
val logUrlsPatch = bytecodePatch(
    name = "Log player URLs (debug)",
    description = "Research aid, off by default: writes every URL the media player and the " +
        "LuraPlayer SDK open to the device's local logcat (tag VIXURL). Nothing is sent anywhere.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        val log = """
            move-object/from16 v0, p1
            invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v0
            const-string v1, "VIXURL"
            invoke-static {v1, v0}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
        """

        // media3 DataSpec canonical constructor (Uri, position, method, body, headers, ...):
        // every manifest and segment request the player makes passes through it.
        val dataSpecParams = listOf(
            "Landroid/net/Uri;", "J", "I", "[B", "Ljava/util/Map;", "J", "J",
            "Ljava/lang/String;", "I", "Ljava/lang/Object;",
        )
        mutableClassDefByOrNull { classDef ->
            classDef.type.startsWith("Landroidx/media3/datasource/") &&
                classDef.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == dataSpecParams }
        }?.methods
            ?.filter { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == dataSpecParams }
            ?.forEach { it.addInstructions(0, log) }

        // Lura SDK HTTP client request entry: a(url, method, body, headers, int, int, int).
        fun isLuraRequest(params: List<String>, returnType: String) =
            params.size == 7 && params[0] == "Ljava/lang/String;" &&
                params[1].startsWith("Lcom/akta/lura/") && params[2] == "Ljava/lang/String;" &&
                params[3] == "Ljava/util/Map;" && params.drop(4).all { it == "I" } &&
                returnType.startsWith("Lcom/akta/lura/")
        mutableClassDefByOrNull { classDef ->
            classDef.type.startsWith("Lcom/akta/lura/") &&
                classDef.methods.any { isLuraRequest(it.parameterTypes.map(CharSequence::toString), it.returnType) }
        }?.methods
            ?.filter { isLuraRequest(it.parameterTypes.map(CharSequence::toString), it.returnType) }
            ?.forEach { it.addInstructions(0, log) }
    }
}
