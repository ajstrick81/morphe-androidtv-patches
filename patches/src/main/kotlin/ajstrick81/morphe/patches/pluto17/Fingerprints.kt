package ajstrick81.morphe.patches.pluto17

// Pluto TV 17.x support contributed by Mathias Thur (github.com/AllGray), from his fork
// AllGray/morphe-androidtv-patches. Tested on-device by him (Sony BRAVIA, Android 9). Thank you!

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// AviaDAIResourceProvider.shouldPlayAd(AviaAdPod): Z — same anchor as the Paramount+ patch (readable
// method name + unique leaf class name); in Pluto 17.2.0 it is private, boolean, and present in both builds.
// Kept as a harmless extra: on-device, Pluto 17 VOD does not play through the DAI provider.
internal object ShouldPlayAd17Fingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, _ ->
        method.name == "shouldPlayAd" &&
            method.definingClass.endsWith("/AviaDAIResourceProvider;")
    },
)

// media3's DashManifestParser.parse(Uri, InputStream): DashManifest. R8-renamed in Pluto 17 (17.2.0 TV:
// Ldz3;->c(Landroid/net/Uri;Ljava/io/InputStream;)Lbz3;), so it is found by media3's own error literal,
// which only this method throws, plus its (Uri, InputStream) parameters.
internal object DashManifestParse17Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/net/Uri;", "Ljava/io/InputStream;"),
    strings = listOf("inputStream does not contain a valid media presentation description"),
)

// Pause ads: CbsPauseWithAdsOverlay's render step, called with each new overlay state. In Pluto 17 it lost
// the " not updating overlay." log line the Paramount+ patch anchors on and is R8-renamed (17.2.0 TV:
// static H(CbsPauseWithAdsOverlay, Lo32;)V, which sizes the image and fades it in via G(J)). Found as the
// class's only static void method taking the overlay itself plus one state object. TV build only.
internal object PauseAdsRender17Fingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.definingClass.endsWith("/CbsPauseWithAdsOverlay;") &&
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0].toString() == method.definingClass
    },
)

// Pluto's stitcher session, StitcherSession(id, cdn, …, adBreak, adBreaks, adBreakLocations, clips, …) (17.2.0 TV: Lv1h;).
// The player's content timeline (duration, time left) and the ad countdown are worked out from its ad breaks, so with the
// ad periods gone from the stream they showed the wrong times. Found by its data-class toString.
internal object StitcherSessionToString17Fingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("StitcherSession(id="),
    custom = { method, _ -> method.name == "toString" },
)

// Pluto's ad-break timeline, AdsData(adBreaks: List, ...): a Kotlin data class (17.2.0 TV: Lgm;) whose
// toString() spells its field names. Found by that literal; its constructor is then edited (see the patch).
internal object AdsDataToString17Fingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("AdsData(adBreaks="),
    custom = { method, _ -> method.name == "toString" },
)
