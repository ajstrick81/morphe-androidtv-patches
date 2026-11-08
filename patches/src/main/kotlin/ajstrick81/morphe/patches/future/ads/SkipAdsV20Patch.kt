package ajstrick81.morphe.patches.future.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.future.shared.Constants

@Suppress("unused")
val skipAdsV20Patch = bytecodePatch(
    name = "Skip ads (v20)",
    description = "Removes client-side ads in Fawesome Movies & TV (Android TV) on the 20.x " +
        "FilmRise MVVM rebuild. Two layers, both forcing the app's own no-ad verdict rather than " +
        "suppressing an ad display call: (1) the in-house ad-decision method is made to answer " +
        "\"no ads for this title\", so no ad playlist is built and every playback transition takes " +
        "its stock content path; (2) the IMA ad pod is nulled at the ad-request entry point, which " +
        "is the app's own \"no pod\" path, so no ad request is ever made. Ads are requested " +
        "client-side, so this is a full removal with no server-stitched (SSAI) fallback to break. " +
        "Content loading, resume and the seekbar are untouched.",
) {
    compatibleWith(Constants.COMPATIBILITY_V20)

    execute {
        // ── Hook 1 — in-house ads: answer the app's own "no ads" verdict ─────────
        // k0(String):Z decides whether an ad plays for this transition. Returning
        // false is the stock "Ad Status false" outcome: l1() (the in-house ad
        // playlist builder) is never called, and all six call sites branch into
        // content — five on `if-eqz`, and the pre-roll "Start" site falls through
        // on `if-nez`. The method's own catch block returns false too, so this is
        // a path the app already takes for ad-free titles.
        //
        // methodOrNull so a future re-anchor miss degrades gracefully (ads remain,
        // playback is untouched) instead of aborting the whole patch run.
        InhouseAdDecisionFingerprint.methodOrNull?.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // ── Hook 2 — IMA ads: null the ad pod at the request entry point ─────────
        // q(models, ObjectVideo, pod, transition) requests and plays the IMA pod.
        // Its first branch is `if-eqz <pod>, :cond_4` and :cond_4 is a plain
        // return-void, so a null pod is the app's own "nothing to play" path.
        //
        // Nulling the parameter (rather than returning at entry) keeps the
        // prologue that runs before that branch — the transition tag, the
        // GlobalObject ad-playing flag being cleared, and the player/view wiring —
        // so the player is left in exactly the state the stock no-ad path leaves it.
        //
        // The pod is this instance method's fourth parameter register
        // (p0 = this, p1 = models, p2 = ObjectVideo, p3 = pod), written via the
        // pN form so the assembler resolves it against the method's own frame —
        // no register arithmetic, and no local is clobbered.
        //
        // const/16 rather than const/4 on purpose: const/4 encodes its
        // destination in 4 bits (v0..v15 only), so on a register-heavy rebuild
        // where p3 lands above v15 it would fail to encode. const/16's
        // destination is 8 bits, which covers any frame this method can have.
        ImaAdRequestFingerprint.methodOrNull?.addInstructions(
            0,
            "const/16 p3, 0x0",
        )
    }
}
