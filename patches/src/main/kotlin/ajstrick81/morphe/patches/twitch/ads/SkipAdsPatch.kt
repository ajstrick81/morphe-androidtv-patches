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
    description = "Suppresses Twitch ads via two layers: spoofs the playerType on the " +
        "stream access token request to reduce server-side ad fill, and blocks the " +
        "GrandDads ad-eligibility query to prevent client-side ad decisioning. " +
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
        val toStringMethod = PlaybackAccessTokenParamsToStringFingerprint.method
        val paramsClass = toStringMethod.definingClass

        val constructor = toStringMethod.classDef.methods.first { it.name == "<init>" }

        // The constructor's first String parameter is playerType. In Kotlin
        // data classes, constructor parameters map to registers p1, p2, ...
        // after p0 (this). We overwrite the playerType register with our
        // spoofed value. The exact register depends on parameter order —
        // playerType is first in the data class, so it's p1.
        constructor.addInstructions(
            0,
            """
                const-string p1, "background_audio"
            """,
        )

        // Layer 2 — GrandDads ad-eligibility block
        //
        // Short-circuits the shouldDeclineAds method to return
        // Single.error(...) immediately, preventing the GrandDads GQL
        // query from ever executing. Downstream callers handle Single
        // errors gracefully (the ad system treats eligibility failures as
        // "no ads" — same behavior Purple TV achieves by returning
        // AdContextUnavailable).
        //
        // Technique credit: Purple TV (GrandDads short-circuit approach)
        DeclineAdsRequestFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Ljava/lang/Exception;
                const-string v1, "ads blocked"
                invoke-direct {v0, v1}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V
                invoke-static {v0}, Lio/reactivex/rxjava3/core/Single;->error(Ljava/lang/Throwable;)Lio/reactivex/rxjava3/core/Single;
                move-result-object v0
                return-object v0
            """,
        )
    }
}
