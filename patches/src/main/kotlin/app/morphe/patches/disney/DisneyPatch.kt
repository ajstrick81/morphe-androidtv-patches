/*
 * Credit:
 * Original work by RookieEnough aka The G.O.A.T :)
 *
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/disneyplus/ads/Fingerprints.kt
 *
 * Modified for use in morphe-androidtv-patches
 *
 * Validated against Disney+ Android TV v26.8.0+rc6-2026.05.20
 * Package:     com.disney.disneyplus
 * VersionCode: 1779314460
 */

package app.morphe.patches.disney

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

@Suppress("unused")
val disneyPatch = bytecodePatch(
    name = "Disney+ Android TV",
    description = "Removes mid-roll / pre-roll ads and pause ads.",
) {
    compatibleWith(AppCompatibilities.DISNEY_PLUS_TV)

    execute {
        // ------------------------------------------------------------------
        // Patch 1 & 2: Pre-roll / mid-roll SGAI/SSAI ad insertion
        //
        // Insertion.getPoints() returns the list of InsertionPoints (ad cue
        // positions) for a media item. Returning an empty list causes the
        // player to see zero ad cues and skip all break scheduling.
        //
        // Insertion.getRanges() returns ad range windows used by:
        //   - Media3ExtensionsKt.allowedLiveInterstitials()  (live ad gating)
        //   - InsertionJsonAdapters                          (serialisation)
        // Emptying it prevents live interstitial gating from admitting any
        // ad range and stops serialisation from writing range data.
        //
        // Two parallel insertion containers must be emptied:
        //   - com.dss.sdk.internal.media.Insertion       (legacy BAM-SDK)
        //   - com.disney.dmp.internal.pbo.Insertion      (new Disney Media
        //     Platform pipeline, v26.9.2+ — the SGAI/SSAI VOD ads the legacy
        //     patch missed rode through here). See Fingerprints.kt.
        //
        // Both methods are simple iget-object / return-object pairs, so
        // prepending a fresh ArrayList return at offset 0 is safe — the
        // original iget is never reached and the field is never read.
        // ------------------------------------------------------------------
        arrayOf(
            InsertionGetPointsFingerprint,
            InsertionGetRangesFingerprint,
            DmpInsertionGetPointsFingerprint,
            DmpInsertionGetRangesFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.addInstructions(
                0,
                """
                    new-instance v0, Ljava/util/ArrayList;
                    invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                    return-object v0
                """.trimIndent(),
            )
        }

        // ------------------------------------------------------------------
        // Patch 3: Pause ads — MediaXPauseSession.started()
        //
        // PCAPdroid analysis confirmed the following pause ad sequence:
        //   +25.52s → us-east-2.ads.digital.disneyadvertising.com (ad decision)
        //   +26.10s → prod-frida-origin.bamgrid.com               (image fetch ← HERE)
        //   +26.13s → AWS EC2 CDN                                  (image delivery)
        //   +26.29s → ad.doubleclick.net                           (measurement)
        //
        // started() is where MediaXPause.into() is called to build the Glide
        // render model (bv0/m), triggering the image fetch. Patching here
        // with return-void at offset 0 prevents into() from ever being called:
        //
        //   - No Glide network request to prod-frida-origin.bamgrid.com
        //   - No ad image delivered from AWS CDN
        //   - No impression beacon to beacons.digital.disneyadvertising.com
        //   - No DoubleClick measurement ping
        //   - Overlay render event (getStarted().onNext()) never published
        //   - Overlay view stays at alpha=0 for duration of pause
        //
        // The ad decision request to us-east-2.ads.digital.disneyadvertising.com
        // still fires at +25.52s (sent from upstream in the MEL layer before
        // started() is called) — this can be suppressed at DNS level via AGH
        // if desired, but has no visible effect since started() never renders.
        // ------------------------------------------------------------------
        PauseAdStartedFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )
    }
}
