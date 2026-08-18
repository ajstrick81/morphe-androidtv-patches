package app.morphe.patches.hbomax.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Block SSAI Ad Origins  (OPT-IN, default OFF, EXPERIMENTAL)
//
// Reproduces, inside the standalone patched APK, what the community AdGuard Home
// DNS rule-lists do: strip HBO Max's ads COMPLETELY, not just their markers.
//
// WHY THIS EXISTS (the ceiling the default "Disable Ads" patch hits):
//   The default patch empties the parsed ad-break TIMELINE (Bolt / GMSS-AdSparx /
//   live-timeline / Nowtilus) — it removes ad markers, skip UI, and beacons. But
//   on the ad-supported ("ad_light") tier the ad VIDEO is server-side stitched
//   (SSAI): a single multi-period DASH manifest where the ad periods are real
//   <Period>s. Verified on 7.9.0.61 by pulling the live manifest for a 1:42:00
//   movie — it was stretched to 2:02:02 by ~20 min of stitched ads across 7
//   breaks, every segment served from gcp.amer-free.prd.media.max.com. Emptying
//   the timeline does not remove those segments, so skippable ads still play
//   (same lesson as Prime Video / MLB native SSAI).
//
// HOW THE DNS BLOCK WORKS (and how we mirror it):
//   The manifest DOCUMENT is served from a clean origin (…h264.io), but ALL its
//   segments — ad AND content — come from the {gcp|akm|cf|fly}.<region>-free.prd
//   .media.max.com origin. The AdGuard lists block that origin with $important;
//   because it also carries the SSAI content segments, HBO's client-side
//   "resiliency-v5.1" CDN-failover layer abandons the stitched manifest and
//   re-requests a clean, non-SSAI manifest — the ad-free stream. This patch
//   throws an IOException from media3's DefaultHttpDataSource.open(DataSpec) for
//   requests to those origins, which the ExoPlayer loader surfaces to the same
//   resiliency layer as a load error (the in-app equivalent of the DNS failure).
//
// ⚠️ HONEST SCOPE / RISK: the end effect depends on HBO's resiliency layer
//   falling back to a clean manifest when the -free origin fails, exactly as it
//   does for a DNS failure. That is the proven AdGuard behaviour, but the in-app
//   IOException path must be confirmed on-device to fall back cleanly rather than
//   stall playback — which is why this ships OPT-IN and separate from the
//   default-on "Disable Ads" patch until field-verified. The blocked hosts come
//   from the ad origins alone (…-free.prd.media.max.com, gmss., fwmrm.net,
//   dnitv.com); the manifest origin (…h264.io) and QoE telemetry (litix.io,
//   mediamelon) are intentionally left alone.
//
// The DefaultHttpDataSource class is R8-renamed per build; it is matched by its
// "DefaultHttpDataSource" log-tag string + the open(DataSpec)J shape rather than
// by name, so this survives version bumps (see DefaultHttpDataSourceOpenFingerprint).
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val hboBlockSsaiOriginsPatch = bytecodePatch(
    name = "HBO Max - Block SSAI Ad Origins",
    description = "Reproduces the AdGuard DNS ad-block inside the app: fails media3 " +
        "segment requests to HBO's SSAI ad origins (amer-free/emea-free.prd.media.max.com, " +
        "gmss, FreeWheel) so the player's resiliency layer falls back to the clean, " +
        "ad-free manifest — removing the stitched ad VIDEO the default Disable Ads patch " +
        "leaves behind. Opt-in / experimental; verify playback on-device.",
    default = false,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    // Merges HboAdOriginFilter into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // Inject the origin guard at the very entry of DefaultHttpDataSource.open()
        // — before the method's internal try-block, so a thrown IOException
        // propagates to the ExoPlayer loader (resiliency CDN failover) instead of
        // being caught and retried inside the data source.
        //
        // The DataSpec is p1 (open is an instance method: p0=this, p1=DataSpec).
        // open() has many locals, so p1 sits in a high register; move it into v0
        // (dead at entry — the original body only writes v0 later, inside the try)
        // and pass it as Object so the patch never names the obfuscated DataSpec.
        DefaultHttpDataSourceOpenFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                invoke-static {v0}, Lajstrick81/morphe/extension/hbomax/ads/HboAdOriginFilter;->guard(Ljava/lang/Object;)V
            """.trimIndent(),
        )
    }
}
