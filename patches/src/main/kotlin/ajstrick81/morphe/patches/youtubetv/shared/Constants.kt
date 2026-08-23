package ajstrick81.morphe.patches.youtubetv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // NOTE: packageName + target are UNCONFIRMED — verify on the Onn before a
    // real build:  adb shell pm list packages | grep -iE "youtube|unplugged"
    // Android TV YouTube TV is commonly com.google.android.youtube.tv. If it
    // differs (e.g. a Google-TV variant), update this one line. A single
    // null-version AppTarget means "any version" so the capture patch applies to
    // any build pulled during RE (Compatibility rejects an empty target list);
    // pin a real AppTarget once we settle on a tested build.
    val COMPATIBILITY = Compatibility(
        name = "YouTube TV Android TV",
        packageName = "com.google.android.youtube.tv",
        appIconColor = 0xFF0000,
        targets = listOf(AppTarget(null)),
    )
}
