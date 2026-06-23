package ajstrick81.morphe.patches.peacock.ads

import ajstrick81.morphe.patches.peacock.shared.Constants
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Disables ad delivery via Sky SDK surgical targets (FreeWheel DI module " +
        "skip, MediaTailor SSAI layers, ad-break-started no-op), OkHttp interceptor, and " +
        "WebView shouldInterceptRequest wrapper. Validated v7.5.102.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // extendWith populates Patch.extensionInputStream — without this call the
    // PeacockAdPatchHelper/PeacockWebViewHelper/AdBlockInterceptor classes
    // referenced below via invoke-static never get merged into the patched
    // APK's dex, even though the smali calling them assembles fine.
    extendWith("extensions/extension.mpe")

    execute {
        // ── Layer 1 ─────────────────────────────────────────────────────────
        // Kill MediaTailor SSAI proxy — empty string prevents proxy URL
        // configuration, disabling server-side ad insertion at the source.
        MediaTailorProxyHostFingerprint.method.addInstructions(
            0,
            """
                const-string v0, ""
                return-object v0
            """.trimIndent(),
        )

        // ── Layer 3 ─────────────────────────────────────────────────────────
        // Abort MediaTailor ad service construction — return null from the
        // factory method identified by its unique error string anchor.
        MediaTailorAdServiceMethodFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent(),
        )

        // ── Layer 4 ─────────────────────────────────────────────────────────
        // Force AdvertisingStrategy.None — getSsaiConfigurationProvider()
        // returning null causes strategyForType() to take the confirmed
        // if-eqz → None branch for ALL playback types. No crash risk.
        SsaiConfigurationProviderFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent(),
        )

        // ── Layer 5 ─────────────────────────────────────────────────────────
        // PlayerEngineItemImpl.handleAdBreakStarted(AdBreakStartedEvent) is a
        // void method whose entire body launches a coroutine to handle an
        // already-started ad break. With Layers 1/3/4/8 preventing any ad
        // break from ever being scheduled in the first place, this should be
        // unreachable in practice — but making it a no-op at offset 0 closes
        // the gap defensively in case any ad-break event still reaches the
        // player engine (e.g. a stale/cached SSAI manifest reference).
        // Void return — no register setup needed, return-void is always
        // verifier-safe at offset 0 regardless of live registers.
        HandleAdBreakStartedFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent(),
        )

        // ── Layer 6 ─────────────────────────────────────────────────────────
        // Replace NetworkingKt.getOkHttpClient() body entirely via a no-arg
        // static call to PeacockAdPatchHelper.buildOkHttpClient().
        //
        // History of VerifyErrors:
        //   v1.4.56 — offset 5, 4-instruction block passing v0 as Builder arg
        //             → type=Undefined at 0x16 (move-result-object after build())
        //   v1.4.57 — offset 5, single invoke-static {v0} passing Builder arg
        //             → type=Conflict at 0x10 (verifier ambiguous on v0 type
        //                at mid-method merge point)
        //
        // Fix — offset 0, no register arguments:
        //   At offset 0 no registers are live. invoke-static {} touches nothing.
        //   move-result-object v0 assigns a fresh OkHttpClient into an
        //   uninitialized register — the verifier always accepts this.
        //   return-object v0 exits cleanly. Original method body unreachable.
        GetOkHttpClientFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lajstrick81/morphe/extension/peacock/ads/PeacockAdPatchHelper;->buildOkHttpClient()Lokhttp3/OkHttpClient;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )

        // ── Layer 7 ─────────────────────────────────────────────────────────
        // WebView shouldInterceptRequest injection.
        //
        // PCAP/GREASE analysis confirmed ad segment delivery and FreeWheel
        // traffic travels through Chromium/WebView, bypassing OkHttp entirely.
        // XTVWebView's xtvClient does not override shouldInterceptRequest.
        //
        // activity_main.xml declares VirtualDpadXTVWebView (a subclass of
        // XTVWebView), which Android's LayoutInflater instantiates via the
        // 2-arg (Context, AttributeSet) constructor — NOT the 1-arg one.
        // Wrapping only the 1-arg constructor left every real-world
        // instantiation of this view completely unwrapped, which is why
        // MORPHE-PCK-WV never appeared in logcat and preroll ads persisted
        // despite Layers 1-6/8 all functioning. All three constructor
        // overloads are now patched so every instantiation path is covered.
        //
        // Each wraps xtvClient via PeacockWebViewHelper.wrapClient(), which
        // adds shouldInterceptRequest with randomized responses to avoid
        // FreeWheel fraud detection.

        // 1-arg <init>(Context) — instruction index 56, register v1.
        XtvClientWrapFingerprint.method.addInstructions(
            56,
            """
                invoke-static {v1}, Lajstrick81/morphe/extension/peacock/ads/PeacockWebViewHelper;->wrapClient(Landroid/webkit/WebViewClient;)Landroid/webkit/WebViewClient;
                move-result-object v1
            """.trimIndent(),
        )

        // 2-arg <init>(Context, AttributeSet) — the constructor actually
        // invoked when inflating VirtualDpadXTVWebView from
        // activity_main.xml. Instruction index 56, register v0.
        XtvClientWrapTwoArgFingerprint.method.addInstructions(
            56,
            """
                invoke-static {v0}, Lajstrick81/morphe/extension/peacock/ads/PeacockWebViewHelper;->wrapClient(Landroid/webkit/WebViewClient;)Landroid/webkit/WebViewClient;
                move-result-object v0
            """.trimIndent(),
        )

        // 3-arg <init>(Context, AttributeSet, int) — instruction index 57,
        // register p3.
        XtvClientWrapThreeArgFingerprint.method.addInstructions(
            57,
            """
                invoke-static {p3}, Lajstrick81/morphe/extension/peacock/ads/PeacockWebViewHelper;->wrapClient(Landroid/webkit/WebViewClient;)Landroid/webkit/WebViewClient;
                move-result-object p3
            """.trimIndent(),
        )

        // ── Layer 8 ─────────────────────────────────────────────────────────
        // Sky SDK FreeWheel DI module surgical removal.
        //
        // AddonInjectorImpl.di$lambda$0() is the Kodein DI wiring method that
        // imports all addon modules into the player container. The full module
        // import sequence is:
        //   coreAddonModule, coroutinesModule, contentProtectionModule,
        //   eventBoundaryModule, videoAdsConfigModule, mediaTailorModule,
        //   freewheelModule,  ← indices 16-17: iget-object + import$default
        //   networkApiModule, urlEncoder, platformAddonModule, lateBindingAddonModule
        //
        // Removing indices 16 and 17 prevents FreeWheel from ever being
        // registered in the DI container. The player has no FreeWheel addon:
        //   - No VMAP ad break schedule fetched
        //   - No VAST ad creative requested
        //   - No impression/quartile/completion pixels fired
        //   - No ad segments fetched or buffered
        //
        // This is the Sky SDK equivalent of Layer 4 (getSsaiConfigurationProvider
        // → null) but targeting CSAI/FreeWheel rather than SSAI/MediaTailor.
        // Works identically for VOD and live TV since the same DI wiring is
        // used for all content types — live TV simply never had a FreeWheel
        // module to begin with so removal is a no-op there.
        //
        // Note: removeInstruction is called twice on index 16 because after
        // the first removal index 17 shifts down to become index 16.
        FreewheelModuleSkipFingerprint.method.apply {
            removeInstruction(16) // iget-object v0, freewheelModule
            removeInstruction(16) // import$default(...) — now shifted to 16
        }
    }
}
