package ajstrick81.morphe.patches.netflix.misc.security

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.netflix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Disable Netflix's DexGuard application CertCheck (anti-tamper).
//
// Any Morphe-signed Netflix build trips DexGuard's runtime signing-cert check
// and self-crashes ~2.5s after launch (see DexGuardCertCheckFingerprint). This
// is a hard prerequisite for EVERY Netflix patch on this repo: without it a
// re-signed Netflix never stays up long enough to do anything.
//
// The check's whole run() method does nothing but "verify cert → else crash".
// Its non-crash outcome is simply to return, so neutralising it is a no-op:
// prepend `return-void` at index 0 so run() returns before the verifier or the
// kill path can execute. No register use, no fallout — the app proceeds exactly
// as it would on a genuine-cert pass.
//
// Anchored purely on the unique crash string, so it survives DexGuard's
// per-build name rotation. Default-enabled: it must run for any re-signed build.
// If DexGuard adds native-side reinforcement in a later version this Java no-op
// may not be sufficient — re-check logcat for a new crash tag if a bump breaks it.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val disableCertCheckPatch = bytecodePatch(
    name = "Disable Netflix CertCheck",
    description = "Neutralises Netflix's DexGuard signing-certificate anti-tamper check so a " +
        "re-signed build doesn't self-crash on launch. Required for every Netflix patch.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        DexGuardCertCheckFingerprint.method.addInstructions(0, "return-void")
    }
}
