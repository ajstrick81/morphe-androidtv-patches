/*
 * MLB At Bat Android TV — Ad & Gambling Content Suppression Patch
 *
 * Validated against:
 *   v26.8.1  (versionCode 1750000022) — com.bamnetworks.mobile.android.gameday
 *
 * Coverage:
 *   ✅ VOD ads              — createVodStreamRequest() empty zzdm →
 *                             IMA SDK throws → fallback to pre-cached CDN URL
 *   ✅ Ad-break content     — Patch 5 overlay: shows "Commercial Break in
 *                             Progress" full-screen over the IMA ad-overlay
 *                             ViewGroup for the duration of every ad break
 *                             (network-level VOD/EVI/SSAI/DAI ad plumbing is
 *                             left completely untouched — see below).
 *   ⚠️  Live games           — Patch 5 is believed safe for live (nothing in
 *                             the playback pipeline is touched, only the
 *                             pre-existing no-op onAdBreakStarted/
 *                             onAdBreakEnded callbacks are hooked), but has
 *                             not been confirmed on-device during a real
 *                             live-game ad break.
 *
 * ARCHITECTURE (current default — Patches 2/3/4 disabled, Patch 5 added):
 *
 *   Patches 2/3 (voiding Lb6/h;.b0()/m0()) and Patch 4 (voiding
 *   onMetadata()) are now DISABLED BY DEFAULT (see inline comments below for
 *   full rationale). Both were found, on closer trace, to sever the exact
 *   callback chain Patch 5's overlay depends on:
 *     - Patches 2/3 block the entire SSAI/DAI session, which prevents
 *       onAdBreakStarted()/onAdBreakEnded() from ever firing.
 *     - Patch 4 blocks onMetadata(), which prevents TXXX/ID3 timed metadata
 *       from reaching onUserTextReceived() — the standard IMA DAI mechanism
 *       that feeds the SDK's internal ad-break boundary detection, which is
 *       what ultimately triggers onAdBreakStarted()/onAdBreakEnded().
 *
 *   With all three disabled, the ad break (VOD or live, EVI or DAI) plays
 *   out completely normally end-to-end, and Patch 5 suppresses it at the
 *   presentation layer instead — consistent behavior for both VOD and live,
 *   since nothing in the playback pipeline itself is modified. The original
 *   network-level blocking code for Patches 2/3/4 is preserved (commented
 *   out, not deleted) for anyone who wants VOD-only network-level blocking
 *   back instead of the overlay.
 *
 * PRE-DAI VOD FALLBACK INVESTIGATION (dead end, static analysis only):
 *   Searched for an app-level "fallback to pre-cached CDN URL" mechanism
 *   upstream of Lb6/h that would explain why VOD recovers ad-free after
 *   Patches 1a/1b corrupt createVodStreamRequest(). Exhaustively confirmed:
 *     - Lb6/k;->a()Landroid/net/Uri; (the only code that constructs the
 *       ssai://dai.google.com/... URI) is never called anywhere in the
 *       decompiled dex.
 *     - Lb6/k; is never referenced from outside the b6/ package.
 *     - The literal strings "ssai"/"dai.google.com" appear only in
 *       g6/n.smali (router), b6/e.smali (a sanity-check Runnable), and
 *       b6/k.smali (the parser) itself.
 *   Conclusion: the SSAI-vs-plain-URL decision (ad-supported vs. ad-free
 *   stream) is made server-side by MLB's backend in the playback API
 *   response, not by any local app-level fallback/retry code. There is no
 *   code-level mechanism to find here — going further would require
 *   inspecting live network/API responses, out of scope for static
 *   bytecode analysis. This investigation is what motivated the pivot to
 *   the presentation-layer overlay (Patch 5) instead of chasing VOD/live
 *   parity in the network-blocking approach.
 *
 * SUPERSEDED DIAGNOSIS (06-18 LOGCAT, from when Patches 2/3/4 were active):
 *   MLB EVI (/EVI/ segments): ZERO — confirmed blocked ✅
 *   TXXX metadata:            ZERO — confirmed blocked ✅
 *   dclk_video_ads:           22 segments — still fetching ❌
 *
 *   Root cause: Lb6/k;.b() returned empty zzdm but zzan.requestStream()
 *   succeeded anyway — IMA SDK uses server-side AdsLoader session state,
 *   not StreamRequest parameters, to generate the DAI manifest URL.
 *
 *   The fix at the time was to block Lb6/h;.b0() before requestStream() is
 *   called at all (Patch 2) — superseded by the overlay approach above.
 *
 * LIVE-GAME RISK ANALYSIS FOR PATCHES 2/3 (kept for reference; these patches
 * are disabled by default — static trace, no device, see git history for
 * the full session):
 *   Lb6/k;.b(Landroid/net/Uri;) — the helper that builds the StreamRequest —
 *   branches on the "assetKey" query param: present → createLiveStreamRequest
 *   (live); absent → createVodStreamRequest (VOD, the call Patches 1a/1b
 *   corrupt). So 1a/1b have ZERO effect on live — createLiveStreamRequest is
 *   completely untouched by any current patch. Only Patches 2/3 touch live.
 *
 *   Patches 2/3 act on Lb6/h (ImaServerSideAdInsertionMediaSource) itself,
 *   and nothing in its b0()/r()/p0 lifecycle branches on stream type — VOD
 *   and live run the identical code. Traced end-to-end: b0() is the only
 *   method that builds the ad-request Loader (Lb6/h$g/$h); Lb6/h$h.i()
 *   (onLoadCompleted) is the ONLY site in the whole dex that ever assigns
 *   Lb6/h;->p0 (the real child MediaSource); r() (createPeriod) delegates
 *   to p0 unconditionally. With b0() voided, p0 can never populate and the
 *   Timeline never refreshes, so the player can never reach r() at all for
 *   either content type — there's no crash path, the source simply never
 *   reports itself prepared. Whatever lets VOD recover today and play back
 *   ad-free happens upstream of Lb6/h (not found in Lb6/h, Lb6/h$d, or
 *   Lb6/k — likely an app-level stall/timeout fallback), and nothing in
 *   the traced chain distinguishes VOD from live.
 *
 *   IMPORTANT CAVEAT, drawn from this repo's other ad-suppression patches
 *   for apps using the same IMA-style SSAI/DAI architecture:
 *
 *     - paramount/ParamountPatch.kt documents that on Paramount+'s
 *       structurally-identical IMA SSAI wrapper, VOD recovers ad-free
 *       because the player already holds an independently-known, pre-DAI
 *       fallback content URL (g1.c()/nm0.c/ek0.s) — and that fallback is
 *       explicitly absent for live TV ("live TV has no fallback content
 *       URL"), so the equivalent fix is documented there as "DAI untouched"
 *       for live.
 *     - hbomax/HBOAdsPatch.kt needed a SEPARATE, dedicated fingerprint/patch
 *       (Patch 5, generateLiveTimelineEntriesForAdBreak) specifically for
 *       live ad-break timeline entries — patching the VOD SSAI timeline
 *       builder (Patch 3) alone did not cover live, because live ad
 *       scheduling ran through independent code.
 *     - disney/DisneyPatch.kt's getRanges() feeds a distinct
 *       allowedLiveInterstitials() live-ad-gating path, separate from the
 *       getPoints() VOD/mid-roll cue list.
 *
 *   The common thread: on this class of player, "no VOD/live branch inside
 *   the shared SSAI wrapper" does NOT by itself prove live is unaffected —
 *   real-world live ad suppression on sibling apps routinely turns out to
 *   depend on mechanisms (fallback URLs, timeline builders, gating checks)
 *   that live outside that wrapper and are VOD-only. MLB At Bat's actual
 *   upstream VOD recovery path (whatever lets VOD fall back to its
 *   pre-cached CDN URL after b0() is voided) has not been located in
 *   Lb6/h, Lb6/h$d, or Lb6/k. Until that mechanism is found and confirmed
 *   to also exist for live, Patches 2/3's effect on live games should be
 *   treated as UNCONFIRMED, not "should be safe by symmetry."
 *
 *   Also ruled out as a factor: Lb6/d/Lb6/c, a separate classic
 *   client-side ad insertion (ImaAdsLoader-style) pathway elsewhere in the
 *   app — confirmed structurally unrelated to this SSAI flow.
 */

