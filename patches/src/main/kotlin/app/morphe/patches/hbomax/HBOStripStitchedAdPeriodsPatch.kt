package app.morphe.patches.hbomax.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// HBO Max — Strip Stitched Ad Periods  (OPT-IN, default OFF, EXPERIMENTAL)
//
// The resume-safe successor to "Block SSAI Ad Origins". Both fight the same thing
// — HBO's ad-supported tier serves a single multi-period DASH manifest with the
// ad breaks stitched in as real <Period>s — but at different layers:
//
//   • Block SSAI Ad Origins fails the ad SEGMENT fetch *downstream*, after the
//     manifest is already stitched. That only recovers when the failure happens
//     at video START (HBO's VideoStartFailureRecoveryUseCase → clean fallback).
//     On a RESUMED session the block never trips at start, so reaching a mid-roll
//     — by play or seek — is a post-start failure that escalates to a fatal
//     "Couldn't Play Content" (39999) error. (See HboManifestFilter for the full
//     investigation.)
//
//   • This patch removes the ad periods *upstream*, at manifest PARSE time, so
//     they never exist. With no ad <Period>s the player never requests a -free ad
//     segment at all → no ads AND nothing to fatal on, on fresh start or resume.
//     This is the in-app analogue of what the AdGuard DNS list achieves by
//     starving ad stitching, but done via manifest laundering instead of a host
//     block (the ad-decision hosts are server-driven and not statically hookable).
//
// HOW: inject at the entry of media3's DashManifestParser.parse(Uri, InputStream)
// (R8-renamed; matched by the "MpdParser"/"MPD" content anchor) and swap the
// InputStream for HboManifestFilter.launderManifest(...), which drops every
// <Period> whose per-period <BaseURL> points at the -free.prd.media.max.com ad
// origin. Content periods (clean MPD-level BaseURL) are untouched.
//
// ⚠️ EXPERIMENTAL — default OFF pending on-device verification (planned next
//   session). Open risk: straight period removal leaves the surviving content
//   periods at their original start offsets, i.e. a gap where each ad was. media3
//   multi-period VOD may tolerate this or may need the gaps closed by
//   re-baselining subsequent <Period start=> (podwash) — HboManifestFilter has a
//   TODO_REBASELINE hook for that once the device test says whether it's needed.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val hboStripStitchedAdPeriodsPatch = bytecodePatch(
    name = "HBO Max - Strip Stitched Ad Periods",
    description = "Removes SSAI ad breaks at DASH manifest parse time by dropping " +
        "the stitched ad <Period>s (those served from the -free ad origin) before " +
        "the player sees them. Resume-safe alternative to Block SSAI Ad Origins — " +
        "no ad segments are ever requested, so there is no mid-roll 39999 error on " +
        "resumed titles. Experimental; verify playback on-device.",
    default = false,
) {
    compatibleWith(AppCompatibilities.HBO_TV)

    // Merges HboManifestFilter into the patched dex.
    extendWith("extensions/extension.mpe")

    execute {
        // parse() is an instance method: p0=this, p1=Uri, p2=InputStream. Swap p2
        // for the laundered stream at index 0, before XmlPullParser.setInput(p2)
        // reads it, so the parser only ever sees the ad-free manifest.
        DashManifestParserParseFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p2}, Lajstrick81/morphe/extension/hbomax/ads/HboManifestFilter;->launderManifest(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p2
            """.trimIndent(),
        )
    }
}
