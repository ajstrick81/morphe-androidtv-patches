package ajstrick81.morphe.patches.netflix.nativehook

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// Application.onCreate — earliest reliable point to load frida-gadget so its
// script (dump_appboot.js) is live before the nrdp runtime fetches/caches the
// appboot UI bundle.
//
// definingClass resolved from base.apk's AndroidManifest.xml
// (<application android:name=".NetflixApplication" …>) →
// Lcom/netflix/ninja/NetflixApplication; — see PORTABILITY-ASSESSMENT.md §3.
// This is the same anchor the assessment named for LoadNativeHookPatch.
//
// VERIFIED against real bytecode (androguard, 2026-08-07) on Netflix
// 13.0.1-25028: the class extends Landroid/app/Application; and defines
// onCreate ()V with PUBLIC access — this fingerprint resolves. onCreate has
// .registers 11 (v10 = this) and its instr 0 is already `const-string v0`, so
// injecting `const-string v0, "gadget"` at index 0 is register-safe (v0 is a
// scratch local the original body immediately reuses; this/v10 untouched), and
// lands before invoke-super onCreate and Netflix's native init.
//
// Re-verify on every version bump: an onCreate fingerprint anchored to the
// wrong class silently won't resolve. If it proves brittle, switch to a
// custom predicate matching the unique Application.onCreate that calls
// super.onCreate() — but keep it anchored to exactly one method so the
// loadLibrary isn't injected twice.
// ─────────────────────────────────────────────────────────────────────────────
object NetflixApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/netflix/ninja/NetflixApplication;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
