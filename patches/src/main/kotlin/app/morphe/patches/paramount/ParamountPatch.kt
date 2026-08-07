/*
 * Paramount+ Android TV — Ad Suppression Patch
 *
 * Validated against:
 *   v16.8.0  (versionCode 520000464) — com.cbs.ott  [empty-request era, see history]
 *   v16.12.0 (versionCode 520000571) — com.cbs.ott
 *   v16.17.0 (versionCode 520000758) — com.cbs.ott  [current mechanism]
 *
 * MECHANISM (v16.17.0, on-device verified 2026-08-04):
 *   The AVIA player's DAI resource provider
 *   (com.paramount.android.avia.player.resource.dai.AviaDAIResourceProvider)
 *   gates every ad pod through shouldPlayAd(AviaAdPod). Its position monitor
 *   (AviaDAIResourceProvider$3) checks shouldPlayAd for each unwatched pod and,
 *   when it returns false, takes the app's own native skip-the-pod path — the
 *   ad break never starts, the DAI stream keeps playing, and content resumes
 *   with no black screen. Forcing shouldPlayAd() -> false therefore removes
 *   VOD pre-rolls (movies + TV) cleanly. Confirmed ad-free on titles that
 *   previously served a 120s pre-roll; playback healthy.
 *
 *   This SUPERSEDES the earlier empty-createVodStreamRequest approach, which
 *   worked on v16.8.0 but on v16.17.0 kills the video entirely (v16.17 VOD is
 *   DAI-only with no separate content stream to fall back to) and, on v16.12.0,
 *   provoked an IMA retry storm / ANR. The empty-request and DAI-retry patches
 *   have been removed in favour of the shouldPlayAd gate.
 *
 * Coverage (v16.17.0, on-device verified):
 *   ✅ VOD ads, pre-roll AND mid-roll (movies + TV) — shouldPlayAd() gates every
 *      pod, so both are skipped. Cosmetic seekbar markers may remain on TV
 *      episodes but no ad plays through them.
 *   ✅ Pause ads                     — CbsPauseWithAdsOverlay state machine
 *   ✅ Live TV                       — preserved; streams cleanly (shouldPlayAd
 *      is shared VOD/live but the live path is unaffected on-device)
 */

package app.morphe.patches.paramount

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

@Suppress("unused")
val paramountPatch = bytecodePatch(
    name = "Paramount+ Android TV",
    description = "Removes VOD pre-roll ads and pause ads while preserving live TV.",
) {
    compatibleWith(AppCompatibilities.PARAMOUNT_TV)

    execute {
        // ------------------------------------------------------------------
        // Patch 1: VOD ad gate — DAIResourceProvider.shouldPlayAd(AviaAdPod)
        //
        // Return false for every pod so the provider's position monitor takes
        // its native skip path instead of engaging the ad break.
        // ------------------------------------------------------------------
        ShouldPlayAdFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 2: Pause ads — CbsPauseWithAdsOverlay state machine
        //
        // return-void prevents Glide image fetch, alpha fade-in, and overlay
        // render. Overlay stays at alpha=0.
        // ------------------------------------------------------------------
        PauseAdOverlayFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )
    }
}
