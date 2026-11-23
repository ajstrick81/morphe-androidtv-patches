package ajstrick81.morphe.patches.shared.compat

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object AppCompatibilities {

    val PARAMOUNT_TV = Compatibility(
    name = "Paramount+ Android TV",
    packageName = "com.cbs.ott",
    appIconColor = 0x0064FF,
    targets = listOf(
        // 17.3.0 (experimental): device-tested 2026-11-12 on an onn 4K Plus. VOD movies
        // play with no pre-roll/promo pod (a title showed one unpatched and none patched).
        // Not yet verified on this build: pause ads, mid-rolls on TV episodes, live slate.
        // All three fingerprints resolve unchanged; the two patched AVIA methods are
        // instruction-identical to 16.21.0.
        AppTarget("17.3.0", isExperimental = true),
        AppTarget("16.19.0"),
        AppTarget("16.17.0"),
        AppTarget("16.12.0"),
        AppTarget("16.8.0"),
       ),
   )

    val DISNEY_PLUS_TV = Compatibility(
        name = "Disney+ Android TV",
        packageName = "com.disney.disneyplus",
        appIconColor = 0x113CCF,
        // Newest first, like every other app here. Disney enforces a minimum
        // client version server-side: an older build installs and launches
        // fine, then playback dies ~20 s in with "There was a problem" (#302).
        // No patch can fix that, so builds Disney has cut off are removed from
        // this list rather than left here looking supported.
        // 26.20.0+rc3 (experimental): patched and tested on-device on the Onn 4K Plus.
        // Both Insertion seams hook cleanly and the empty-ArrayList prologue is confirmed
        // in the patched dex; several movies and shows played with no pre-roll, no mid-roll
        // markers, no stutter and no playback delay, and zero ad-decision / beacon /
        // InsertionPoint traffic in logcat. Pause ads are NOT blocked here — the MEL pause
        // seam has been unresolvable since 26.16 (#167).
        targets = listOf(AppTarget("26.20.0+rc3-2026.11.02", isExperimental = true),
                         AppTarget("26.16.0+rc2-2026.09.08"),
                         AppTarget("26.12.1+rc1-2026.07.15"),
        ),
    )

    // Same app as DISNEY_PLUS_TV, but ONLY 26.12.1+rc1. "Suppress Household Prompt" is
    // obfuscation-pinned: its fingerprint matches Lv8/x;->y, an R8-minified name exact to
    // 26.12.1+rc1-2026.07.15 (versionCode 1784077450). R8 renames per release, so on any
    // other build the pinned name does not exist and the fingerprint cannot match — which
    // is the "Failed to match the fingerprint: OutOfHouseholdCheckFingerprint" report in
    // #289 (that user was on 26.9.2+rc1). Because that patch reads the eager `.method`
    // accessor, a miss aborts the whole patch rather than degrading. Listing it against the
    // full target list advertised versions it cannot actually patch, so scope it to the one
    // build it is pinned to; every other Disney patch stays on DISNEY_PLUS_TV. Widen this
    // only after re-pinning the fingerprint against a fresh decompile of the newer build.
    val DISNEY_PLUS_TV_HOUSEHOLD_PINNED = Compatibility(
        name = "Disney+ Android TV",
        packageName = "com.disney.disneyplus",
        appIconColor = 0x113CCF,
        targets = listOf(AppTarget("26.12.1+rc1-2026.07.15", isExperimental = true)),
    )

    val HBO_TV = Compatibility(
    name = "HBO Max Android TV",
    packageName = "com.wbd.hbomax",
    appIconColor = 0xFFFFFF,
    targets = listOf(
        // 7.14.0.69 (experimental): device-tested on-device by @Joker4Life12 (Shield TV).
        // The recommended combo — "Disable Ads" + "Remove Ad Periods (keep 5.1)" — is clean
        // (no ads, correct skip-intro/next-episode, 5.1 preserved). "Prefer Ad-Free Stream"
        // works (stereo). NOTE: "Block SSAI Ad Origins" HANGS playback on this build, so it
        // uses HBO_TV_ORIGIN_BLOCK_SAFE below (7.14.0.69 excluded) instead of this list.
        AppTarget("7.14.0.69", isExperimental = true),
        AppTarget("7.9.0.61"),
        AppTarget("7.7.0.78"),
        AppTarget("7.5.0.73"),
        AppTarget("7.2.0.41"),
    ),
    )

    // Same app as HBO_TV, minus 7.14.0.69. "Block SSAI Ad Origins" is the only HBO patch
    // that breaks on 7.14.0.69 — @Joker4Life12 confirmed on-device that with it applied an
    // ad becomes unskippable and playback then hangs on a black screen / endless spinner
    // (consistent with the origin-block's known resume fragility). Keep it off 7.14.x until
    // a fix lands; every other HBO patch stays on HBO_TV (which includes 7.14.0.69).
    val HBO_TV_ORIGIN_BLOCK_SAFE = Compatibility(
    name = "HBO Max Android TV",
    packageName = "com.wbd.hbomax",
    appIconColor = 0xFFFFFF,
    targets = listOf(
        AppTarget("7.9.0.61"),
        AppTarget("7.7.0.78"),
        AppTarget("7.5.0.73"),
        AppTarget("7.2.0.41"),
    ),
    )

    val ESPN_TV = Compatibility(
        name = "ESPN Android TV",
        packageName = "com.espn.score_center",
        appIconColor = 0xCC0000,
        targets = listOf(
            AppTarget("6.11.1"),
        ),
    )

    val MLB_TV = Compatibility(
    name = "MLB.tv Android TV",
    packageName = "com.bamnetworks.mobile.android.gameday.atbat",
    appIconColor = 0x002D72,
    targets = listOf(AppTarget("26.8.1.1"),
        ),
    )    
}
