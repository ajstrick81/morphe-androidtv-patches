package ajstrick81.morphe.patches.purpletv.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

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

// ── Layer 2 ──────────────────────────────────────────────────────────────────
// Target: the GraphQL query Twitch's own client calls "GrandDads" internally —
// the actual per-session ad-eligibility/decisioning request (distinct from the
// playback access token request Layer 1 targets). This is the mechanism behind
// dynamic ad rotation: it returns a decline decision from the ad server before
// any specific ad creative is requested.
//
// Confirmed present and structurally unchanged between a decompiled 2024
// Purple TV build (which carries readable, unminified names for its own
// bundled Twitch code) and the current v30.2.2 official APK analyzed here —
// same query text, same AdRequestContext/AdRequestClientContext/
// AdRequestPlayerContext shape, same shared GraphQL repository class handling
// both this query and the playback token request from Layer 1.
//
// The query's document text is embedded as a compile-time string constant by
// Apollo's GraphQL codegen, making it a stable anchor independent of R8
// renaming — this identifies the query class itself.
internal object GrandDadsQueryDocumentFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("query GrandDads(\$context: AdRequestContext!)"),
)

// ── Layer 3 ──────────────────────────────────────────────────────────────────
// Target: the method that actually constructs and dispatches the GrandDads
// query — the equivalent of what a decompiled Purple TV build shows as
// GrandDadsApiImpl.shouldDeclineAds / GrandDadsFetcher$shouldDeclineAds$2.
// R8 has merged this into a shared lambda-dispatch class in the official
// build (confirmed via direct disassembly), so it isn't matchable by name.
//
// Located by scanning for a NEW_INSTANCE of the class Layer 2 identifies —
// this only depends on the *relationship* between the two obfuscated names,
// not on either one directly, so it survives independent per-build renaming
// of both classes.
internal object DeclineAdsRequestFingerprint : Fingerprint(
    custom = { method, classDef ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.NEW_INSTANCE &&
                (instruction as ReferenceInstruction).reference.let { it as? TypeReference }
                    ?.type == GrandDadsQueryDocumentFingerprint.method.definingClass
        } == true
    },
)
