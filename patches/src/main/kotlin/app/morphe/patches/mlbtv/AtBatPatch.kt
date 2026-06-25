/*
 * MLB At Bat Android TV — Ad Suppression & Gambling-Ad Overlay Patch
 *
 * Validated against:
 *   v26.8.1.1 — com.bamnetworks.mobile.android.gameday.atbat
 *   (re-verified via androguard dex analysis of this exact build, 7 classesN.dex)
 *
 * Coverage:
 *   ✅ VOD ads              — createVodStreamRequest() empty zzdm →
 *                             IMA SDK throws → fallback to pre-cached CDN URL
 *   🧪 SSAI media source    — Lb6/h;.b0() blocked (no confirmed effect on
 *                             live ad segments — see status note)
 *   🧪 DAI StreamManager    — Lb6/h;.m0() blocked (same caveat as above)
 *   🧪 TXXX dispatch        — Lu70/i;.onMetadata() blocked (same caveat)
 *   🧪 Commercial-break overlay (Patch 5) — shows a full-screen overlay
 *                             during ad breaks. Primary trigger is now the
 *                             manifest rewriter (Patch 6), which signals an ad
 *                             break whenever it sees ad markers in a media
 *                             playlist; the IMA onAdBreakStarted()/
 *                             onAdBreakEnded() hooks are kept as a backup
 *                             (never observed firing in field tests).
 *   🧪 HLS manifest ad-segment stripping (Patch 6) — rewrites the .m3u8
 *                             playlist itself to drop dclk_video_ads
 *                             segments before ExoPlayer parses them.
 *                             Not yet field-tested.
 *
 * STATUS NOTE (this revision):
 *
 *   Two logcat captures against builds with Patches 2/3/4 active (most
 *   recently v1.5.1, confirmed against app v26.8.1.1) both showed
 *   dclk_video_ads segments STILL being fetched during live ad breaks —
 *   36 occurrences in the second capture — and the Patch 5 overlay never
 *   firing, with no crashes either time. This falsifies the original theory
 *   that blocking Lb6/h;.b0()/m0() would prevent requestStream() and thus
 *   prevent ad segment fetching: live SSAI/DAI ad segments are stitched
 *   directly into the HLS manifest server-side, so the player just
 *   sequentially requests whatever the manifest lists — there's no
 *   client-side "ad request" call gating segment fetches for IMA SDK
 *   method-blocking to intercept.
 *
 *   Patch 6 is the pivot: rather than blocking IMA SDK methods, it rewrites
 *   the manifest text itself at the data-source level (Lr5/a;.f(), Media3's
 *   OkHttpDataSource.open()) — see AtBatFingerprints.kt and
 *   MlbManifestRewriter.kt for the full mechanism. Patches 2/3/4/5 are left
 *   in place as belt-and-suspenders (Patch 1a/1b's VOD path and any
 *   tracking/cue suppression they still provide), but Patch 6 is the one
 *   actually expected to stop live ad segments. Needs a field test (logcat
 *   against a build containing Patch 6) before that expectation is
 *   confirmed — update this note once results come back.
 */

