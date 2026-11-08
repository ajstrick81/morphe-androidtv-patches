package ajstrick81.morphe.patches.future.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Fawesome Movies & TV (Android TV)",
        packageName = "com.future.moviesByFawesomeAndroidTV",
        appIconColor = 0xFFB300,
        targets = listOf(AppTarget("19.7"))
    )

    // The update-prompt seam (Disable update prompt) survives across the 19.7 → 20.1
    // rewrite, so that patch declares its own compatibility spanning both. The ad seams
    // (Skip ads) do NOT survive 20.1's R8 obfuscation, so Skip ads stays on COMPATIBILITY
    // (19.7 only) rather than falsely advertising 20.1 support.
    val UPDATE_COMPATIBILITY = Compatibility(
        name = "Fawesome Movies & TV (Android TV)",
        packageName = "com.future.moviesByFawesomeAndroidTV",
        appIconColor = 0xFFB300,
        targets = listOf(AppTarget("20.8"), AppTarget("19.7"), AppTarget("20.1"))
    )

    // 20.8 is a different app skeleton again: between 20.1 and 20.8 Fawesome was
    // rebased onto Future Today's shared FilmRise MVVM codebase, so com/future/*
    // is gone and the player, splash and globals live in com/mvvm/* and
    // com/recipe/filmrise/*. Class names are readable there (20.1's were not),
    // which is why the 20.x ad seams are re-anchorable at all — but they are a
    // different set of seams from both 19.7 and 20.1, so Skip ads (v20) gets its
    // own compatibility rather than being folded into either.
    val COMPATIBILITY_V20 = Compatibility(
        name = "Fawesome Movies & TV (Android TV)",
        packageName = "com.future.moviesByFawesomeAndroidTV",
        appIconColor = 0xFFB300,
        targets = listOf(AppTarget("20.8"))
    )
}
