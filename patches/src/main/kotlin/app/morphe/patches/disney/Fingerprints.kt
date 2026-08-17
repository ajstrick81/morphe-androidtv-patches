/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/disneyplus/ads/Fingerprints.kt
 * ALL CREDIT GOES TO RookieEnough FOR THE ORIGINAL CODE
 *
 * Verified on Disney+ Android TV v26.12.1+rc1-2026.07.15 (versionCode 1784077450)
 * — all fingerprints resolve unchanged; on-device pre- and mid-roll ads gone for
 * both movies and TV (AGH off). Previously validated on v26.8.0+rc6 (1779314460)
 * and the DMP-pipeline fix landed on v26.9.2+rc1 (1781224190).
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

package app.morphe.patches.disney

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
// com.bamtechmedia.dominguez.accountsharing. On v26.12.1 the app resolves the
// device's household status through ONE authoritative suspend predicate:
//
//   Lv8/x;->y(Lv81/c;)Ljava/lang/Object;   (returns a boxed Boolean)
//       ≈  (firstCheck && !secondCheck)  →  "device is OUT of household"
//
// Every household/verify routing site funnels through this single method:
//   - Lzy/c1; (startup destination resolver) — two sites: true → returns the
//     OutOfHouseholdBlock destination (Lzy/h0;->c) instead of continuing to home.
//   - Ln00/d; — true → navigates to the "verifyDevice" route.
//   - Lo1/t; — same boolean gate.
// (Lkf/b; is y()'s own coroutine continuation, not a distinct caller.)
//
// Forcing y() to return Boolean.FALSE makes every caller see an in-household
// device, so none of the block/verify screens are ever routed to and the app
// proceeds to home exactly as it does for a normal in-household device. This is
// behaviour-neutral for users who are already in-household (they already get
// false here); it only changes the out-of-household path.
//
// ⚠️ OBFUSCATION-PINNED: Lv8/x; and the method name "y" are R8-minified names,
// exact for v26.12.1+rc1-2026.07.15 (versionCode 1784077450). They WILL drift on
// app updates — re-resolve against a fresh decompile and re-pin on each version
// bump (the patch fails loud if the fingerprint no longer resolves). The stable
// anchors to re-locate it from: the singleton whose toString() is
// "OutOfHouseholdBlock" (Lkh/t;/Lzy/h0;), the "verifyDevice" nav string in the
// n00/d caller, and the router constructor's kept param-name strings
// ("sessionStateRepository", "completeProfileStateProvider",
// "huluLinkEligibleProfilesRepository", "sessionConfig").
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
        method.name == "y" &&
            method.definingClass == "Lv8/x;"
    },
)
