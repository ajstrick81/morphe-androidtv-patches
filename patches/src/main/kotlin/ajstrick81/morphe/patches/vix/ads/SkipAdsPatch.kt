package ajstrick81.morphe.patches.vix.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.vix.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.iface.Method

@Suppress("unused")
val skipAdsPatch = bytecodePatch(
    name = "Skip ads",
    description = "Suppresses ViX ad delivery by emptying the LuraPlayer ad-break schedule — " +
        "both the client-side VMAP breaks (third-party ads) and the first-party house / " +
        "cross-promo breaks — so no ad-periods are inserted into the timeline, by removing " +
        "the ad periods the server stitches into on-demand stream manifests, and by " +
        "preventing the Innovid SSAI ad overlay from mounting.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    extendWith("extensions/extension.mpe")

    execute {

        // ─────────────────────────────────────────────────────────────────────
        // Hook 1 — empty the client-VMAP ad-break schedule at ingestion
        //
        // ViX serves VOD ads client-side (provider "generic"): the VMAP is
        // fetched from the GAM ad unit, parsed, and delivered to the player as a
        // LuraEvent carrying an InterstitialsData payload. getBreaks() is the
        // List<LuraAdBreak> schedule; the player state machine (c5) ingests it
        // (getData → instanceof InterstitialsData → getBreaks) and stores it,
        // after which media3 inserts those breaks as timeline ad-periods.
        //
        // Returning an EMPTY list empties the schedule at its single ingestion
        // point, so no ad-periods are inserted and content plays through
        // seamlessly. Empty, NOT null: the ingestion site skips storing on a
        // null (leaving a prior schedule intact) but ingests-empty on an empty
        // list. This is the "empty the ad DATA, not the flow the player waits
        // on" rule — content playback runs on a separate path, untouched.
        //
        // Device-proven dead ends this replaces: the LuraGam*.getAdTagUrl()
        // config getters are never read at request time (emptied to "" and the
        // VMAP still fetched), and LuraAdPingExecutor / the quartile reporters
        // fire downstream of delivery (ad still played). getBreaks() lives in
        // the retained public api/event package (R8-stable) and sits upstream of
        // the schedule store.
        //
        // (Live SSAI ads are Google DAI Pod Serving, stitched server-side and
        // out of scope for bytecode — see the "Override certificate pinning"
        // patch and docs/adguard/vix_ssai_v1_0_agp.txt.)
        //
        // methodOrNull so a drift on one target version degrades gracefully
        // instead of aborting the whole patch (incl. Innovid) — per the repo
        // rule learned on Disney 26.16 / Prime v16.
        // ─────────────────────────────────────────────────────────────────────
        InterstitialsDataGetBreaksFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
            """
        )

        // ─────────────────────────────────────────────────────────────────────
        // Hook 1b — drop server-stitched ad periods from the VOD DASH manifest
        //
        // Device-traced on 4.52.0_tv: on-demand streams arrive from the Lura
        // platform already stitched. The multi-period manifest
        // (dcs4-vod.mp.lura.live/vod/p/manifest.mpd) carries unencrypted ad
        // periods (segments on dcs110-mcdn.mp.lura.live) ahead of and between
        // the Widevine content periods. No client ad request exists to block,
        // and the schedule hooks below only hide the markers — the ~50 s
        // pre-roll still played.
        //
        // AdPeriodFilter.filter() rewrites the manifest text before media3
        // parses it: periods without ContentProtection are removed (only when
        // other periods in the same static manifest have it) and the remaining
        // periods are re-timed so the timeline stays contiguous. With the
        // schedule emptied below, the SDK and the player then agree there are
        // no ads. Any doubt or error returns the manifest untouched.
        //
        // DashManifestParser keeps its public method names, so the anchor is
        // parse(Uri, InputStream) on the class that owns the MPD error string.
        // ─────────────────────────────────────────────────────────────────────
        DashManifestParseFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {p2}, Lajstrick81/morphe/extension/vix/ads/AdPeriodFilter;->filter(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p2
            """
        )

        // ─────────────────────────────────────────────────────────────────────
        // Hook 2 — empty every ad-break list getter on the player state holder
        //
        // The ad-break schedule lives on the player state object (R8 name j4) in
        // four List<ad-break> fields, exposed by four no-arg getters (R8 names
        // c/d/q/s). These are the TRUE bottleneck: the schedule aggregator (c5),
        // the media3 player (f5) via c5.p()/q(), AND the live-splice/scheduler
        // consumers (b5, h5.j → ExoPlayer.replaceMediaItem, p3) all read these
        // j4 getters — several of them DIRECTLY, bypassing the c5-level
        // delegates. (An earlier build emptied only the c5 delegates; device
        // logcat showed mid-roll / house-promo pods still inserted via the direct
        // j4 reads — hence hooking j4 itself.) q() and s() are the VOD and
        // live/pod break lists; c() and d() are the other two ad-break lists.
        // Emptying all four removes every ad category — third-party pre/mid-roll
        // AND ViX first-party house/cross-promos — at the single lowest level.
        //
        // j4 is R8-obfuscated but uniquely identified by its API-stable getters
        // returning LuraEventData$TimeUpdate, LuraTracks and LuraType (the
        // luraplayer/api types survive R8). The four ad-break getters are the
        // no-arg List getters whose generic element is an obfuscated
        // Lcom/akta/lura/<d2>; type; the sole content List getter (element
        // Lcom/akta/luraplayer/api/data/content/LuraMedia;) is excluded by that
        // package test, so media playback is untouched. Returning an empty list
        // leaves no breaks to schedule, aggregate or splice.
        //
        // mutableClassDefByOrNull + the generic-element filter so a miss or a
        // shape change on some version degrades gracefully instead of aborting
        // the patch. (Hook 1 still empties the InterstitialsData event at
        // ingestion as an API-stable first line of defence.)
        // ─────────────────────────────────────────────────────────────────────
        // element is the obfuscated impl package (ad-break d2); luraplayer/api = content.
        fun returnsAdBreakList(method: Method): Boolean {
            val sig = method.annotations.firstOrNull { it.type == "Ldalvik/annotation/Signature;" } ?: return false
            val value = sig.elements.firstOrNull { it.name == "value" }?.value as? ArrayEncodedValue ?: return false
            val joined = value.value.filterIsInstance<StringEncodedValue>().joinToString("") { it.value }
            return joined.contains("Lcom/akta/lura/") && !joined.contains("Lcom/akta/luraplayer/")
        }

        // The ad-break schedule holder j4 exposes its four List<d2> fields as PURE
        // field getters (iget-object + return-object, no invoke). The state machine
        // c5 shares the same API-stable getters (TimeUpdate/LuraTracks/LuraType) but
        // its List<d2> getters DELEGATE to j4 (they contain invoke) — so requiring a
        // pure-field ad-break getter uniquely selects j4 and not c5. (An earlier
        // build without this check matched c5 and emptied only its delegates, which
        // the direct j4 readers b5/h5/p3 bypassed — device-proven leak.)
        val invokeOpcodes = setOf(
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT,
            Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
            Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE,
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE,
        )
        fun Method.isPureAdBreakFieldGetter(): Boolean =
            parameters.isEmpty() &&
                returnType == "Ljava/util/List;" &&
                returnsAdBreakList(this) &&
                implementation?.instructions?.let { insns ->
                    insns.any { it.opcode == Opcode.IGET_OBJECT } &&
                        insns.none { it.opcode in invokeOpcodes }
                } == true

        mutableClassDefByOrNull { classDef ->
            fun hasNoArgReturning(type: String) = classDef.methods.any { m ->
                m.parameters.isEmpty() && m.returnType == type
            }
            hasNoArgReturning("Lcom/akta/luraplayer/api/event/LuraEventData\$TimeUpdate;") &&
                hasNoArgReturning("Lcom/akta/luraplayer/api/models/LuraTracks;") &&
                hasNoArgReturning("Lcom/akta/luraplayer/api/event/LuraType;") &&
                classDef.methods.any { it.isPureAdBreakFieldGetter() }
        }?.methods
            ?.filter { it.isPureAdBreakFieldGetter() }
            ?.forEach { adBreakGetter ->
                adBreakGetter.addInstructions(
                    0,
                    """
                        invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                        move-result-object v0
                        return-object v0
                    """
                )
            }

        // ─────────────────────────────────────────────────────────────────────
        // Hook 3 — InnovidHelper mount method (boolean, WebView, VideoModel, Flow)
        // (obfuscated name: h on 4.46/4.47, u on 4.50.1 — matched by anchors, not name)
        //
        // The entry point that mounts the Innovid SSAI ad WebView overlay
        // (carries the "innovidAd" model; dispatched from the player's
        // ad-event switch in videoplayer/z.smali). Returning void before the
        // first instruction stops the session before any WebView is attached
        // or network request is made. Safe to stub at index 0 — this is an
        // ordinary instance method, not a constructor.
        // ─────────────────────────────────────────────────────────────────────
        InnovidStartAdFingerprint.method.addInstructions(
            0,
            """
                return-void
            """
        )
    }
}
