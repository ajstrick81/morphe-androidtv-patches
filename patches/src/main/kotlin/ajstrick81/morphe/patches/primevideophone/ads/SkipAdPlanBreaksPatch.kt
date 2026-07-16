package ajstrick81.morphe.patches.primevideophone.ads

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val skipAdPlanBreaksPatch = bytecodePatch(
    name = "Empty ad-break plan (experimental)",
    description = "Empties AdManagerBasedAdPlan.getBreaks() — the central ad-break timeline the " +
        "player's ad state machine reads to schedule ads. Because the SSAI plan " +
        "(ServerInsertedAdManagerBasedAdPlan) only bridges via invoke-super, this one hook covers " +
        "both server-side and client-side ad plans. Prime Video analog of the Disney " +
        "Insertion.getPoints() strip (Java ad-timeline technique). " +
        "⚠️ FOR A STOCK PHONE APK ONLY — do NOT enable on a build that already carries hoodles' " +
        "Skip-ads patch. That patch skips ads by SEEKING past them, so it NEEDS this break " +
        "metadata; emptying it makes the ads play back as raw stream AND desyncs subtitles. " +
        "Opt-in / experimental.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Prepend an empty-list return. The player's break scheduler then sees
        // zero ad breaks (SSAI + CSAI), so no ads are inserted. EmptyAdPlan in
        // the app proves an empty break list is a valid, crash-safe state.
        // .locals 1 in the original, so v0 is safe to use at offset 0.
        AdPlanGetBreaksFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
            """
        )
    }
}
