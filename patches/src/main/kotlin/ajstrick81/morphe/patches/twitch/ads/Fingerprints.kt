/*
 * Attribution:
 *
 * The ad-suppression strategy used in this patch is informed by techniques
 * from several open-source projects. Specific credit:
 *
 * - Purple TV (nyanarchive/purpletv, https://github.com/AdrianLxM/PurpleTV)
 *   The GrandDads ad-eligibility short-circuit approach was discovered by
 *   reverse-engineering Purple TV's AdBlocker class (decompiled from
 *   PurpleTV_2.4.apk). Purple TV's shipped technique short-circuits
 *   GrandDadsApiImpl.shouldDeclineAds to return AdContextUnavailable
 *   before any network call, gated by a DISABLE_AD_ELIGIBILITY flag.
 *   Credit to the Purple TV developers for identifying this as the
 *   decisive ad-eligibility choke point.
 *
 * - TwitchAdSolutions (pixeltris/TwitchAdSolutions,
 *   https://github.com/pixeltris/TwitchAdSolutions)
 *   The playerType spoofing concept — substituting the default
 *   mobile_player token type with an alternative that receives fewer
 *   or no ads — originates from TwitchAdSolutions' proxy/playlist
 *   rewriting techniques. The specific playerType values and their
 *   ad-reduction effects were first documented by that project.
 *
 * - Xtra for Twitch (crackededed/Xtra, https://github.com/crackededed/Xtra)
 *   Referenced as prior art for Android-side Twitch ad mitigation
 *   approaches and playerType enumeration.
 *
 * All techniques were independently re-derived against the official
 * Twitch Android APK v30.2.2 (versionCode 3002026) via dex disassembly,
 * not copied from any project's source code. The fingerprints and smali
 * patches below are original work targeting obfuscated class/method names
 * specific to this build.
 */
package ajstrick81.morphe.patches.twitch.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// ===========================================================================
// Twitch Android — ad architecture (confirmed by dex disassembly of v30.2.2)
//
// Twitch uses Amazon IVS Player for live playback. Ad decisioning is NOT
// reactive to player events (AdBreak/AdCue callbacks are no-ops). Instead,
// two mechanisms control ad delivery:
//
// 1. StreamAccessTokenQuery (GQL) — requests a playback access token from
//    Twitch's API. The token carries a `playerType` field (enum Lr440 in
//    this build) that influences server-side ad-fill decisions. Values
//    include mobile_player (default for live), background_audio, autoplay,
//    prime_video_player, etc.
//
// 2. GrandDads GQL query — a per-session ad-eligibility/decisioning call
//    that determines dynamic ad rotation. Call chain:
//      VideoAdManager → AdEligibilityFetcher/ClientAdEligibilityFetcher
//        → GrandDadsFetcher → GrandDadsApiImpl.shouldDeclineAds
//        → GrandDadsQuery
//    Short-circuiting shouldDeclineAds to return AdContextUnavailable
//    before the network call prevents the app from ever requesting ads.
//
// Patching both layers provides defense-in-depth: playerType spoofing
// reduces server-side ad fill, while the GrandDads block prevents the
// client from requesting ad eligibility at all.
// ===========================================================================

// Layer 1 — playerType spoofing on StreamAccessTokenQuery
//
// The PlaybackAccessTokenParams class is constructed with a playerType
// value from enum Lr440. Its toString() includes the literal string
// "PlaybackAccessTokenParams" which survives R8 minification (it's a
// data-class generated toString, not a debug string). We anchor on that
// toString() in a sibling method to locate the constructor, then patch
// the playerType argument.
//
// This fingerprint targets the toString() method to locate the class,
// not the constructor directly — the constructor's signature is generic
// enough that it could false-match without the string anchor.
object PlaybackAccessTokenParamsToStringFingerprint : Fingerprint(
    strings = listOf("PlaybackAccessTokenParams(disableHTTPS=")
)

// Layer 2 — GrandDads ad-eligibility query document
//
// Apollo-codegen embeds the full GQL document string as a static field
// in the generated query class. This string is stable across builds
// because it's derived from the GraphQL schema, not from R8 renaming.
// We use it to locate the GrandDads query class reliably.
object GrandDadsQueryDocumentFingerprint : Fingerprint(
    strings = listOf("query GrandDads")
)

