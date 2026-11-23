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
        // "6.23.23+v15.5.0.70-armv7a" is the longest-validated STABLE target and
        // what the announcement recommends.
        //
        // "6.24.7+v16.0.0.332-allAbis" is STABLE as of 2026-11-23: the maintainer
        // has run it as a daily driver for weeks with ads suppressed and no
        // issues. "6.24.13+v16.0.1.93-allAbis" stays EXPERIMENTAL
        // (isExperimental=true surfaces a "proceed with caution" flag in
        // Manager) until it gets a device pass. Why v16 works at all: the ad
        // strip is a GOT/PLT import hook on libignite's memcpy/memmove slots, so
        // it never depended on the Java ad surface v16 deleted. Those import
        // slots are unchanged across all three builds (R_ARM_JUMP_SLOT for
        // memcpy, memmove, __memcpy_chk, __memmove_chk, memset, __memset_chk;
        // libignite.so 15.68 MB -> 16.35 MB -> 16.38 MB), and
        // Lcom/amazon/primevideo/PrimeVideoApplication; is still the manifest's
        // android:name, so ApplicationOnCreateFingerprint still resolves.
        //
        // History: until 2026-11-07 the loader could not even fire on v16:
        // ApplicationOnCreateFingerprint pinned accessFlags=[PUBLIC] while v16's
        // onCreate is `public final`, and Morphe compares accessFlags with exact
        // integer equality, so the fingerprint silently failed to resolve and
        // libpvhook was never loaded. Dropping accessFlags (see Fingerprints.kt)
        // is what makes the strip reachable on v16 at all.
        //   6.24.7+v16.0.0.332-allAbis   — device-confirmed after that fix
        //     (maintainer's daily use, weeks, no issues).
        //   6.24.13+v16.0.1.93-allAbis   — same import slots and anchor as
        //     6.24.7 (libignite +27 KB); not yet run on a device.
        // Verify with logcat: PVNativeHook lines present at all (hook loaded),
        // then PVKILL / rego_* heartbeat counters moving at an ad break.
        //
        // The v16 bundles are allAbis (arm64-v8a + armeabi-v7a);
        // bundleNativeHookPatch ships libpvhook.so for both ABIs, so a 64-bit
        // install gets its own hook.
        targets = listOf(
            AppTarget("6.23.23+v15.5.0.70-armv7a"),
            AppTarget("6.24.7+v16.0.0.332-allAbis"),
            AppTarget("6.24.13+v16.0.1.93-allAbis", isExperimental = true),
        )
    )
}
