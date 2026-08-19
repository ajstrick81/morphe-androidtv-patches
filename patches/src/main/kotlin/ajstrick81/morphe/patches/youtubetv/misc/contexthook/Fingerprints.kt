package ajstrick81.morphe.patches.youtubetv.misc.contexthook

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Automotive-feature check — the InnerTube client-context OS-name gate
//
// The InnerTube client context sent with every YouTube request carries an OS
// name. YouTube does not serve video ads to the Android Automotive OS. The app
// decides whether it is running on Automotive with a single cached helper:
//
//   static boolean c(Context ctx)   // class Lfam; in 7.11.300 (was Leyn; in 7.05.301)
//       returns ctx.getPackageManager()
//                  .hasSystemFeature("android.hardware.type.automotive")
//
// Its result flows into the client-context builder (Lfht; in 7.11.300), which
// sets the OS-name string to "Android Automotive" when the check is true
// (fht.smali: invoke fam.c(Context) → const-string "Android Automotive").
// Forcing c() to return true therefore makes the app report OS = Android
// Automotive, and YouTube suppresses video ads.
//
// The class/method names are R8-obfuscated and drift across versions, so this
// fingerprint anchors on the STABLE, non-obfuscated shape instead:
//   • the "android.hardware.type.automotive" string constant,
//   • return type Z,
//   • method name "c" with a single Landroid/content/Context; parameter.
// In 7.11.300 this resolves uniquely to Lfam;->c(Landroid/content/Context;)Z
// (the four other references to the automotive string are in Cobalt platform
// classes with different signatures — AudioManagerAndroid, DeviceInfo, etc.).
val automotiveCheckFingerprint = Fingerprint(
    strings = listOf("android.hardware.type.automotive"),
    returnType = "Z",
    custom = { method, _ ->
        method.name == "c" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Landroid/content/Context;"
    },
)
