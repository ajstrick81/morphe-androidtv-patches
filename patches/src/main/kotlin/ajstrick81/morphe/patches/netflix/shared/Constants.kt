package ajstrick81.morphe.patches.netflix.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Netflix Android TV (com.netflix.ninja). Target confirmed from base.apk bytes
// in experimental/netflix-native-adstrip/PORTABILITY-ASSESSMENT.md §3:
// versionName 13.0.0, versionCode 25009, ABI armeabi-v7a (nrd runtime v26.1).
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Netflix Android TV",
        packageName = "com.netflix.ninja",
        appIconColor = 0xE50914,
        targets = listOf(AppTarget("13.0.0-25009-armv7a"))
    )
}
