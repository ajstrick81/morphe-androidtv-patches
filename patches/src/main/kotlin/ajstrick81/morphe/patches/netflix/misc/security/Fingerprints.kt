package ajstrick81.morphe.patches.netflix.misc.security

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// DexGuard application CertCheck (anti-tamper).
//
// Netflix is protected by Guardsquare DexGuard, which injects a runtime check
// that reads the APK signing certificate and DELIBERATELY CRASHES the process
// when it isn't Netflix's official cert — so ANY re-signed build (clone or in
// place) dies ~2.5s after launch with, in logcat:
//     E Loader: CertCheck failed, crash!!!
// and a "DexGuardCertCheckException : DexGuard App CertCheck failed !" handled
// exception. Verified on-device (Onn, 13.0.1-25028); see
// experimental/netflix-native-adstrip/HANDOFF.md.
//
// The check lives in an obfuscated Runnable whose run() method:
//   - fetches the expected SHA-256 fingerprint (a hardcoded string literal),
//   - calls an obfuscated verifier returning Z (true = cert matches),
//   - on false: Log.e("Loader", "CertCheck failed, crash!!!") then reflectively
//     invokes a killer that tears the process down.
//
// Obfuscated names (class Lo/setReturnTransition$5;, verifier MoMD214, etc.)
// rotate every build, so we anchor on the ONE stable, unique artifact: the
// literal crash string. Confirmed the only method in the app referencing it.
// ─────────────────────────────────────────────────────────────────────────────
internal object DexGuardCertCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    strings = listOf("CertCheck failed, crash!!!"),
)
