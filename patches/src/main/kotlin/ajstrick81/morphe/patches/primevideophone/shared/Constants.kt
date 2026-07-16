package ajstrick81.morphe.patches.primevideophone.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Prime Video *phone* client (com.amazon.avod.thirdpartyclient) — distinct from
// the native/WASM Android TV build (com.amazon.amazonvideo.livingroom). The
// phone binary is a normal Java/Kotlin app, which is why its player UI can be
// reached and augmented by bytecode. Run on TV hardware to sidestep the sealed
// native ad pipeline of the ATV build.
//
// Pinned target: 3.0.452 build 1045 = armeabi-v7a (nodpi), the 32-bit ABI that
// installs on the Onn 4K (2nd-gen). Build 1047 (arm64) is the same 3.0.452
// release with an identical Java/dex layer, so this patch applies to both.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Prime Video (phone)",
        packageName = "com.amazon.avod.thirdpartyclient",
        appIconColor = 0x177BCE,
        targets = listOf(AppTarget("3.0.452.1045"))
    )
}
