package ajstrick81.morphe.patches.youtubetv.misc.auth

import app.morphe.patcher.Fingerprint

/**
 * Auth-related fingerprints for MicroG sign-in hook.
 *
 * Ported from Morphe mobile: youtube/misc/auth/Fingerprints.kt
 *
 * ALL FINGERPRINTS REQUIRE PHASE 0 REVERSE ENGINEERING.
 * The class names and method signatures below are placeholders and must be
 * verified against the actual YouTube TV APK.
 */

// AccountIdentityToStringFingerprint — finds getPageId() method
// In mobile: matches toString on account identity class, used to extract page ID
// For TV: similar account identity model likely exists
object AccountIdentityToStringFingerprint : Fingerprint(
    strings = listOf("pageId"),
    custom = { method, _ ->
        // TODO Phase 0: find account identity toString in YouTube TV APK
        method.name == "toString" && method.returnType == "Ljava/lang/String;"
    },
)

// IncognitoStatusFingerprint — finds isIncognito() method
// In mobile: method that returns incognito status boolean
// For TV: incognito mode likely exists on TV too
object IncognitoStatusFingerprint : Fingerprint(
    strings = listOf("incognito"),
    custom = { method, _ ->
        // TODO Phase 0: find incognito check method in YouTube TV APK
        method.returnType == "Z"
    },
)

// BuildRequestFingerprint — finds URL request builder
// In mobile: method that builds/hooks the InnerTube URL request
// For TV: request building likely uses the same InnerTube library
object BuildRequestFingerprint : Fingerprint(
    strings = listOf("youtubei"),
    custom = { method, _ ->
        // TODO Phase 0: find URL request builder in YouTube TV APK
        method.returnType != "V"
    },
)
