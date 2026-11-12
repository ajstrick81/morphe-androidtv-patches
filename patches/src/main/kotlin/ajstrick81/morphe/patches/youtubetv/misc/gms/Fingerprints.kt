package ajstrick81.morphe.patches.youtubetv.misc.gms

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// Fingerprints for "GmsCore support". Every anchor below was checked against
// dexdump -d of the two supported builds. The obfuscated class names change
// between versions; the shapes and strings are what the fingerprints rely on.
//
// 7.31.300: classes2.dex (dexdump line numbers from %TEMP%\dex731-classes2.txt)
// 7.05.301: classes2.dex (dexdump line numbers from .apk-decompile\extracted)

/**
 * Play services availability status, as GMS itself computes it for this app
 * (bundled Play services client code, not the GMS APK).
 *
 * Matches the method holding GMS's manifest-value reader: it reads
 * "com.google.android.gms.version" from the app metadata and returns a
 * ConnectionResult status, where 0 = SUCCESS. "GmsCore support" returns 0 so
 * the app proceeds to talk to MicroG instead of refusing to start.
 *
 * 7.31.300: Ldma;->b(Landroid/content/Context;I)I, PUBLIC STATIC,
 *           classes2.dex 1446701-1446710; strings at 1446756
 *           ("MetadataValueReader"), 1446757 ("This should never happen."),
 *           1446721 and 1446834 ("com.google.android.gms").
 * 7.05.301: Lcut;->e(Landroid/content/Context;I)I, PUBLIC FINAL,
 *           classes2.dex 1157430; same strings at 1157478.
 *
 * No accessFlags constraint: the method is static in 7.31.300 but an instance
 * method in 7.05.301, and pinning either one would reject the other build.
 */
internal object GooglePlayUtilityFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    strings = listOf(
        "MetadataValueReader",
        "This should never happen.",
        "com.google.android.gms",
    ),
)

/**
 * The check that throws GooglePlayServicesNotAvailableException.
 *
 * 7.31.300: Ldma;->c(Landroid/content/Context;I)V, PUBLIC STATIC,
 *           classes2.dex 1447382-1447391, throws at 1447409
 *           ("Google Play Services not available").
 * 7.05.301: Lcvg;->b(Landroid/content/Context;I)V, PUBLIC STATIC,
 *           classes2.dex 1163726, throws at 1163744.
 *
 * "GmsCore support" returns from it immediately: with the GMS package names
 * redirected, MicroG answers the real availability calls, but this local check
 * still runs first and would throw, aborting start-up before the app gets that
 * far.
 */
internal object ServiceCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "I"),
    strings = listOf("Google Play Services not available"),
)

/**
 * The Primes allowlist of first-party packages. It reads the app's own package
 * name and checks it against a list that contains
 * "com.google.android.youtube.tv"; after the rename the app is no longer on
 * that list, so "GmsCore support" swaps that one const-string for the patched
 * package name.
 *
 * 7.31.300: Lelf;->a()Z, PUBLIC FINAL, classes2.dex 1609862-1609872; list at
 *           1609906 holds "com.google.android.youtube.tv", 1609913 holds
 *           "com.android.vending" and 1609914 "com.google.android.GoogleCamera".
 * 7.05.301: Lduc;->a()Z, PUBLIC FINAL, classes2.dex 1383495; the same package
 *           string is at 1383528.
 */
internal object PrimeMethodFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf(
        "com.android.vending",
        "com.google.android.GoogleCamera",
    ),
)

/**
 * CobaltActivity.onCreate(Bundle) — the activity onCreate every YouTube TV
 * activity goes through: MainActivity extends Lcyz; (which overrides onCreate
 * and calls invoke-super Ldev/cobalt/coat/CobaltActivity;.onCreate), and
 * StandalonePlayerActivity extends MainActivity, so one injection covers normal
 * and media-intent launches.
 *
 * 7.31.300: class Ldev/cobalt/coat/CobaltActivity; at classes2.dex 951920,
 *           onCreate at 953316-953325 (PROTECTED, .registers 27, .ins 2 →
 *           p0 = v25).
 * 7.05.301: onCreate at classes2.dex 661720-661728 (PROTECTED, .registers 22,
 *           .ins 2 → p0 = v20).
 */
internal object CobaltActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Ldev/cobalt/coat/CobaltActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)
