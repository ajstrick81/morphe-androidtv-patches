package app.morphe.patches.disney

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// ─────────────────────────────────────────────────────────────────────────────
// Suppress the Disney+ out-of-household ("Verify Household Network") prompt.
// OPT-IN, default OFF, EXPERIMENTAL.
//
// Unlike Netflix's household prompt (which lives in a V8 JS VM and could not be
// beaten from bytecode), Disney's enforcement is entirely Java/Kotlin bytecode in
// com.bamtechmedia.dominguez.accountsharing, and it funnels through a single
// authoritative suspend predicate: Lv8/x;->y(Lv81/c;) returns a boxed Boolean
// meaning "device is OUT of household". Three routing sites consume it — the
// startup destination resolver (Lzy/c1;, → OutOfHouseholdBlock screen), the
// device-verify router (Ln00/d;, → "verifyDevice"), and Lo1/t; — so forcing y()
// to return Boolean.FALSE makes every one of them see an in-household device and
// the app proceeds to home exactly as normal. See Fingerprints.kt for the full
// trace and the re-pin anchors.
//
// Behaviour-neutral for in-household users (y() already returns false for them);
// it only changes the out-of-household path. Kept as its own opt-in — separate
// from the default-on ad patch — because it acts on an account/household control,
// a deliberate and distinct choice.
//
// ⚠️ HONEST SCOPE: this suppresses only the CLIENT-side prompt/routing. Disney's
// out-of-household detection is server/IP-driven; if the server ALSO refuses to
// serve the stream to an out-of-household device, hiding the prompt will not by
// itself restore playback (a home-region VPN is the only fix for that). The seam
// is verified reachable in bytecode; the end-to-end effect can only be confirmed
// by a user actually in a flagged out-of-household state, since the state is not
// reproducible on demand.
//
// ⚠️ OBFUSCATION-PINNED to v26.12.1+rc1-2026.07.15 (versionCode 1784077450).
// Lv8/x;/"y" are R8-minified names and WILL drift on app updates; the fingerprint
// fails loud (patch errors) if it no longer resolves — re-pin against a fresh
// decompile on each version bump.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val suppressHouseholdPromptPatch = bytecodePatch(
    name = "Suppress Household Prompt",
    description = "Hides the Disney+ \"Verify Household Network\" / out-of-household prompt by " +
        "forcing the client's out-of-household check to report in-household, so the app routes " +
        "straight to home. Does NOT change what Disney's servers detect (that's driven by your " +
        "public IP — use a VPN for that); it only suppresses the on-screen prompt/routing. " +
        "Opt-in / experimental; only takes visible effect when the device would otherwise be " +
        "prompted.",
    default = false,
) {
    compatibleWith(AppCompatibilities.DISNEY_PLUS_TV)

    execute {
        // Prepend `return Boolean.FALSE;` so the out-of-household predicate always
        // resolves to "in household". The method is a Kotlin suspend function
        // returning Object; a synchronous non-suspending return of Boolean.FALSE is
        // valid (callers already handle a direct Boolean result vs COROUTINE_SUSPENDED),
        // and the original body — including the real async checks — is never reached.
        OutOfHouseholdCheckFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """.trimIndent(),
        )
    }
}
