package ajstrick81.morphe.extension.peacock.ads

import okhttp3.OkHttpClient

/**
 * Static wrapper invoked from smali to replace the entire body of
 * NetworkingKt.getOkHttpClient() with a no-arg static call.
 *
 * Strategy — method body replacement at offset 0:
 *   invoke-static {}, PeacockAdPatchHelper;->buildOkHttpClient()LOkHttpClient;
 *   move-result-object v0
 *   return-object v0
 *
 * Why offset 0 with no args:
 *   All previous attempts inserted mid-method (offset 5) and passed v0
 *   (the Builder) as an argument. The ART verifier rejected this with
 *   type=Undefined (v1.4.56) and type=Conflict (v1.4.57) because inserting
 *   instructions mid-method leaves the verifier's register type-tracking
 *   in an ambiguous state at the merge point.
 *
 *   Inserting at offset 0 with {} (no register arguments) is unconditionally
 *   safe — no registers are live yet, so the verifier has nothing to conflict.
 *   move-result-object v0 assigns a fresh object into an uninitialized register,
 *   which the verifier always accepts. return-object v0 follows cleanly.
 *
 *   The original method body (Builder + OkHttpWorkaroundInterceptor + build())
 *   is never reached. buildOkHttpClient() replicates it in full, adding
 *   AdBlockInterceptor first so both interceptors are chained.
 */
object PeacockAdPatchHelper {

    @JvmStatic
    fun buildOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AdBlockInterceptor())
            .addInterceptor(OkHttpWorkaroundInterceptor())
            .build()
    }

    /**
     * Adds AdBlockInterceptor to the Sky Core Player SDK's addon-network
     * OkHttpClient.Builder (com.sky.core.player.sdk.addon.networkLayer.
     * NativeNetworkApi) before it's built.
     *
     * This client is independent from NetworkingKt.getOkHttpClient() above —
     * it's derived via OkHttpClient.newBuilder() inside NativeNetworkApi's
     * own constructor and carries all Sky SDK addon traffic (FreeWheel ad
     * decisioning, Conviva/Comscore/Nielsen measurement pixels delivered
     * dynamically via the ad-config response, MediaTailor telemetry).
     * AdBlockInterceptor was previously only wired into the app's main
     * client, leaving this entire addon traffic path unfiltered — confirmed
     * via AdGuard Home query logs showing sas.peacocktv.com, fwmrm.net, and
     * third-party measurement domains still resolving and being requested
     * with the patched APK installed, even though those hostnames were
     * already present in AdBlockInterceptor's domain lists.
     */
    @JvmStatic
    fun addAdBlockInterceptor(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder.addInterceptor(AdBlockInterceptor())
    }
}