// Layer 2 (real) — GrandDads ad-eligibility RESPONSE MAPPER.
//
// The decisive ad-eligibility choke point. Traced 2026-07-30 from the only
// R8-stable anchor, the "query GrandDads" document string:
//   "query GrandDads" ── document() of Li0i (GrandDadsQuery)
//   Li0i is constructed in exactly ONE place: Lhs9;->f (the query builder;
//     runs when the shared Lhs9 dispatch class is invoked with tag a=0x18).
//   Lhs9;->f calls the gql service Ld2i;->l(query, mapper, …) passing the
//     response mapper `new Lwyh(0x4)` (a Lt5h; functor).
//   That mapper is Lwyh;->invoke, tag-4 branch: it casts the gql response to
//     Lg0i (GrandDadsQuery.Data) and returns a GrandDadsResponse (sealed base
//     Lp0i): if the decision node Lg0i;->a is null → Ln0i (AdContextUnavailable
//     singleton, INSTANCE = Ln0i;->a:Ln0i;), else Lo0i (ads-available).
//
// SkipAdsPatch forces this mapper to ALWAYS return the AdContextUnavailable
// sentinel Ln0i;->a — the obfuscated analog of Purple TV's
// shouldDeclineAds → GrandDadsResponse.AdContextUnavailable.INSTANCE.
//
// Scope safety: the tag-4 branch begins `check-cast p1, Lg0i;`, so any
// non-GrandDads tag-4 use of Lwyh would already ClassCastException — proving
// tag-4 is GrandDads-exclusive and the guard is correctly scoped.
//
// This is a v30.2.2 obfuscated pin (Lwyh/Ln0i/tag names change per app
// version). The `custom` predicate self-verifies the match by requiring the
// method to reference the Ln0i sentinel, so a drifted pin fails to match
// (patch skipped) rather than mis-applying. Re-derive via the chain above.
object GrandDadsResponseMapperFingerprint : Fingerprint(
    definingClass = "Lwyh;",
    name = "invoke",
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        method.implementation?.instructions?.any { insn ->
            (insn as? ReferenceInstruction)?.reference?.toString()?.contains("Ln0i;") == true
        } == true
    }
)

// Layer 3 (real SSAI kill) — ExoPlayer stitched-ad metadata parser.
//
// The decisive on-device seam. Twitch live playback on Android TV runs on
// ExoPlayer2 (NOT sealed native IVS): the player core
// `tv.twitch.android.shared.player.core.b` (unobfuscated package) receives
// timed HLS metadata via its onMetadata callback (method obfuscated to `G`,
// param `com.google.android.exoplayer2.metadata.Metadata`). That method walks
// each `#EXT-X-DATERANGE` tag, reads the tag's `CLASS` attribute, and does
// `"twitch-stitched-ad".equals(class)` → a boolean. When true it enters ad
// handling (parses the X-TV-TWITCH-AD-* metadata, dispatches an ad event to
// the ad manager, fires quartile beacons); when false it takes the player's
// own built-in "no ads" path.
//
// SkipAdsPatch forces that boolean false so onMetadata always takes the no-ad
// path — the client-side analog of the TwitchAdSolutions stitched-ad strip.
//
// Fingerprint is version-durable: the class name is unobfuscated and the two
// strings are HLS/Twitch protocol constants that don't change with R8. The
// injection point (the equals→move-result) is located dynamically in the
// patch, not by a fixed offset, so register/instruction drift doesn't break it.
object StitchedAdMetadataFingerprint : Fingerprint(
    definingClass = "Ltv/twitch/android/shared/player/core/b;",
    strings = listOf("#EXT-X-DATERANGE", "twitch-stitched-ad"),
)

// NOTE (Path B / join pre-roll — investigated, not shippable): a fourth layer
// that strips ad segments from the live HLS media playlist was prototyped
// against ExoPlayer's HlsPlaylistParser (R8-renamed `Lxsi;`). On-device it was
// proven INEFFECTIVE: a hook injected at that parser's parse() entry never
// fired during live playback (0 calls across a full join with an ad), so Twitch
// live does NOT route through ExoPlayer's stock HLS parser — it feeds ExoPlayer
// via a custom/native IVS media source whose playlist parsing bytecode cannot
// reach. The residual ~15s pre-roll therefore can't be stripped at the Java
// playlist layer. See the twitch-ad-suppression handoff memory for the full
// trace before re-attempting.
