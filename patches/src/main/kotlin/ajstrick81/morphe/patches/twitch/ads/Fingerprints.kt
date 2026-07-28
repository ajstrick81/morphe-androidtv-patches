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
    strings = listOf("PlaybackAccessTokenParams(playerType=")
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

// Layer 3 — GrandDadsApiImpl.shouldDeclineAds (version-pinned)
//
// This is the method Purple TV's AdBlocker short-circuits. In v30.2.2,
// it lives at Lhs9; method f (obfuscated). Because the Morphe framework's
// Fingerprint.method requires an execute{} context for dynamic resolution,
// and cross-fingerprint references don't compile outside that context, this
// fingerprint is version-pinned to the exact obfuscated names.
//
// When the app updates and class/method names change, this fingerprint
// will need to be re-derived from a fresh decompilation.
object DeclineAdsRequestFingerprint : Fingerprint(
    definingClass = "Lhs9;",
    name = "f",
    returnType = "Lio/reactivex/rxjava3/core/Single;"
)
