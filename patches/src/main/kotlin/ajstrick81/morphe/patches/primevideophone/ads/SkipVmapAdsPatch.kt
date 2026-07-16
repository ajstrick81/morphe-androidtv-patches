package ajstrick81.morphe.patches.primevideophone.ads

import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val skipVmapAdsPatch = bytecodePatch(
    name = "Skip VMAP ads (experimental)",
    description = "Empties the client-side VMAP ad-break list before AdManifest.createAdManifest " +
        "builds the manifest, so zero CSAI ad breaks are scheduled. Cleaner than the community " +
        "IllegalArgumentException trick (no exception — builds a valid empty manifest). Covers the " +
        "VMAP/client-side ad path only, not PV's SSAI road. " +
        "⚠️ FOR A STOCK PHONE APK ONLY — do NOT enable alongside hoodles' Skip-ads patch, which " +
        "depends on the ad-break metadata this strips (ads reappear + subtitles desync). " +
        "Opt-in / experimental.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Replace the incoming List<VmapAdBreak> (p0) with an empty list at
        // method entry. The subsequent checkNotNull(p0) passes (emptyList is
        // non-null), size() is 0, and the per-break loop never runs — so the
        // builder returns an AdManifest containing no ad breaks. p1 (AdHttpClient)
        // is only used inside that loop, so it stays untouched beyond its own
        // null-check. Pure inline smali — no extension class, no R8 keep rule.
        CreateAdManifestFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object p0
            """
        )
    }
}
