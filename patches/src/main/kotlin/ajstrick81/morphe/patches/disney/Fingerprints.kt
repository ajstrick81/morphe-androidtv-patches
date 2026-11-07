/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/disneyplus/ads/Fingerprints.kt
 * ALL CREDIT GOES TO RookieEnough FOR THE ORIGINAL CODE
 *
 * Verified on Disney+ Android TV v26.20.0+rc3-2026.11.02 (versionCode 1793662190)
 * — both Insertion seams resolve unchanged and the empty-ArrayList prologue is
 * confirmed in the patched dex; on-device playback clean (Widevine + HW decoder
 * active, zero ad-decision/beacon/InsertionPoint traffic, no pre- or mid-roll on
 * movies or TV, AGH off). The pause-ad seam stays unresolved, as on 26.16 (#167).
 * Previously verified on v26.12.1+rc1-2026.07.15 (versionCode 1784077450) and
 * v26.8.0+rc6 (1779314460); the DMP-pipeline fix landed on v26.9.2+rc1 (1781224190).
 * - InsertionGetPointsFingerprint:     VALIDATED ✅  (legacy DSS-SDK Insertion)
 * - InsertionGetRangesFingerprint:     VALIDATED ✅  (legacy DSS-SDK Insertion)
 * - DmpInsertionGetPointsFingerprint:  VALIDATED ✅  (new com.disney.dmp Insertion)
 * - DmpInsertionGetRangesFingerprint:  VALIDATED ✅  (new com.disney.dmp Insertion)
 * - PauseAdStartedFingerprint:         VALIDATED ✅  MediaXPauseSession.started()
 *
 * Pause ad patch history:
 *   v1 — targeted onPauseScheduled() → no match (wrong method)
 *   v2 — targeted createPauseSession() → matched but wrong layer;
 *         prod-frida-origin.bamgrid.com still reached (pcap confirmed)
 *   v3 — targeted MediaXPauseSession.started() → blocks Glide fetch at
 *         source; confirmed via pcap that this is where the image request
 *         originates (prod-frida-origin.bamgrid.com fires at +26.10s)
 */

package ajstrick81.morphe.patches.disney

import app.morphe.patcher.Fingerprint

// ---------------------------------------------------------------------------
// Legacy pipeline — validated present and structurally unchanged in
// com.dss.sdk.internal.media.Insertion through v26.12.1+rc1-2026.07.15
// ---------------------------------------------------------------------------

internal object InsertionGetPointsFingerprint : Fingerprint(
    returnType = "Ljava/util/List",
    custom = { method, _ ->
        method.name == "getPoints" &&
            method.definingClass == "Lcom/dss/sdk/internal/media/Insertion;"
    },
)

internal object InsertionGetRangesFingerprint : Fingerprint(
    returnType = "Ljava/util/List",
    custom = { method, _ ->
        method.name == "getRanges" &&
            method.definingClass == "Lcom/dss/sdk/internal/media/Insertion;"
    },
)

// ---------------------------------------------------------------------------
// NEW pipeline (v26.9.2+): Disney Media Platform ("dmp") PlayBack-Orchestration
// ("pbo") insertion container. The player migrated off the legacy DSS-SDK
// Insertion (above) to com.disney.dmp.internal.pbo.Insertion — a @Keep Moshi
// data class with the same getPoints()/getRanges() shape (mode = SGAI/SSAI).
// The ad-break builder iterates getPoints(), casts each to InsertionPoint and
// branches on `instanceof Sgai/SsaiVodInsertionPoint`; emptying getPoints()/
// getRanges() here yields zero ad cues → no SGAI/SSAI VOD ads (pre- and mid-roll),
// with no DNS dependency. Verified on Onn 4K TV (v26.9.2+rc1). This is why the
// legacy-only patch still applied yet SGAI ads leaked through.
// ---------------------------------------------------------------------------

internal object DmpInsertionGetPointsFingerprint : Fingerprint(
    returnType = "Ljava/util/List",
    custom = { method, _ ->
        method.name == "getPoints" &&
            method.definingClass == "Lcom/disney/dmp/internal/pbo/Insertion;"
    },
)

internal object DmpInsertionGetRangesFingerprint : Fingerprint(
    returnType = "Ljava/util/List",
    custom = { method, _ ->
        method.name == "getRanges" &&
            method.definingClass == "Lcom/disney/dmp/internal/pbo/Insertion;"
    },
)

// ---------------------------------------------------------------------------
// Pause ad fingerprint — MediaXPauseSession.started()
//
// Network analysis (PCAPdroid) confirmed the pause ad image is fetched from
// prod-frida-origin.bamgrid.com at +26.10s after pause, triggered by a
// Glide image load inside MediaXPauseSession.started(). This is the correct
// interception point — earlier in the chain (createPauseSession, onPauseScheduled)
// does not prevent the network fetch.
//
// Smali:
//   const-string v0, "pauseData"                   ← Kotlin null-check label
//   invoke-static v2, v0, kotlin/jvm/internal/k->g
//   invoke-virtual v2, MediaXPause;->into()Lbv0/m; ← builds Glide render model
//   iput-object v2, v1, ...->pause                 ← stores model
//   getStarted().onNext(kotlin/Unit)               ← triggers overlay render
//
// Patching strategy: return-void at offset 0.
//   - into() is never called → no Glide request → no image fetched
//   - prod-frida-origin.bamgrid.com is never contacted
//   - beacons.digital.disneyadvertising.com impression beacon never fires
//   - ad.doubleclick.net measurement never fires
//   - Overlay render event never published
//
// Anchor: "pauseData" is a Kotlin compiler null-check label derived from the
// source parameter name — stable across ProGuard minification.
// The class and method names are unobfuscated source-level names.
// ---------------------------------------------------------------------------

internal object PauseAdStartedFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("pauseData"),
    custom = { method, _ ->
        method.name == "started" &&
            method.definingClass ==
                "Lcom/disneystreaming/nve/player/mel/MediaXPauseSession;"
    },
)

