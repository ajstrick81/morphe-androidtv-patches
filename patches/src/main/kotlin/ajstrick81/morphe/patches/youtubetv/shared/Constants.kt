package ajstrick81.morphe.patches.youtubetv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// YouTube for Android TV (com.google.android.youtube.tv) — the leanback YouTube
// client that ships on Android TV / Google TV. NOT "YouTube TV: Live TV & more"
// (the live-streaming service, com.google.android.apps.youtube.unplugged).
//
// Target verified from a re-pulled base.apk (apktool, 2026-08-19):
//   versionName "7.11.300", versionCode 711300320, single-arch armeabi-v7a
//   bundle (requiredSplitTypes base__abi, extractNativeLibs="false").
//
// The app is a Chromium-based Cobalt ("Chrobalt") shell that loads the leanback
// web app from https://www.youtube.com/tv at runtime; see
// analysis/youtube-tv/711300320/ for the full architecture write-up. Ads are
// negotiated in the InnerTube client context rather than bundled, which is what
// the "Client context hook" patch exploits.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "YouTube Android TV",
        packageName = "com.google.android.youtube.tv",
        appIconColor = 0xFF0000,
        // Must equal the APK's ACTUAL versionName so Morphe Manager recognizes
        // the download as Recommended (not "Unsupported — proceed anyway").
        targets = listOf(AppTarget("7.11.300"))
    )
}
