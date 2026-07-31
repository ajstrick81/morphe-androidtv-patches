/*
 * Attribution:
 *
 * See Fingerprints.kt in this package for full credit to Purple TV,
 * TwitchAdSolutions, and Xtra for Twitch — the projects whose published
 * techniques informed this patch's ad-suppression strategy.
 */
package ajstrick81.morphe.patches.twitch.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.twitch.shared.Constants

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Suppresses Twitch ads by spoofing the playerType on the stream access " +
        "token request to reduce server-side ad fill. " +
        "UNTESTED — requires on-device validation.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // Layer 1 — playerType spoofing
        //
        // Locate PlaybackAccessTokenParams via its toString() string anchor,
        // then find the constructor in the same class. The constructor takes
        // the playerType as a String parameter. We prepend instructions that
        // overwrite the playerType register with "background_audio" before
        // the original constructor body runs.
        //
        // Why "background_audio": this playerType is used by Twitch's own
        // background playback mode and historically receives no ad fill.
        // If this value doesn't work, candidates to try (from enum Lr440):
        //   - "autoplay"
        //   - "prime_video_on_twitch_mobile" (shared Amazon infra)
        //   - "embed" (web embed context)
        //
        // Technique credit: TwitchAdSolutions (playerType spoofing concept)
        //
        // Verified 2026-07-28 against v30.2.2 smali (class `Ls1r;`): the
        // toString() field order in this build is
        // disableHTTPS,hasAdblock,playerBackend,playerType,maid,notificationID
        // — playerType is field `d` (String, non-Optional/required), and the
        // single physical constructor is `<init>(Ljava/lang/String;Lu1q;I)V`
        // where p1 is iput directly into `d`. So p1 IS playerType here,
        // confirming the register choice below is correct for this build.
        val toStringMethod = PlaybackAccessTokenParamsToStringFingerprint.method
        val paramsClass = toStringMethod.definingClass

        val constructor = mutableClassDefBy(paramsClass).methods.first { it.name == "<init>" }

        constructor.addInstructions(
            0,
            """
                const-string p1, "background_audio"
            """,
        )

        // Layer 2 — GrandDads ad-eligibility block — REMOVED 2026-07-28.
        //
        // The DeclineAdsRequestFingerprint pin (Lhs9;->f) was verified WRONG
        // against v30.2.2 smali: Lhs9; is a shared SAM-lambda dispatch class
        // (implements Lt5h;, dispatches on a numeric tag field to methods
        // a/b/c/e/f/i/l/m...), and method `f` in this build is an unrelated
        // ~700-instruction method that builds playback/ad-context objects and
        // returns an RxJava2 `io.reactivex.internal.operators.observable.j1`
        // (Observable), not a Single. It is not shouldDeclineAds.
        //
        // The injected smali also referenced `Lio/reactivex/rxjava3/core/
        // Single;`, which does not exist anywhere in this app — this build is
        // RxJava2-only (`io.reactivex.*`, itself R8-renamed to single-letter
        // classes like `Lio/reactivex/v;`, not "Single"/"Observable" by name).
        // That reference would have failed to resolve at runtime even if the
        // method pin had been right.
        //
        // Re-deriving the real GrandDads/shouldDeclineAds call site (finding
        // it via the "query GrandDads" document string's actual caller chain,
        // not a guessed lambda-class letter) is follow-up work, not done here.
    }
}