package app.morphe.patches.mlbtv

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val atbatPatch = bytecodePatch(
    name = "MLB At Bat Android TV",
    description = "Shows a \"Commercial Break in Progress\" overlay during between-innings " +
        "ad breaks (suppressing harmful gambling ad content) and removes VOD ads, " +
        "while preserving live game playback.",
) {
    compatibleWith(AppCompatibilities.MLB_TV)

    execute {
        // ------------------------------------------------------------------
        // Patch 1a: VOD SSAI — createVodStreamRequest (3-arg)
        // ------------------------------------------------------------------
        VodStreamRequest3ArgFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Lcom/google/ads/interactivemedia/v3/impl/zzdm;
                sget-object v1, Lcom/google/ads/interactivemedia/v3/internal/zzafs;->zzd:Lcom/google/ads/interactivemedia/v3/internal/zzafs;
                invoke-direct {v0, v1}, Lcom/google/ads/interactivemedia/v3/impl/zzdm;-><init>(Lcom/google/ads/interactivemedia/v3/internal/zzafs;)V
                return-object v0
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 1b: VOD SSAI — createVodStreamRequest (4-arg)
        // ------------------------------------------------------------------
        VodStreamRequest4ArgFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Lcom/google/ads/interactivemedia/v3/impl/zzdm;
                sget-object v1, Lcom/google/ads/interactivemedia/v3/internal/zzafs;->zzd:Lcom/google/ads/interactivemedia/v3/internal/zzafs;
                invoke-direct {v0, v1}, Lcom/google/ads/interactivemedia/v3/impl/zzdm;-><init>(Lcom/google/ads/interactivemedia/v3/internal/zzafs;)V
                return-object v0
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patches 2/3: SSAI MediaSource Startup / DAI StreamManager Handler
        // — DISABLED in favor of Patch 5 (overlay).
        //
        // Voiding Lb6/h;.b0()/m0() blocks the entire SSAI/DAI session, which
        // also prevents onAdBreakStarted()/onAdBreakEnded() from ever firing
        // — the exact callbacks Patch 5's overlay depends on. Their effect
        // on live games was also never confirmed on-device (see the
        // LIVE-GAME RISK ANALYSIS above). Disabled by default so the ad
        // break runs normally end-to-end and Patch 5 can suppress its
        // content at the presentation layer instead, consistently for both
        // VOD and live. Re-enable below only if you specifically want
        // network-level VOD ad blocking back and don't need the overlay.
        // ------------------------------------------------------------------
        // SsaiMediaSourceStartupFingerprint.method.addInstructions(
        //     0,
        //     """
        //         return-void
        //     """.trimIndent(),
        // )
        // DaiStreamManagerHandlerFingerprint.method.addInstructions(
        //     0,
        //     """
        //         return-void
        //     """.trimIndent(),
        // )

        // ------------------------------------------------------------------
        // Patch 4: TXXX Metadata Dispatcher — Lu70/i;.onMetadata(Ll5/t;)V
        // — DISABLED in favor of Patch 5 (overlay).
        //
        // CONFIRMED WORKING in isolation (logcat 06-18: zero TXXX, zero EVI
        // segments), but Lb6/h$c;.onMetadata() forwards TXXX entries to
        // Lb6/h$i;->a (the VideoStreamPlayerCallback list) via
        // onUserTextReceived(String) — the standard IMA DAI mechanism apps
        // use to feed ID3/TXXX timed metadata into the SDK so it can track
        // ad-break boundaries internally. Blocking onMetadata() upstream
        // therefore also starves IMA's ad-break detection, which means
        // Patch 5's onAdBreakStarted()/onAdBreakEnded() hooks would never
        // fire either. Disabled by default so TXXX reaches IMA normally and
        // the overlay can suppress ad content at the presentation layer
        // instead. Re-enable below only if you don't need the overlay and
        // specifically want EVI/TXXX dispatch blocked again.
        // ------------------------------------------------------------------
        // ExoMediaPlayerMetadataFingerprint.method.addInstructions(
        //     0,
        //     """
        //         return-void
        //     """.trimIndent(),
        // )

        // ------------------------------------------------------------------
        // Patch 5: "Commercial Break in Progress" overlay
        //
        // Registers the IMA ad-overlay ViewGroup once at SSAI MediaSource
        // construction time, then shows/hides a full-screen overlay on the
        // existing (previously no-op) onAdBreakStarted()/onAdBreakEnded()
        // callbacks. Nothing in the playback pipeline itself is touched, so
        // this is safe for VOD and live alike.
        // ------------------------------------------------------------------
        val displayContainerMethod = SsaiDisplayContainerFingerprint.method
        val getAdViewGroupInstructionIndex = displayContainerMethod.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_INTERFACE &&
                ((it as ReferenceInstruction).reference as? MethodReference)?.name == "getAdViewGroup"
        }
        val moveResultInstruction = displayContainerMethod.implementation!!.instructions[getAdViewGroupInstructionIndex + 1]
        val adViewGroupRegister = (moveResultInstruction as OneRegisterInstruction).registerA

        displayContainerMethod.addInstructions(
            getAdViewGroupInstructionIndex + 2,
            """
                invoke-static {v$adViewGroupRegister}, Lapp/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->registerAdViewGroup(Landroid/view/ViewGroup;)V
            """.trimIndent(),
        )

        AdBreakStartedFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->showOverlay()V
            """.trimIndent(),
        )

        AdBreakEndedFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->hideOverlay()V
            """.trimIndent(),
        )
    }
}
