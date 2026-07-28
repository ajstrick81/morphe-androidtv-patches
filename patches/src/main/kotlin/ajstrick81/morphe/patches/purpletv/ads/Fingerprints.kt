package ajstrick81.morphe.patches.purpletv.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// ── Layer 1 ──────────────────────────────────────────────────────────────────
// Target: PlaybackAccessTokenParams.<init>(String playerType, Optional hasAdblock
// or maid, int defaultsMask) — the data class the app builds right before
// requesting a stream's playback access token (StreamAccessTokenQuery) and Usher
// HLS URL. Confirmed via direct dex disassembly (classes.dex): built at exactly
// two call sites (Llam.a() for live playback, Llam.b() for VOD/clips), both of
// which derive the playerType string from PlayerType.toString() — an enum whose
// live-viewing default constant serializes to "mobile_player". Patching the
// constructor covers both call sites with a single fingerprint.
//
// Class and member names below are R8-obfuscated and will shift between app
// rebuilds; the only stable anchor is this class's own toString() literal
// prefix, which Kotlin's data-class-generated toString() bakes in as a
// compile-time constant and R8 has no reason to alter. Matched by:
//   - <init>, exactly 3 parameters
//   - param 0: String (playerType)
//   - param 2: int (defaults bitmask)
//   - sibling toString() method in the same class containing the literal
//     "PlaybackAccessTokenParams(disableHTTPS=" as a CONST_STRING instruction
//
// Confirmed present in v30.2.2 (versionCode 3002026). Not yet validated on
// device — see SkipAdsPatch.kt for what remains to be tested.
internal object PlaybackAccessTokenParamsConstructorFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        method.name == "<init>" &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes[0] == "Ljava/lang/String;" &&
            method.parameterTypes[2] == "I" &&
            classDef.methods.any { candidate ->
                candidate.name == "toString" &&
                    candidate.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.CONST_STRING &&
                            ((instruction as ReferenceInstruction).reference as? StringReference)
                                ?.string == "PlaybackAccessTokenParams(disableHTTPS="
                    } == true
            }
    },
)
