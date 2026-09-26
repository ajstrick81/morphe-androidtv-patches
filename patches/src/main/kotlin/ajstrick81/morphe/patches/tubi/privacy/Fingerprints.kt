package ajstrick81.morphe.patches.tubi.privacy

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// SDK entry points below are public API, which the SDKs' consumer ProGuard
// rules keep un-obfuscated, so they match across Tubi versions (verified
// against the 10.36.5000 Android TV build).

// Adjust 5.x — the SDK never starts, so it never reads ANDROID_ID / GAID or
// sends attribution events. Later Adjust.trackEvent() etc. only log
// "SDK not initialised".
object AdjustInitSdkFingerprint : Fingerprint(
    definingClass = "Lcom/adjust/sdk/Adjust;",
    name = "initSdk",
    parameters = listOf("Lcom/adjust/sdk/AdjustConfig;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

// Adjust 4.x equivalent of initSdk, for older Tubi builds.
object AdjustOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/adjust/sdk/Adjust;",
    name = "onCreate",
    parameters = listOf("Lcom/adjust/sdk/AdjustConfig;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

// OkHttpClient(Builder) — both Builder.build() and the no-arg OkHttpClient()
// construct through here, so hooking it covers every OkHttp client.
object OkHttpClientInitFingerprint : Fingerprint(
    definingClass = "Lokhttp3/OkHttpClient;",
    name = "<init>",
    parameters = listOf("Lokhttp3/OkHttpClient\$Builder;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
)
