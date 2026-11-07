package ajstrick81.morphe.patches.primevideo.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Prime Video Android TV",
        packageName = "com.amazon.amazonvideo.livingroom",
        appIconColor = 0x177BCE,
        // Target strings must equal the APK's ACTUAL versionName or Morphe
        // Manager flags the download as a version mismatch. All three below were
        // read straight out of each bundle's base.apk AndroidManifest.xml.
        //
        // "6.23.23+v15.5.0.70-armv7a" is the STABLE target: the only build
        // validated end to end here, and what the announcement recommends.
        //
        // The two v16 entries are EXPERIMENTAL (isExperimental=true surfaces a
        // "proceed with caution" flag in Manager). Why they work at all: the ad
        // strip is a GOT/PLT import hook on libignite's memcpy/memmove slots, so
        // it never depended on the Java ad surface v16 deleted. Those import
        // slots are unchanged across all three builds (R_ARM_JUMP_SLOT for
        // memcpy, memmove, __memcpy_chk, __memmove_chk, memset, __memset_chk;
        // libignite.so 15.68 MB -> 16.35 MB -> 16.38 MB), and
        // Lcom/amazon/primevideo/PrimeVideoApplication; is still the manifest's
        // android:name, so ApplicationOnCreateFingerprint still resolves.
        //
        // IMPORTANT — neither v16 entry has verified ad suppression yet. Until
        // 2026-11-07 the loader could not even fire on v16:
        // ApplicationOnCreateFingerprint pinned accessFlags=[PUBLIC] while v16's
        // onCreate is `public final`, and Morphe compares accessFlags with exact
        // integer equality, so the fingerprint silently failed to resolve and
        // libpvhook was never loaded. Dropping accessFlags (see Fingerprints.kt)
        // is what makes the strip reachable on v16 at all. Both entries are
        // therefore STATIC-EVIDENCE ONLY and need a device pass:
        //   6.24.7+v16.0.0.332-allAbis   — run informally by the maintainer on
        //     the Onn 4K; ads appeared suppressed, but that build's hook was NOT
        //     loaded, so the cause is unexplained and is not evidence for this
        //     patch working.
        //   6.24.13+v16.0.1.93-allAbis   — same import slots and anchor as
        //     6.24.7 (libignite +27 KB); never run on a device.
        // Verify with logcat: PVNativeHook lines present at all (hook loaded),
        // then PVKILL / rego_* heartbeat counters moving at an ad break.
        //
        // Caveat for both: the v16 bundles are allAbis (arm64-v8a +
        // armeabi-v7a) and we ship only an armeabi-v7a libpvhook.so, so the
        // hook loads only where the app resolves to 32-bit. On a device that
        // keeps the arm64 slice the app loads arm64 libignite, the hook never
        // runs, and ads come back with no error of any kind. An arm64-v8a
        // libpvhook.so would close that; see bundleNativeHookPatch.
        targets = listOf(
            AppTarget("6.23.23+v15.5.0.70-armv7a"),
            AppTarget("6.24.7+v16.0.0.332-allAbis", isExperimental = true),
            AppTarget("6.24.13+v16.0.1.93-allAbis", isExperimental = true),
        )
    )
}
