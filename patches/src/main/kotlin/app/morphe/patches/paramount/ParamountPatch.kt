/*
 * Paramount+ Android TV — Ad Suppression Patch
 *
 * Validated against:
 *   v16.8.0  (versionCode 520000464) — com.cbs.ott  [empty-request era, see history]
 *   v16.12.0 (versionCode 520000571) — com.cbs.ott
 *   v16.17.0 (versionCode 520000758) — com.cbs.ott  [current mechanism]
 *   v16.19.0 (versionCode 520000827) — com.cbs.ott  [fingerprints re-verified;
 *                                       all three targets unchanged vs 16.17.0]
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
 *
 * LIVE SPORTS ADS (v16.17.0, on-device verified 2026-08-16 — Patch 3):
 *   Live ads can't be seeked past (no content past the live edge), so the
 *   shouldPlayAd gate doesn't remove them. They are Google DAI pod-serving —
 *   separately-addressable ad segments — and the same pod exposes a "slate"
 *   rendition (Paramount's "Commercial in Progress" branded card). Patch 3
 *   rewrites each ad-pod request at the AviaNetworkInterceptor okhttp seam to
 *   that slate rendition. On a real live sports break the card renders and the
 *   stream recovers cleanly to the game; content requests are untouched. Full
 *   slate segment coverage confirmed on-device across multiple breaks.
 */

package app.morphe.patches.paramount

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

