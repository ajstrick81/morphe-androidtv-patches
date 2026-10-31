package ajstrick81.morphe.patches.netflix.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Netflix Android TV (com.netflix.ninja). Target verified from a re-pulled
// base.apk (androguard, 2026-08-07); 13.2.2 (27250) re-verified 2026-10-30:
// versionName "13.0.1 build 25028",
// versionCode 25028, ABI armeabi-v7a, requiredSplitTypes base__abi,
// extractNativeLibs="false". Application subclass Lcom/netflix/ninja/
// NetflixApplication; and its onCreate()V (public, .registers 11) confirmed
// against real bytecode — index-0 injection of loadLibrary is register-safe.
//
// NOTE: the PORTABILITY-ASSESSMENT.md §3 architecture (Hermes/appboot/milo,
// MSL, appboot signature) was measured on 13.0.0-25009; Netflix has since
// bumped to 25028. The Application anchor is unchanged; re-verify the native
// findings if/when libnetflix.so from this build is analyzed.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Netflix Android TV",
        packageName = "com.netflix.ninja",
        appIconColor = 0xE50914,
        // Must equal the APK's ACTUAL versionName so Morphe Manager recognizes
        // the download as Recommended (not "Unsupported — proceed anyway"). The
        // armeabi-v7a build on APKMirror reports versionName "13.0.1 build 25028"
        // (versionCode 25028); a normalized "13.0.1-25028-armv7a" string does NOT
        // match and trips the version-mismatch warning.
        //
        // "13.2.1 build 27207" (versionCode 27207) is marked EXPERIMENTAL:
        // device-verified on Onn coffey (clean startup, no CertCheck crash,
        // ad-kill landing with verify=OK, multiple titles ad-free over a
        // sustained session), BUT the ad-kill currently rests on the V3
        // re-minify re-anchor rather than the pristine exact MASTER/ADV anchors
        // (those re-minified on this build). V3 is drift-tolerant and
        // verify-before-write, so it's safe; the exact re-pin is tracked ship
        // hardening. isExperimental=true surfaces a "proceed with caution" flag
        // in Morphe Manager. See memory netflix-13.2.1-27207-bringup.
        targets = listOf(
            AppTarget("13.0.1 build 25028"),
            AppTarget("13.2.1 build 27207", isExperimental = true),
            // 13.2.2 (27250): target for household suppression and ad-kill.
            // Application anchor re-verified against real bytecode (onCreate .locals 4,
            // instr 0 const-string v0); CertCheck literal present.
            // Verified on-device (AFTKM, Fire OS 8, armv7a).
            AppTarget("13.2.2 build 27250", isExperimental = true),
        )
    )
}