// ---------------------------------------------------------------------------
// Out-of-household ("Verify Household Network") decision — OPT-IN suppression.
//
// The household prompt is a startup-flow gate in
// com.bamtechmedia.dominguez.accountsharing. The app resolves the device's
// household status through ONE authoritative suspend predicate (a boxed Boolean
// meaning "device is OUT of household"):
//
//   v26.12.1:  Lv8/x;->y(Lv81/c;)Ljava/lang/Object;
//   v26.16.0:  Lg11/o;->A(Lt91/c;)Ljava/lang/Object;   ← current pin
//       ≈  (firstCheck && !secondCheck)  →  "device is OUT of household"
//          firstCheck  = Lg00/f;->x(..)   (boxed Boolean)
//          secondCheck = Lur0/q;->c(..)   (boxed Boolean)
//
// Every household/verify routing site funnels through this single method —
// confirmed on 26.16.0 by listing its callers:
//   - Lqz/e1; (startup destination resolver, was Lzy/c1;) — two sites: true →
//     returns the OutOfHouseholdBlock destination (Lqz/i0;->c, whose toString()
//     is "OutOfHouseholdBlock") instead of continuing to home.
//   - Lsf1/a; (was Ln00/d;) — true → navigates to the "verifyDevice" route.
//   - Lr50/o; (was Lo1/t;) — same boolean gate.
// (Lnf/a; is A()'s own coroutine continuation, not a distinct caller — was Lkf/b;.)
//
// Forcing the predicate to return Boolean.FALSE makes every caller see an
// in-household device, so none of the block/verify screens are ever routed to
// and the app proceeds to home exactly as it does for a normal in-household
// device. This is behaviour-neutral for users who are already in-household (they
// already get false here); it only changes the out-of-household path.
//
// ⚠️ OBFUSCATION-PINNED: the class (Lg11/o;, an R8 horizontally-merged synthetic)
// and the method letter ("A") are R8-minified names, exact for
// v26.16.0+rc2-2026.09.08 (versionCode 1788909180). They WILL drift on app
// updates — re-resolve against a fresh decompile and re-pin on each version bump
// (the patch fails loud if the fingerprint no longer resolves). The stable
// anchors to re-locate it from: the singleton whose toString() is
// "OutOfHouseholdBlock" (26.16.0: Lqz/i0; field c), the "verifyDevice" nav string
// in the device-verify caller, and the startup destination resolver's kept
// @Inject param-name strings ("sessionStateRepository",
// "completeProfileStateProvider", "huluLinkEligibleProfilesRepository",
// "sessionConfig") — the resolver class that has all four references the
// OutOfHouseholdBlock destination singleton and calls this predicate; the
// predicate is the boxed-Boolean suspend method it casts + branches on.
//
// HONEST SCOPE: this suppresses only the CLIENT-side prompt/routing. Disney's
// out-of-household detection is server/IP-driven; if the server also refuses to
// serve the stream to an out-of-household device, hiding the prompt will not by
// itself restore playback. Verified reachable in bytecode; effect must be
// confirmed by a user actually in a flagged out-of-household state.
// ---------------------------------------------------------------------------

internal object OutOfHouseholdCheckFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        method.name == "A" &&
            method.definingClass == "Lg11/o;"
    },
)
