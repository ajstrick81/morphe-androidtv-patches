/*
 * Hook set ported from semicoloncolonel's Pluto TV "Skip ads" patch
 * (tv-morphe-patches, GPL-3.0):
 * https://github.com/semicoloncolonel/tv-morphe-patches/tree/main/patches/src/main/kotlin/semicoloncolonel/mobilestream/patches/pluto/ads
 * Author: semicoloncolonel (github.com/semicoloncolonel).
 *
 * semicoloncolonel's patch is itself a port of this repo's Pluto work, and their
 * build kept Pluto's Continue Watching row working where ours did not (issue #300).
 * This file now applies the same three hooks theirs does, and nothing more.
 */

package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import ajstrick81.morphe.patches.shared.ads.ImaPauseAdDisplayFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val MPD_FILTER = "Lajstrick81/morphe/extension/pluto/ads/PlutoV17MpdFilter;"

@Suppress("unused")
val skipAdsV17Patch = bytecodePatch(
    name = "Skip ads (v17)",
    description = "Removes the ads, ad markers, and pause ads from on-demand videos on " +
        "Pluto TV v17. Live TV ads are part of the broadcast and are not removed. " +
        "Hook design ported from semicoloncolonel's tv-morphe-patches.",
) {
    compatibleWith(Constants.COMPATIBILITY_V17)

    // Merges PlutoV17MpdFilter into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {

        // Hook 1 — the ad video. Route the raw MPD through PlutoV17MpdFilter before
        // AVIA parses it. Ad periods are dropped and the rest re-based contiguous.
        //
        // parse() is register-heavy (.locals 23 in 17.2.0), so p2 (the InputStream) sits
        // above v15. Emit the range form against p2's numeric register: with this
        // patcher, a non-range invoke on a high register is silently dropped and leaves
        // an orphan move-result-object -> VerifyError (device-confirmed on 17.2.0).
        AviaDashParserParseFingerprint.method.apply {
            // this, Uri, InputStream -> p2 is the last parameter register.
            val p2reg = implementation!!.registerCount - 1
            addInstructions(
                0,
                """
                    invoke-static/range {v$p2reg .. v$p2reg}, $MPD_FILTER->filter(Ljava/io/InputStream;)Ljava/io/InputStream;
                    move-result-object v$p2reg
                """,
            )
        }

        // Replaces a List field with a fresh empty ArrayList as each constructor stores
        // it (semicoloncolonel's technique). The class is found by its toString() label,
        // since R8 renames the class but not the string. The field is the first
        // iget-object after the label in toString(). The empty list is written into the
        // register the constructor was about to store, right before the store, so
        // nothing else in the constructor changes.
        fun emptyListFieldOnConstruct(classLabel: String, fieldLabel: String) {
            val classDef = mutableClassDefByOrNull { it.hasStringConstant(classLabel) }
            checkNotNull(classDef) { "skipAdsV17Patch: no class with \"$classLabel\"" }

            val toString = classDef.methods.first { method ->
                method.name == "toString" &&
                    method.implementation?.instructions?.any { it.stringConstant() == fieldLabel } == true
            }
            val toStringInstructions = toString.implementation!!.instructions.toList()
            val labelIndex = toStringInstructions.indexOfFirst { it.stringConstant() == fieldLabel }
            val field = toStringInstructions.drop(labelIndex + 1)
                .first { it.opcode == Opcode.IGET_OBJECT }
                .let { (it as ReferenceInstruction).reference as FieldReference }

            var patched = 0
            classDef.methods.filter { it.name == "<init>" }.forEach { constructor ->
                val instructions = constructor.implementation?.instructions?.toList() ?: return@forEach
                // Highest index first, so earlier indices stay valid after inserting.
                instructions.indices.reversed().forEach { index ->
                    val instruction = instructions[index]
                    if (instruction.opcode != Opcode.IPUT_OBJECT) return@forEach
                    val reference = (instruction as ReferenceInstruction).reference as FieldReference
                    if (reference.name != field.name || reference.definingClass != field.definingClass) {
                        return@forEach
                    }
                    val register = (instruction as TwoRegisterInstruction).registerA
                    constructor.addInstructions(
                        index,
                        """
                            new-instance v$register, Ljava/util/ArrayList;
                            invoke-direct/range {v$register .. v$register}, Ljava/util/ArrayList;-><init>()V
                        """,
                    )
                    patched++
                }
            }
            check(patched > 0) {
                "skipAdsV17Patch: no constructor stores ${field.definingClass}->${field.name}"
            }
        }

        // Hook 2 — the ad-break timeline. StitcherSession.adBreaks empty means the
        // timebar ad markers, the ad-beacon controllers and the player's ad-break
        // handling all see no ads. Pluto converts a Continue Watching position into the
        // stitched timeline by adding these breaks, so with the list empty the saved
        // and restored positions already match the stripped timeline: no resume remap.
        emptyListFieldOnConstruct("StitcherSession(id=", ", adBreaks=")

        // Hook 3 — pause ads: the pause-ads response carries no ads, and IMA's own
        // pause-ad display handler never shows one.
        emptyListFieldOnConstruct("PauseAdsResponse(ads=", "PauseAdsResponse(ads=")
        ImaPauseAdDisplayFingerprint.methodOrNull?.addInstructions(0, "return-void")
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.stringConstant(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** True if any method of this class loads [text] as a string constant. */
private fun ClassDef.hasStringConstant(text: String): Boolean = methods.any { method ->
    method.implementation?.instructions?.any { it.stringConstant() == text } == true
}
