package ajstrick81.morphe.extension.peacock.ads

import okhttp3.OkHttpClient

/**
 * Static helpers invoked from injected smali to wire AdBlockInterceptor into
 * the app's OkHttpClient builders.
 *
 * Both Layer 6 (the app's shared client) and Layers 9/11 (Sky SDK clients) now
 * use the same strategy: locate the OkHttpClient$Builder.build() call in the
 * target method and insert addAdBlockInterceptor(builder) immediately before it
 * (see SkipAdsPatch.injectAdBlockBeforeOkHttpBuild). This preserves every piece
 * of the original client's configuration (timeouts, TLS, cookie jar, auth/
 * header interceptors, DNS) and only adds AdBlockInterceptor.
 *
 * An earlier approach replaced NetworkingKt.getOkHttpClient()'s body wholesale
 * with a bare client. That discarded the real client configuration once the
 * Layer 6 fingerprint re-anchored onto 7.10.102's shared-client builder, which
 * broke fresh sign-in (issue #230); the body-replacement helper was removed.
 */
object PeacockAdPatchHelper {

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
