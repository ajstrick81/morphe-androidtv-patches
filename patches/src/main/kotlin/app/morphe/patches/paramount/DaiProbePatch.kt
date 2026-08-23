/*
 * Paramount+ Android TV — DAI Probe  (DIAGNOSTIC, OPT-IN, default OFF)
 *
 * NOT an ad patch. This is a capture tool for investigating the live-sports
 * "Commercial in Progress" slate mechanism (see ParamountPatch.kt, Patch 3).
 *
 * It prepends a LOG-ONLY pass at AviaNetworkInterceptor.intercept(Chain):
 * every request whose URL contains "/linear/pods/v1/" is logged verbatim to
 * logcat under the tag "MorpheDaiProbe", then the ORIGINAL interceptor body
 * runs unchanged. Nothing is rewritten — the app plays its real ad pods — so a
 * capture reveals the ground truth we can't otherwise see:
 *
 *   • how many ad SLOTS a break has (<pod>/<slot>/<adIdx>/...)
 *   • how many SEGMENTS each slot has (the max N in /<hash>/N.ts)
 *   • the per-pod/per-slate HASHES (to check the rewrite's hash-keying)
 *   • whether segment URLs carry their own "?d=" duration (and its value)
 *
 * That is the data needed to replace the hardcoded "?d=4972" in Patch 3 with a
 * per-event derived duration.
 *
 * USAGE (build locally, run EXCLUSIVELY so the real rewrite is off):
 *   ./testing/scripts/patch.sh paramount apks/paramount-16.17.0.apkm \
 *       --exclusive -e "Paramount+ DAI Probe (diagnostic)"
 *   ./testing/scripts/deploy.sh paramount
 *   ./testing/scripts/capture-dai.sh          # then scrub to a commercial break
 *   ./testing/scripts/parse-dai-probe.py testing/out/dai-probe-*.log
 *
 * This patch is default = false and never ships in the release bundle unless a
 * user explicitly enables it, and it changes no playback behaviour.
 *
 * Register note: intercept() carries the Chain in a high param register (p1),
 * so it is moved to v0 (move-object/from16) before any invoke — a bare
 * invoke-interface {p1} would exceed the 4-bit argument-register limit. v0..v2
 * are scratch and dead once we fall through to the original body (which
 * re-initializes its own registers), matching the ":..._skip nop" idiom used by
 * the shipped patch.
 */

package app.morphe.patches.paramount

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

// A dedicated fingerprint instance (identical matcher to the shipped one) so the
// probe is fully independent of Patch 3 and safe to run on its own.
internal object DaiProbeInterceptorFingerprint : Fingerprint(
    returnType = "Lokhttp3/Response;",
    custom = { method, _ ->
        method.name == "intercept" &&
            method.definingClass.endsWith("/network/AviaNetworkInterceptor;")
    },
)

@Suppress("unused")
val daiProbePatch = bytecodePatch(
    name = "Paramount+ DAI Probe (diagnostic)",
    description = "Diagnostic only — does NOT block ads. Logs every DAI " +
        "/linear/pods/v1/ request URL to logcat (tag MorpheDaiProbe) with playback " +
        "unchanged, so a commercial-break capture reveals the real ad-pod slot/segment " +
        "layout, hashes, and durations. Run exclusively; off by default.",
    default = false,
) {
    compatibleWith(AppCompatibilities.PARAMOUNT_TV)

    execute {
        DaiProbeInterceptorFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                invoke-interface {v0}, Lokhttp3/Interceptor${'$'}Chain;->request()Lokhttp3/Request;
                move-result-object v1
                invoke-virtual {v1}, Lokhttp3/Request;->url()Lokhttp3/HttpUrl;
                move-result-object v1
                invoke-virtual {v1}, Lokhttp3/HttpUrl;->toString()Ljava/lang/String;
                move-result-object v1
                const-string v2, "/linear/pods/v1/"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v2
                if-eqz v2, :probe_skip
                const-string v2, "MorpheDaiProbe"
                invoke-static {v2, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
                :probe_skip
                nop
            """.trimIndent(),
        )
    }
}
