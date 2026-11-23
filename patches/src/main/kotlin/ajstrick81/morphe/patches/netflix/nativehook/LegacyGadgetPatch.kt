package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// LEGACY ENGINE (OPT-IN, default OFF) — for TVs where the default engine crashes.
//
// WHY: the default ad-remover engine (frida-gadget 17.9.1) hooks the Android
// dynamic linker's module-load path. On some 32-bit Android 12 TV linker builds
// that hook lands wrong: the script arms ("killads ready …"), then the process
// dies ~150 ms later with
//   Fatal signal 11 (SIGSEGV) … #01 … linker (__dl_notify_gdb_of_load+22)
// Seen on a TiVo Stream 4K (#241) and a Philips PH9M_EA_5599 (#317). The crash
// is the engine's, not the script's, and is identical across Netflix versions.
//
// FIX (found and verified on the Philips by the #317 reporter): frida-gadget
// 16.2.1 does not crash on those linkers. Gadget 16.7.19 still crashes; 16.6.6
// and older launch but their default QuickJS runtime cannot parse killads.js,
// so the script silently never runs — hence the forced V8 runtime below.
//
// WHAT THIS PATCH DOES: it runs AFTER the two default ad-remover patches
// (dependsOn) and replaces two of the files they staged:
//   1. lib/armeabi-v7a/libgadget.so         -> the 16.2.1 engine
//   2. lib/armeabi-v7a/libgadget.config.so  -> same script-mode config + "runtime":"v8"
// The script (libgadget.script.so) is NOT replaced: killads.js carries a
// one-line polyfill for the single frida-17-only call it makes, which is a
// no-op on the default engine. So with this patch OFF the output is exactly
// what the default patches produce.
//
// The 16.2.1 binary is committed next to the default one (same redistribution
// terms, frida's wxWindows licence):
//   patches/src/main/resources/netflix/native/armeabi-v7a/libgadget-16.2.1.so
//   sha256 55cd05fb08be602c6eaf5467d310a31a9f9bac0ddc8dabbe434284ebf83c60ae
//   (frida-gadget-16.2.1-android-arm.so from github.com/frida/frida/releases/16.2.1)
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val legacyGadgetPatch = resourcePatch(
    name = "Remove Netflix ads (legacy engine)",
    description = "Only for TVs where the patched Netflix clone crashes right at launch with the " +
        "default ad remover (seen on some Android 12 devices, e.g. TiVo Stream 4K and some " +
        "Philips TVs). Swaps the in-process ad-kill engine for an older build (frida-gadget " +
        "16.2.1) that those devices tolerate; the ad-kill script itself is unchanged. Select it " +
        "IN ADDITION to the two default \"Remove Netflix ads\" patches. Leave it off if the " +
        "default patch works for you. Opt-in / experimental.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    // The loader (and through it the bundle-engine patch) must have staged the
    // default engine, script and config first; this patch then overwrites two of them.
    dependsOn(loadGadgetPatch)

    execute {
        val abi = "armeabi-v7a"

        val resourcePath = "/netflix/native/$abi/libgadget-16.2.1.so"
        val soBytes = object {}.javaClass.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("legacyGadgetPatch: $resourcePath not found on the patch classpath")

        val gadget = get("lib/$abi/libgadget.so")
        val config = get("lib/$abi/libgadget.config.so")
        require(gadget.exists() && config.exists()) {
            "legacyGadgetPatch: the default engine was not staged — bundleGadgetPatch out of sync."
        }

        gadget.writeBytes(soBytes)

        // Same script-mode config as bundleGadgetPatch, plus "runtime":"v8": gadget 16.x
        // defaults to QuickJS, which fails to parse killads.js ("unexpected line
        // terminator in regexp") and would leave the app launching with ads intact.
        config.writeText(
            """
            {
              "runtime": "v8",
              "interaction": {
                "type": "script",
                "path": "libgadget.script.so"
              }
            }
            """.trimIndent()
        )
    }
}
