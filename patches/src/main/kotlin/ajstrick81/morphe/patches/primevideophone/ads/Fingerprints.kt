package ajstrick81.morphe.patches.primevideophone.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// AdManifest.createAdManifest(List<VmapAdBreak>, AdHttpClient) : AdManifest
// classes3.dex / smali/com/amazon/avod/ads/api/AdManifest.smali
//
// The client-side (VMAP / CSAI) ad-manifest builder in Amazon's shared video-ads
// code (com.amazon.avod.ads.*). It takes the parsed list of VMAP ad breaks and
// constructs the AdManifest the player uses to schedule client-side ads. This is
// a Java-reachable ad-TIMELINE accessor — the seam the ad-timeline methodology
// targets (docs/JAVA_AD_TIMELINE_HOOK_METHODOLOGY.md).
//
// A community find sabotaged this by forcing the list-size register to -1 so
// Lists.newArrayListWithCapacity(-1) throws IllegalArgumentException (crashes the
// builder). We instead empty the incoming break list, so the builder produces a
// valid AdManifest with ZERO breaks — same ad-kill, no exception.
//
// NOTE: this covers only the VMAP/CSAI path. PV's main SSAI (server-stitched)
// ads ride the media3/native road handled elsewhere; this is complementary and
// most relevant to VMAP-delivered ads (e.g. Freevee-style / certain live).
// ─────────────────────────────────────────────────────────────────────────────
object CreateAdManifestFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/avod/ads/api/AdManifest;",
    name = "createAdManifest",
    parameters = listOf(
        "Ljava/util/List;",
        "Lcom/amazon/avod/ads/http/AdHttpClient;"
    ),
    returnType = "Lcom/amazon/avod/ads/api/AdManifest;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

// ─────────────────────────────────────────────────────────────────────────────
// AdManagerBasedAdPlan.getBreaks() : List<AdBreak>
// classes5.dex / smali/com/amazon/avod/media/ads/internal/AdManagerBasedAdPlan.smali
//
// THE central ad-break TIMELINE accessor for the phone app — the Prime Video
// analog of Disney's com.disney.dmp Insertion.getPoints() (see
// docs/JAVA_AD_TIMELINE_HOOK_METHODOLOGY.md and [[disney-sgai-ad-investigation]]).
// The player's ad state machine reads this List<AdBreak> to schedule ads.
//
// Crucially this is the BASE class: the SSAI plan
// (ServerInsertedAdManagerBasedAdPlan) overrides getBreaks() with only a
// `invoke-super` bridge, so emptying THIS one accessor covers BOTH the
// server-side (SSAI) and client-side ad plans in a single hook. Amazon ships an
// EmptyAdPlan class, confirming "zero breaks" is a valid, crash-safe state.
//
// Original body is a trivial getter: iget mBreaks -> unmodifiableList -> return.
// We prepend an empty-list return so the player sees no ad breaks at all — the
// canonical "empty the Java ad-timeline accessor" technique.
// ─────────────────────────────────────────────────────────────────────────────
object AdPlanGetBreaksFingerprint : Fingerprint(
    definingClass = "Lcom/amazon/avod/media/ads/internal/AdManagerBasedAdPlan;",
    name = "getBreaks",
    parameters = emptyList(),
    returnType = "Ljava/util/List;",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
