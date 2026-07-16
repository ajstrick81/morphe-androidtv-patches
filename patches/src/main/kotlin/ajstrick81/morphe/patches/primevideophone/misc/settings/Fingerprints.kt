package ajstrick81.morphe.patches.primevideophone.misc.settings

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// The hijack target — com.google.android.gms.common.api.GoogleApiActivity.onCreate
//
// Anchored entirely on R8-stable facts: GoogleApiActivity is a GMS SDK class that
// is declared by full name in AndroidManifest.xml, so it can never be renamed or
// stripped; onCreate is an Android framework override, so its name/params/return
// type are equally fixed. Nothing app-owned or obfuscated is referenced.
//
// definingClass + name + parameters + returnType already identify this method
// UNIQUELY — a class cannot declare two onCreate(Bundle)V. accessFlags would add
// no selectivity, only a way to break: the observed method is `public final`, and
// pinning `final` would fail the match if GMS ever drops it. Fewer stable filters
// beat more fragile ones.
//
// Verified against the pinned target (3.0.452.1045) in
// smali/classes8/com/google/android/gms/common/api/GoogleApiActivity.smali:
//   .class public Lcom/google/android/gms/common/api/GoogleApiActivity;
//   .super Landroid/app/Activity;                <- the invoke-super in the patch
//   .method public final onCreate(Landroid/os/Bundle;)V
// ─────────────────────────────────────────────────────────────────────────────
object GoogleApiActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/common/api/GoogleApiActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V"
)