package app.morphe.patches.mlbtv

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val atbatPatch = bytecodePatch(
    name = "MLB At Bat Android TV",
    description = "Removes VOD ads and shows a commercial-break overlay over live gambling ads " +
        "while preserving live game playback.",
) {
    compatibleWith(AppCompatibilities.MLB_TV)

    extendWith("extensions/extension.mpe")

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
        // Patch 2: SSAI MediaSource Startup — Lb6/h;.b0(Lq5/w;)V
        //
        // Verified: string="ImaServerSideAdInsertionMediaSource" (UNIQUE in APK)
        // proto=(Lq5/w;)V, registers=10
        //
        // Called when ImaServerSideAdInsertionMediaSource starts up.
        // return-void prevents: Lb6/h$g; construction → requestStream()
        // call → DAI manifest URL generation → dclk_video_ads segments.
        //
        // Re-enabled: field testing showed no live-playback regressions.
        // May also prevent onAdBreakStarted()/onAdBreakEnded() (Patch 5)
        // from firing — see top-of-file status note, to be confirmed by
        // the next logcat capture against this build.
        // ------------------------------------------------------------------
        SsaiMediaSourceStartupFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 3: DAI StreamManager Event Handler — Lb6/h;.m0(StreamManager)V
        //
        // Verified: strings="IMA DAI Stream Event: ", "GSTREAM:DAI"
        // Belt-and-suspenders: prevents StreamManager from processing
        // DAI stream and scheduling ad segments even if Patch 2 is bypassed.
        //
        // Re-enabled for the same reason as Patch 2 — see top-of-file note.
        // ------------------------------------------------------------------
        DaiStreamManagerHandlerFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 4: TXXX Metadata Dispatcher — Lu70/i;.onMetadata(Ll5/t;)V
        //
        // Blocks ALL HLS timed metadata dispatch:
        //   → Lz70/b;.o() never called → MLB EVI coroutines never launched
        //   → Lb6/h$c;.onMetadata() never called → IMA cues suppressed
        //
        // Re-enabled: this is also the path IMA's StreamManager uses
        // internally to detect ad-break boundaries, so it may interact
        // with Patch 5's onAdBreakStarted()/onAdBreakEnded() hooks — see
        // top-of-file status note.
        // ------------------------------------------------------------------
        ExoMediaPlayerMetadataFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 5: Ad-Break Overlay
        //
        // 5a — registers the SSAI ad view group with the extension helper.
        // Lb6/h$d;.b(Ll5/q;)Lg6/w; calls
        //   Ll5/d;->getAdViewGroup()Landroid/view/ViewGroup;
        // immediately before passing the result into
        //   ImaSdkFactory.createStreamDisplayContainer(ViewGroup, VideoStreamPlayer)
        // The register holding the ViewGroup is located dynamically (rather
        // than hardcoded) since it can shift between app builds.
        // ------------------------------------------------------------------
        SsaiDisplayContainerFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val getAdViewGroupIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)?.name ==
                        "getAdViewGroup"
            }
            // Fail loud at patch time: a -1 here would silently make
            // moveResultIndex 0 and register a garbage view group, leaving the
            // overlay with nothing valid to attach to (it would never show).
            check(getAdViewGroupIndex != -1) {
                "SsaiDisplayContainerFingerprint matched but no getAdViewGroup() call was found — " +
                    "the overlay's ad view group cannot be registered"
            }
            val moveResultIndex = getAdViewGroupIndex + 1
            val adViewGroupRegister =
                (instructions[moveResultIndex] as OneRegisterInstruction).registerA
            val totalRegisters = implementation!!.registerCount
            val paramRegisters = parameters.size + 1 // +1 for implicit `this` (p0)
            val firstParamRegister = totalRegisters - paramRegisters
            val registerName = if (adViewGroupRegister >= firstParamRegister) {
                "p${adViewGroupRegister - firstParamRegister}"
            } else {
                "v$adViewGroupRegister"
            }

            addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static {$registerName}, Lajstrick81/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->registerAdViewGroup(Landroid/view/ViewGroup;)V
                """.trimIndent(),
            )
        }

        // 5b/5c — wire the no-op ad-break lifecycle callbacks to show/hide
        // the overlay. These are now a BACKUP trigger: the primary trigger is
        // MlbManifestRewriter.signalAdBreak() (Patch 6), which fires off the
        // actual manifest content and does not depend on these callbacks ever
        // running (they never were observed firing in field tests). Both
        // bodies are a single return-void (1 register, `this`), so prepending
        // at index 0 is unconditionally safe — no registers are live yet for
        // the verifier to conflict over.
        AdBreakStartedFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->showOverlay()V
            """.trimIndent(),
        )

        AdBreakEndedFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/mlbtv/ads/AdBreakOverlayHelper;->hideOverlay()V
            """.trimIndent(),
        )

        // ------------------------------------------------------------------
        // Patch 6: HLS Manifest Ad-Segment Stripping — Lr5/a;.f(Lq5/i;)J
        //
        // Patches 2/3/4 block the IMA SDK's own DAI/SSAI Java methods, but
        // logcat against v1.5.1 confirmed dclk_video_ads segments are STILL
        // fetched during live games regardless — they're stitched into the
        // HLS manifest server-side, so there's no client-side "ad request"
        // call left to intercept. The only remaining point of control is the
        // manifest text itself, before ExoPlayer's HLS parser reads it.
        //
        // f(Lq5/i;)J is OkHttpDataSource.open(): it stores the raw response
        // InputStream into the data source's only Ljava/io/InputStream;
        // field right after the network read. We splice
        // MlbManifestRewriter.wrap() in at that exact point — it passes
        // through non-manifest fetches (segments, etc.) untouched and only
        // rewrites .m3u8 responses that actually contain ad markers.
        //
        // Register safety: this reuses the InputStream register already
        // live at the iput-object site (no new register is introduced for
        // it) and passes the DataSpec parameter via its own untouched `p1`
        // register — f() takes exactly one declared parameter, so `p1` is
        // guaranteed to hold it for the lifetime of the method regardless of
        // any internal `move-object` the method does with its own locals.
        // The extension method itself uses reflection to pull the Uri out of
        // the DataSpec rather than requiring its obfuscated type at the
        // smali call site.
        // ------------------------------------------------------------------
        OkHttpDataSourceOpenFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val iputStreamIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    ((instruction as ReferenceInstruction).reference as? FieldReference)?.type ==
                        "Ljava/io/InputStream;"
            }
            // Fail loud at patch time: a -1 here would index instructions[-1]
            // and throw an opaque IndexOutOfBounds; surface the real cause
            // (the manifest rewriter could not be spliced in) instead.
            check(iputStreamIndex != -1) {
                "OkHttpDataSourceOpenFingerprint matched but no InputStream iput-object was found — " +
                    "the manifest rewriter cannot be spliced in"
            }
            val streamRegister = "v${(instructions[iputStreamIndex] as TwoRegisterInstruction).registerA}"

            addInstructions(
                iputStreamIndex,
                """
                    invoke-static {p1, $streamRegister}, Lajstrick81/morphe/extension/mlbtv/ads/MlbManifestRewriter;->wrap(Ljava/lang/Object;Ljava/io/InputStream;)Ljava/io/InputStream;
                    move-result-object $streamRegister
                """.trimIndent(),
            )
        }
    }
}
