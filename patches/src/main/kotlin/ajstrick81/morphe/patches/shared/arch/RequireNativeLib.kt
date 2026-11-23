package ajstrick81.morphe.patches.shared.arch

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext

// Guard against Morphe Manager's "Optimize for device architecture" (Expert mode).
//
// With that option on, Manager keeps only the CPU architecture of the device doing
// the patching. For a split bundle (.apkm) it skips the other architecture splits
// while merging — BEFORE patches run. So when someone patches on an arm64 phone for
// a TV box, an armeabi-v7a-only bundle (Prime Video 6.23.23, Netflix 13.0.1) reaches
// us with no lib/armeabi-v7a/ at all. Without this check every patch still reports
// "Applied" and the output crashes on the TV (our injected lib is there, the app's
// own engine library is not).
//
// Verified 2026-09-25 with the Morphe CLI: the resource workspace exposes the app's
// original native libs under lib/<abi>/ (e.g. lib/armeabi-v7a/libnetflix.so), and a
// copy of the APK with lib/ removed reaches the patch with no lib/ directory.
//
// Limit: for a plain .apk, Manager strips other architectures AFTER patching, which
// no patch can see. The README install steps cover that case.

/**
 * Fails the patch with a clear message when the app's own native library for [abi]
 * is missing — the signature of an architecture-optimized input.
 *
 * @param appName shown to the user, e.g. "Prime Video".
 * @param mainLib the app's own engine library that every good input contains.
 */
fun ResourcePatchContext.requireNativeLib(appName: String, mainLib: String, abi: String = "armeabi-v7a") {
    if (get("lib/$abi/$mainLib").exists()) return

    val present = get("lib").listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
    val found = if (present.isEmpty()) "no native libraries at all" else "only ${present.joinToString()}"

    throw PatchException(
        "$appName needs its $abi native libraries, but this APK has $found " +
            "(lib/$abi/$mainLib is missing). This usually means Morphe Manager's " +
            "\"Optimize for device architecture\" option was ON while patching on a phone. " +
            "Turn it OFF (it is an Expert mode setting) and patch the original .apkm again. " +
            "If the option is already off, re-download the exact Android TV bundle " +
            "linked in the README.",
    )
}

/**
 * Multi-ABI form: returns every ABI in [abis] whose [mainLib] is present, and fails
 * only when none is. Use this for an app that ships more than one architecture, where
 * any one of them is a valid input — Prime Video v15.5 is armeabi-v7a only while the
 * v16 bundles are allAbis, and both are legitimate.
 *
 * The caller is expected to inject its own library into *each* returned ABI; injecting
 * into only one leaves a 64-bit install silently unhooked.
 *
 * @param appName shown to the user, e.g. "Prime Video".
 * @param mainLib the app's own engine library that every good input contains.
 * @param abis the ABIs worth looking for, most-preferred first.
 */
fun ResourcePatchContext.requirePresentNativeAbis(
    appName: String,
    mainLib: String,
    abis: List<String> = listOf("armeabi-v7a", "arm64-v8a"),
): List<String> {
    val present = abis.filter { get("lib/$it/$mainLib").exists() }
    if (present.isNotEmpty()) return present

    val found = get("lib").listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
    val describe = if (found.isEmpty()) "no native libraries at all" else "only ${found.joinToString()}"

    throw PatchException(
        "$appName needs its native libraries, but this APK has $describe " +
            "(expected $mainLib under one of ${abis.joinToString()}). This usually means " +
            "Morphe Manager's \"Optimize for device architecture\" option was ON while " +
            "patching on a phone. Turn it OFF (it is an Expert mode setting) and patch the " +
            "original .apkm again. If the option is already off, re-download the exact " +
            "Android TV bundle linked in the README.",
    )
}
