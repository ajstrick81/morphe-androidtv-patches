package ajstrick81.morphe.patches.bbciplayer.compatibility

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.FormFactor
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderArrayPayload
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

private val compatibility = Compatibility(
    name = "BBC iPlayer (Android TV)",
    packageName = "bbc.iplayer.android",
    targets = listOf(AppTarget("0.4.7-dev", 47, minSdk = 26)),
    formFactor = FormFactor.TV,
)

private const val ORIGINAL_USER_AGENT =
    "Mozilla/5.0 (Linux; Android %s; %s Build/%s; wv-atv) Google/%s " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Mobile Safari/537.36"

private const val DEFAULT_DEVICE_IDENTIFIER = "P1"
private val deviceIdentifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

@Suppress("unused")
val bbcIPlayerTvCompatibilityPatch = bytecodePatch(
    name = "Change the TV model iPlayer sees",
    description = "Fixes the ‘device isn’t compatible’ message. " +
        "For UK residents using iPlayer in the UK.",
) {
    compatibleWith(compatibility)

    val deviceIdentifier by stringOption(
        key = "deviceIdentifier",
        default = DEFAULT_DEVICE_IDENTIFIER,
        values = mapOf("P1 - Chromecast with Google TV (4K, 2020)" to DEFAULT_DEVICE_IDENTIFIER),
        title = "TV model",
        description = "Keep the recommended model (P1). To try another model, type a model code in the field. " +
            "See the patch README for codes.",
        required = true,
        validator = { value -> value != null && deviceIdentifierPattern.matches(value) },
    )

    execute {
        val model = checkNotNull(deviceIdentifier)
        val userAgent = "Mozilla/5.0 (Linux; Android 9.0.0; $model Build/PQ3B.190801.002; wv-atv; mse) " +
            "Google/1.0.0 AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/43.0.2357.65 Mobile Safari/537.36"
        val activity = mutableClassDefBy("Lexternal/androidtv/bbciplayer/MainActivity;")
        val body = activity.methods.single { it.name == "onCreate" }.implementation!!
        val matching = body.instructions.withIndex().filter { (_, instruction) ->
            val value = (instruction as? ReferenceInstruction)?.reference as? StringReference
            value?.string == ORIGINAL_USER_AGENT
        }.single()
        val register = (matching.value as OneRegisterInstruction).registerA
        body.replaceInstruction(matching.index, BuilderInstruction31c(
            Opcode.CONST_STRING_JUMBO, register, ImmutableStringReference(userAgent),
        ))
    }
}

@Suppress("unused")
val bbcIPlayerWebViewPlaybackPatch = bytecodePatch(
    name = "Fix iPlayer playback",
    description = "Fixes the loading spinner when you try to watch something. " +
        "Geographic checks and DRM still apply to both patches.",
) {
    compatibleWith(compatibility)
    dependsOn(bbcIPlayerTvCompatibilityPatch)

    execute {
        val client = mutableClassDefBy("Lexternal/androidtv/bbciplayer/web/iPlayerWebViewClient;")
        val factory = client.methods.single { it.name == "lambda\$new\$1" }
        val body = factory.implementation!!
        val instructions = body.instructions
        val lengthIndex = 2
        val length = instructions[lengthIndex]
        val payload = instructions.last() as? BuilderArrayPayload
        check(length.opcode == Opcode.CONST_4 && (length as NarrowLiteralInstruction).narrowLiteral == 3 &&
            payload?.elementWidth == 4 && payload.arrayElements == listOf(0x7f0e0003, 0x7f0e0001, 0x7f0e0002)
        ) { "The legacy iPlayer WebView script list did not match" }

        // Keep the BBC and ExoPlayer event bridges. Exclude raw/htmlvideo, which overrides native video APIs.
        val register = (length as OneRegisterInstruction).registerA
        body.replaceInstruction(lengthIndex, BuilderInstruction11n(Opcode.CONST_4, register, 2))
        body.replaceInstruction(instructions.lastIndex, BuilderArrayPayload(4, listOf(0x7f0e0003, 0x7f0e0001)))
    }
}
