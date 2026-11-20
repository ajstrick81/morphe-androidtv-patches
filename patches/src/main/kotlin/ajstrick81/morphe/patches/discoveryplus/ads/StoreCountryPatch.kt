package ajstrick81.morphe.patches.discoveryplus.ads

import ajstrick81.morphe.patches.discoveryplus.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption

// ─────────────────────────────────────────────────────────────────────────────
// discovery+ — Store Country Override  (OPT-IN)
//
// The welcome screen's options are chosen server-side from the Google Play storefront
// country, which the app reads through Play Billing (GoogleIapHelper.getStoreMarketPlace
// → IapManager.onStoreMarketplaceRetrievalCompleted → native PlatformIAPBroker). The IP
// address alone does not decide it: on a US Play account the stock app shows only
// "Subscribe" / "Sign in" even on an Italian connection, and the Italian free tier
// ("Naviga gratis") appears only when the storefront country is IT (#334).
//
// Verified on an onn 4K Plus (22.1.0.71, Italy VPN, US Play account): forcing "IT" here
// brings back "Naviga gratis". The free tier still needs an Italian IP address.
//
// Opt-in because it changes more than ads: the storefront country also drives the
// in-app subscription offers, so a subscriber in another region should leave it off.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val discoveryPlusStoreCountryPatch = bytecodePatch(
    name = "discovery+ - Store Country Override",
    description = "Reports a fixed Google Play store country to discovery+ (default IT). " +
        "With an Italian IP address this brings back the free 'Naviga gratis' (browse " +
        "for free) tier, which otherwise depends on your Play account's country. Off by " +
        "default: it also changes the subscription offers shown in the app.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    val country by stringOption(
        key = "country",
        default = "IT",
        values = mapOf("Italy" to "IT"),
        title = "Store country",
        description = "Two-letter country code to report as the Google Play store country.",
        required = true,
        validator = { value -> value != null && Regex("^[A-Z]{2}$").matches(value) },
    )

    execute {
        // Instance method (Result, String)V with .locals 2: replace the billing result with a
        // success (response code 0) and the country with the chosen code, before the app
        // logs and forwards them.
        DplayStoreMarketplaceFingerprint.method.addInstructions(
            0,
            """
                new-instance p1, Ltv/youi/InAppPurchase/Result;
                const/4 v0, 0x0
                const-string v1, "Store country set by Morphe patch"
                invoke-direct {p1, v0, v1}, Ltv/youi/InAppPurchase/Result;-><init>(ILjava/lang/String;)V
                const-string p2, "$country"
            """.trimIndent(),
        )
    }
}
