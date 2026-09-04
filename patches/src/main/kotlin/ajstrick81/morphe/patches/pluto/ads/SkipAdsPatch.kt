package ajstrick81.morphe.patches.pluto.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Removes Pluto TV's on-demand (VOD) ads entirely — ad video, markers, " +
        "overlays, and beacons. Two layers: (1) drops the ad <Period>s from the stitched " +
        "DASH manifest at media3's parser and re-bases the content periods contiguous, so " +
        "the ad VIDEO is never played (verified on-device: 2:49:58 -> 2:18:41, mid-rolls " +
        "gone); (2) empties the client-side ad-break timeline (StitcherSession.adBreaks — " +
        "the same data AdGuard strips via jsonprune) and no-ops pause ads and clickable-ad " +
        "overlays, removing the markers/UI/beacons. Fail-open: a manifest it can't rewrite " +
        "is passed through unchanged (ads remain, playback never breaks). LIVE TV ads are " +
        "real broadcast time in the linear feed and are not removable. Validated on-device, " +
        "5.66.0-leanback.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // Hook 5 merges PlutoDashManifestProbe (stripAdPeriods) into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {

        // Hook 1 — BeaconTracker.fire(String, List)
        // Silences every stitched-ad tracking beacon (impressions, quartiles,
        // completes). Fire-and-forget void method — no playback dependency.
        BeaconTrackerFireFingerprint.method.addInstructions(0, "return-void")

        // Hook 2 — PauseAdsImageBinder.showPauseAdImageAfterInactivity(Bitmap, List)
        // Suppresses the pause-ad overlay and its beacons.
        PauseAdsShowFingerprint.method.addInstructions(0, "return-void")

        // Hook 3 — ClickableAdsBinder$Companion.bind(...): Disposable
        // Prevents clickable ("interactive") ad overlays from ever being wired
        // by returning an already-disposed Disposable at offset 0. The caller
        // adds it to a CompositeDisposable as usual — a disposed Disposable is
        // a harmless no-op, so the contract is honored and nothing hangs.
        //
        // Verifier note: at offset 0 no registers are live, so writing a fresh
        // Disposable into v0 and returning it is always accepted (same pattern
        // as the Peacock getOkHttpClient replacement). Disposables.disposed()
        // is confirmed present in the app's bundled RxJava.
        ClickableAdsBindFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lio/reactivex/disposables/Disposables;->disposed()Lio/reactivex/disposables/Disposable;
                move-result-object v0
                return-object v0
            """,
        )

        // Hook 4 — StitcherSession.getAdBreaks() -> empty list
        // Reproduces AdGuard Premium's proven suppression in bytecode: its
        // filtering log strips the ad-break timeline from the stitcher session
        // response (||pluto.tv/*/session.json$jsonprune=$.adBreaks.*). This is
        // the in-app equivalent at the single client-side model choke point —
        // every consumer of the parsed StitcherSession (timebar
        // AdBreakPositionSource, the ID3/slot beacon controllers, and the
        // player's ad-break handling) reads the breaks through this getter, so
        // returning an empty list de-schedules the entire ad experience.
        // getAdBreaks() has .locals 1, so v0 is valid at entry.
        StitcherSessionGetAdBreaksFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
            """,
        )

        // Hook 5 — DASH period surgery: remove the ad VIDEO (the real removal).
        //
        // Hook 4 only empties the adBreaks metadata (markers/beacons/UI/seek); the
        // ad video still plays because the stitched DASH manifest media3 fetches is
        // a MULTI-PERIOD timeline with the ads as real <Period>s. media3 is NOT
        // obfuscated in this build, so we route the parsed manifest through
        // stripAdPeriods at DashManifestParser.parse's return (post-download,
        // post-parse — it never touches the streaming OkHttp client, which
        // black-screens playback when wrapped). It DROPS the ad periods, detected
        // positively by the ad-creative URL signature (_ad/creative on the spots,
        // _ad_bumper on the pod bumper), and keeps everything else (content, DRM'd
        // or not) — re-bases the kept periods contiguous and rebuilds the manifest.
        // Positive ad-detection (vs trusting a DRM marker for content) is the issue
        // #144 fix: some titles serve content un-DRM'd, so the old content==pDRM test
        // dropped the whole episode to a 10s stub -> skip/restart.
        // Fail-open: any surprise returns the original manifest (ads remain,
        // playback never breaks). See the pluto-adbreaks-suppression note and the
        // PLUTO_VOD_ADREMOVAL_HANDOFF doc for how this seam was mapped.
        DashManifestParserParseFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            check(returnIndex >= 0) { "skipAdsPatch Hook 5: no return-object in parse()" }
            val manifestRegister = (instructions[returnIndex] as OneRegisterInstruction).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static {v$manifestRegister}, Lajstrick81/morphe/extension/pluto/ads/PlutoDashManifestProbe;->stripAdPeriods(Landroidx/media3/exoplayer/dash/manifest/DashManifest;)Landroidx/media3/exoplayer/dash/manifest/DashManifest;
                    move-result-object v$manifestRegister
                """.trimIndent(),
            )
        }
    }
}
