package app.morphe.patches.hbomax.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Block SSAI Ad Origins  (OPT-IN, default OFF — see RESUME 39999 note)
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
// ⚠️ RESUME 39999 REGRESSION — WHY THIS IS OPT-IN / DEFAULT OFF:
//   This works cleanly only on a FRESH start. The block fails media3 SEGMENT
//   requests to the -free origin *downstream* — after the manifest is already
//   ad-stitched. It relies on HBO's `VideoStartFailureRecoveryUseCase`, which
//   ONLY recovers manifest failures that occur at VIDEO START:
//     • Fresh start: the -free block trips at startup → start-failure recovery →
//       swaps to the clean `_fallback.mpd` → no ad periods, no markers, safe.
//     • RESUME: playback resumes from a bookmark on a clean origin, so the block
//       does NOT trip at start (0 blocks logged); the session stays on the
//       ad-stitched manifest. When playback then REACHES a mid-roll — by normal
//       play OR by seek — the -free request is a POST-start failure that recovery
//       does not handle → escalates to `39999 ws2 Source error` (FATAL) →
//       "Couldn't Play Content" error screen. Confirmed on-device 2026-08-25
//       (Onn 4K, 7.9.0.61): resume + play-into-ad reproducibly errors.
//   v1.29.3 briefly shipped this DEFAULT ON (#134) and was REVERTED to opt-in
//   here because resume is the common path. The real fix is an UPSTREAM,
//   connection-layer host block of the ad-DECISION / ad-TIMELINE hosts
//   (fwmrm.net, gmss.*.discomax.com) so ads are never stitched — the in-app
//   equivalent of the AdGuard DNS list, which does NOT hit this because it blocks
//   those hosts for ALL HTTP clients, upstream of stitching. See memory
//   hbomax-origin-block-resume-39999-regression. Until that lands, the media3
//   segment block below is safe only as an opt-in for fresh-start viewing.
//   Blocked hosts: …-free.prd.media.max.com, gmss., fwmrm.net, dnitv.com; the
//   manifest origin (…h264.io) and QoE telemetry (litix.io, mediamelon) are left alone.
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
        "leaves behind. OPT-IN: works on a fresh start, but a RESUMED session that " +
        "reaches a mid-roll throws a fatal 'Couldn't Play Content' (39999) error, so " +
        "it is default-off pending the upstream connection-layer fix.",
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