@Suppress("unused")
val paramountPatch = bytecodePatch(
    name = "Paramount+ Android TV",
    description = "Removes VOD pre-roll/mid-roll ads and pause ads, and replaces live sports " +
        "commercial breaks with the \"Commercial in Progress\" slate, while preserving playback.",
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

        // ------------------------------------------------------------------
        // Patch 3: LIVE ad slate — AviaNetworkInterceptor.intercept(Chain)
        //
        // Live sports ads can't be skipped by seeking (no content past the live
        // edge), so shouldPlayAd (Patch 1) doesn't cover them. But live ads are
        // Google DAI "pod serving": separately-addressable ad segments at
        // dai.google.com/linear/pods/v1/.../<pod>/<slot>/<adIdx>/<hash>/N.ts|.aac
        // that 302-redirect to googlevideo ad media. The SAME pod also exposes a
        // "slate" rendition (/<pod>/slate/0/<hash>/N...) — Paramount's own server
        // -served "Commercial in Progress" branded card, a real segment carrying
        // correct live-timeline PTS.
        //
        // This prepends a request diverter at intercept() entry: for an ad-pod
        // request (path contains "/linear/pods/v1/" and not already "/slate/"),
        // rewrite the pod/slot/adIdx path segment to "slate/0" and proceed() with
        // the rewritten request, returning that Response. All non-ad requests
        // (content manifests + segments) fall through to the original interceptor
        // body untouched (CMCD headers etc. preserved). Exactly one proceed() per
        // path — required: this is a NETWORK interceptor.
        //
        // On-device verified (v16.17.0, live sports break): the branded card
        // renders and the stream recovers cleanly to the game. Full slate segment
        // coverage observed (every 0..N segment resolves).
        //
        // Slate duration (smoothness): a slate segment request needs a "?d=<ms>"
        // duration hint so the DAI endpoint returns a slate chunk that fills the
        // timeline slot it replaces. Only the *tail* segment of a pod carries its
        // own "?d=" (the leading full-length segments have no query), so those
        // leading segments must be given a duration. A hardcoded value drifts if
        // an event's pod uses a different segment length than the one it was
        // measured on -> per-segment gap -> slate stutter. Instead this SELF-
        // CORRECTS: whenever an ad-pod URL carries a real "?d=<n>", n is cached
        // via System property "morphe.slate.d" (guarded by a digits check so a
        // non-match never poisons it); query-less leading segments then append
        // "?d=" + that learned value. Until a real value is seen it falls back to
        // "4972" (the original constant), so the worst case is byte-identical to
        // the previously-verified behavior -- it can only match the event's true
        // segment length better, never worse. The "?" check preserves the tail's
        // own query untouched (no malformed "?d=..?d=.." double query, which the
        // DAI endpoint 400s).
        //
        // Register note: intercept() carries the Chain in a high param register
        // (p1), so it is moved to v0 (move-object/from16) before any invoke — a
        // bare invoke-interface {p1} would exceed the 4-bit argument-register
        // limit. v0..v3 are scratch; they are dead once we fall through to the
        // original body (which re-initializes its own registers), matching the
        // ":morphe_continue nop" fall-through idiom.
        //
        // Smoothness note (v1.30.2 — reverted the PTS-align hot-path): v1.30.1 tried to
        // smooth the slate by PTS-aligning it in-interceptor via the MorpheTsRewriter
        // extension — it proceeded the slate request, then SYNCHRONOUSLY out-of-band
        // fetched the slate media (up to 16MB) + a real-ad head and byte-rewrote PTS/PCR
        // on the okhttp call thread the player was blocked on. That validated smooth on a
        // fast Onn 4K + LAN, but on weaker hardware / slower networks (tester report,
        // issue #91: TiVo Stream 4K) the added serialized latency starved the player
        // pipeline → AVIA watchdog bailed to the profile-selection screen at EVERY break
        // (a hard regression vs the merely-choppy v1.29.2). The null-return failsafe only
        // covered exceptions, not "succeeded but too slow", so slowness had no safety net.
        // Until the smoother is re-architected OFF the hot path (warm the slate cache
        // async at break start + a per-segment deadline fallback), this reverts to the
        // proven pure URL-redirect: proceed the rewritten slate request and return its 302
        // so okhttp follows it to the slate media (v1.29.2 behavior — choppy on segment
        // boundaries, but plays on all hardware, never crashes, never real ads). The
        // MorpheTsRewriter extension + keep-rule are retained unused for that rework.
        //
        // Register note: intercept() carries the Chain in a high param register (p1),
        // moved to v0 first. v1 = ad URL, v4 = slate URL, v5 = proceeded response;
        // v2/v3 scratch. Non-ad requests fall through via ":morphe_continue nop".
        // ------------------------------------------------------------------
        AviaNetworkInterceptorFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                invoke-interface {v0}, Lokhttp3/Interceptor${'$'}Chain;->request()Lokhttp3/Request;
                move-result-object v1
                invoke-virtual {v1}, Lokhttp3/Request;->url()Lokhttp3/HttpUrl;
                move-result-object v1
                invoke-virtual {v1}, Lokhttp3/HttpUrl;->toString()Ljava/lang/String;
                move-result-object v1
                const-string v2, "/linear/pods/v1/"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v2
                if-eqz v2, :morphe_continue
                const-string v2, "/slate/"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v2
                if-nez v2, :morphe_continue
                const-string v2, "^.*[?&]d=([0-9]+).*${'$'}"
                const-string v3, "${'$'}1"
                invoke-virtual {v1, v2, v3}, Ljava/lang/String;->replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                const-string v3, "[0-9]+"
                invoke-virtual {v2, v3}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v3
                if-eqz v3, :morphe_learned
                const-string v3, "morphe.slate.d"
                invoke-static {v3, v2}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                :morphe_learned
                const-string v2, "/([0-9]+)/([0-9]+)/([0-9]+)/([0-9a-fA-F]{32})/"
                const-string v3, "/${'$'}1/slate/0/${'$'}4/"
                invoke-virtual {v1, v2, v3}, Ljava/lang/String;->replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v4
                const-string v2, "?"
                invoke-virtual {v4, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v2
                if-nez v2, :morphe_haveq
                new-instance v2, Ljava/lang/StringBuilder;
                invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
                invoke-virtual {v2, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v3, "?d="
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v3, "morphe.slate.d"
                const-string v5, "4972"
                invoke-static {v3, v5}, Ljava/lang/System;->getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v3
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v4
                :morphe_haveq
                invoke-interface {v0}, Lokhttp3/Interceptor${'$'}Chain;->request()Lokhttp3/Request;
                move-result-object v2
                invoke-virtual {v2}, Lokhttp3/Request;->newBuilder()Lokhttp3/Request${'$'}Builder;
                move-result-object v2
                invoke-virtual {v2, v4}, Lokhttp3/Request${'$'}Builder;->url(Ljava/lang/String;)Lokhttp3/Request${'$'}Builder;
                move-result-object v2
                invoke-virtual {v2}, Lokhttp3/Request${'$'}Builder;->build()Lokhttp3/Request;
                move-result-object v2
                invoke-interface {v0, v2}, Lokhttp3/Interceptor${'$'}Chain;->proceed(Lokhttp3/Request;)Lokhttp3/Response;
                move-result-object v5
                return-object v5
                :morphe_continue
                nop
            """.trimIndent(),
        )
    }
}
