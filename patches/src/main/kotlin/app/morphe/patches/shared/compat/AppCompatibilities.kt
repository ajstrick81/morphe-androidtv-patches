package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object AppCompatibilities {

    val PARAMOUNT_TV = Compatibility(
    name = "Paramount+ Android TV",
    packageName = "com.cbs.ott",
    appIconColor = 0x0064FF,
    targets = listOf(
        AppTarget("16.12.0"),
        AppTarget("16.8.0"),
       ),
   )

    val DISNEY_PLUS_TV = Compatibility(
        name = "Disney+ Android TV",
        packageName = "com.disney.disneyplus",
        appIconColor = 0x113CCF,
        targets = listOf(AppTarget("26.6.0+rc5-2026.04.21"),
                         AppTarget("26.8.0+rc6-2026.05.20"), 
                         AppTarget("26.9.2+rc1-2026.06.12"),
        ),                 
    )

    val HBO_TV = Compatibility(
    name = "HBO Max Android TV",
    packageName = "com.wbd.hbomax",
    appIconColor = 0xFFFFFF,
    targets = listOf(
        AppTarget("7.5.0.73"),
        AppTarget("7.2.0.41"),
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
