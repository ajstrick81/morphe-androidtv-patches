package ajstrick81.morphe.patches.discoveryplus.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // discovery+ (Android TV) runs on the same WBD Bolt / Beam player stack as HBO Max
    // (com.discovery.player.*, com.wbd.beam.player.*, com.wbd.adtech.bolt.*), so the hook
    // targets below mirror the HBO Max patches. Not yet device-verified: experimental.
    val COMPATIBILITY = Compatibility(
        name = "discovery+ Android TV",
        packageName = "com.discovery.dplay",
        appIconColor = 0x0A1E5C,
        targets = listOf(
            AppTarget("22.1.0.71", isExperimental = true),
        ),
    )
}
