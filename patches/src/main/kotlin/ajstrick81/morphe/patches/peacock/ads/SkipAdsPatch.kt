package ajstrick81.morphe.patches.peacock.ads

import ajstrick81.morphe.patches.peacock.shared.Constants
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Disables ad delivery via Sky SDK surgical targets (FreeWheel DI module " +
        "skip, MediaTailor SSAI layers, ad-break-started no-op), AdBlockInterceptor wiring " +
        "on both the app NetworkingKt OkHttp client and the Sky SDK addon network client, " +
        "and a WebView shouldInterceptRequest wrapper. Validated v7.5.102 and v7.6.100.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    // extendWith populates Patch.extensionInputStream — without this call the
    // PeacockAdPatchHelper/PeacockWebViewHelper/AdBlockInterceptor classes
    // referenced below via invoke-static never get merged into the patched
    // APK's dex, even though the smali calling them assembles fine.
    extendWith("extensions/extension.mpe")

    execute {
        // Locates the setWebViewClient(...) call inside one of XTVWebView's
        // constructors and injects a wrapClient() call immediately before it,
        // reassigning whatever register holds the client. Finding the index
        // and register dynamically (rather than trusting a hardcoded offset)
        // matters here specifically: v7.5.102 -> v7.6.100 dropped the
        // maxRendererCrashes/rendererCrashWindowMs field assignments from all
        // three constructors, shifting setWebViewClient's instruction index
        // by -4 in every one of them (56->52, 56->52, 57->53) while leaving
        // the holding register unchanged. A fixed-offset injection would have
        // silently landed 4 instructions late on v7.6.100, re-breaking
        // Layer 7 the same way the original single-overload version of this
        // patch did. Declared local to this block since Fingerprint.method
        // requires the BytecodePatchContext that only execute{} provides.
        fun wrapXtvClientSetter(fingerprint: Fingerprint) {
            val method = fingerprint.method
            val instructions = method.implementation!!.instructions
            val setClientIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)?.name == "setWebViewClient"
            }
            val clientRegister = (instructions[setClientIndex] as FiveRegisterInstruction).registerD
            val totalRegisters = method.implementation!!.registerCount
            val paramRegisters = method.parameters.size + 1 // +1 for the implicit `this` (p0)
            val firstParamRegister = totalRegisters - paramRegisters
            val registerName = if (clientRegister >= firstParamRegister) {
                "p${clientRegister - firstParamRegister}"
            } else {
                "v$clientRegister"
            }

            method.addInstructions(
                setClientIndex,
                """
                    invoke-static {$registerName}, Lajstrick81/morphe/extension/peacock/ads/PeacockWebViewHelper;->wrapClient(Landroid/webkit/WebViewClient;)Landroid/webkit/WebViewClient;
                    move-result-object $registerName
                """.trimIndent(),
            )
        }

        // Finds the single okhttp3.OkHttpClient$Builder.build() call in the
        // matched method and injects PeacockAdPatchHelper.addAdBlockInterceptor()
        // immediately before it, reusing the builder's own register. Used for
        // every OkHttpClient that is *constructed* (rather than body-replaced
        // like Layer 6's getOkHttpClient): the Sky SDK addon client (Layer 9)
        // and the SDK root client (Layer 11). Locating the build() and its
        // register dynamically — rather than via a fixed offset — keeps this
        // stable across register-allocation and field-layout drift between
        // versions, the same resilience rationale as wrapXtvClientSetter above.
        fun injectAdBlockBeforeOkHttpBuild(fingerprint: Fingerprint) {
            fingerprint.method.apply {
                val instructions = implementation!!.instructions
                val buildIndex = instructions.indexOfFirst { instruction ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)?.let { ref ->
                            ref.name == "build" && ref.definingClass == "Lokhttp3/OkHttpClient\$Builder;"
                        } == true
                }
                val builderRegister = (instructions[buildIndex] as FiveRegisterInstruction).registerC
                val totalRegisters = implementation!!.registerCount
                val paramRegisters = parameters.size + 1 // +1 for implicit `this` (p0)
                val firstParamRegister = totalRegisters - paramRegisters
                val registerName = if (builderRegister >= firstParamRegister) {
                    "p${builderRegister - firstParamRegister}"
                } else {
                    "v$builderRegister"
                }

                addInstructions(
                    buildIndex,
                    """
                        invoke-static {$registerName}, Lajstrick81/morphe/extension/peacock/ads/PeacockAdPatchHelper;->addAdBlockInterceptor(Lokhttp3/OkHttpClient${'$'}Builder;)Lokhttp3/OkHttpClient${'$'}Builder;
                        move-result-object $registerName
                    """.trimIndent(),
                )
            }
        }

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
        // FreeWheel fraud detection. The injection point and register are
        // located dynamically per-constructor (see wrapXtvClientSetter) so
        // this stays correct across versions even as field layout shifts.
        wrapXtvClientSetter(XtvClientWrapFingerprint)
        wrapXtvClientSetter(XtvClientWrapTwoArgFingerprint)
        wrapXtvClientSetter(XtvClientWrapThreeArgFingerprint)

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

        // ── Layer 9 ─────────────────────────────────────────────────────────
        // NativeNetworkApi.<init> derives its own child OkHttpClient via
        // newBuilder()/build() — the Sky SDK addon network path (FreeWheel ad
        // decisioning, Conviva/Comscore/Nielsen measurement, MediaTailor
        // telemetry) that Layer 6 never touches. Add AdBlockInterceptor to it.
        //
        // On-device logcat from a v1.5.7 (Layers 1-8 + fixed Layer 7 only)
        // build confirmed this layer is necessary, not just defense-in-depth:
        // FreeWheel's full ad-slot lifecycle (defaultImpression -> firstQuartile
        // -> midPoint -> thirdQuartile -> complete -> slotEnd) fired across
        // five consecutive ad pods over ~3 minutes, meaning the ad creative
        // itself played to completion even with Layer 8's FreeWheel DI-module
        // removal in place. Layer 7 only caught the *beacon* pings to
        // fwmrm.net/omtrdc.net/conviva.com after the fact — it never had a
        // chance to block the ad decisioning/creative fetch, which travels
        // over this NativeNetworkApi client instead. Re-added without Layers
        // 10/11 (still unverified) so this one addition can be device-tested
        // in isolation.
        injectAdBlockBeforeOkHttpBuild(NativeNetworkApiConstructorFingerprint)

        // NOTE: SdkRootOkHttpClientFingerprint (Layer 11) and
        // NewRelicInitFingerprint (Layer 10) remain intentionally absent —
        // see the rollback note this layer's reintroduction replaces. They
        // stay out until each is device-verified on its own.
    }
}
