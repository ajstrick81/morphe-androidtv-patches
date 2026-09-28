package ajstrick81.morphe.patches.primevideo.misc.security

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.primevideo.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Certificate Pinning Override
//
// Prime Video ATV does not ship a network_security_config.xml — this patch
// creates one and references it in AndroidManifest.xml.
//
// What this enables:
//   HTTPS inspection tools (AdGuard Premium) can intercept connections made
//   through Android's PLATFORM TLS stack (HttpsURLConnection, OkHttp, WebView,
//   Volley's HurlStack) when their CA certificate is installed on the device.
//   Without this patch, apps targeting API 24+ trust only system CAs, so the
//   platform stack rejects interception proxies even when a user CA is present.
//
// What this creates:
//   res/xml/network_security_config.xml:
//     - Trusts user-installed CA certificates (required for AGP HTTPS filtering)
//     - Removes any hardcoded certificate pin sets
//     - Applies to all domains (base-config)
//
//   AndroidManifest.xml:
//     - Adds android:networkSecurityConfig="@xml/network_security_config"
//       to the <application> tag
//
// Scope and limitations (confirmed by on-device logcat, 2026-06-26):
//   This config governs ONLY the Java/platform TLS stack. Prime Video ATV
//   fetches its ad/media/API/DRM traffic through a NATIVE libcurl stack (the
//   "DOWNLOADER"), which carries its own CA bundle and ignores the Android
//   user-trust store this patch edits. Evidence: the SSAI host
//   ters-sgai1.us-east-1.aiv-delivery.net failed with CURL error 35
//   (SSL_CONNECT_ERROR) — AGP's interception cert was rejected by libcurl.
//   AGP therefore CANNOT inspect the native ad plane regardless of this patch.
//
//   Reliable suppression of api.us-east-1.aiv-delivery.net is DNS/connection-
//   level blocking (CURL error 7, "connect refused"), which works below TLS and
//   needs no interception — see dns/prime-video.txt. It does not depend on this
//   patch.
//
// Note: optional adjunct, independent of the Skip ads patch. It only helps AGP
// path-filter traffic on the platform TLS stack; for Prime Video ATV that
// surface is thin (most traffic is native, or already hooked in-process by
// enforceAdBlock). The DNS layer is the workhorse for the native plane.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val certificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Adds a network_security_config trusting user CAs (no pin sets) so AdGuard Premium can inspect the app's platform-stack HTTPS. Optional adjunct: removes no ads by itself and is not needed for the native in-app ad strip. Opt-in. Security trade-off: the app will also trust ANY user-installed CA, so leave it off unless you run an HTTPS-filtering proxy.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // ─────────────────────────────────────────────────────────────────────
        // Step 1 — Create network_security_config.xml
        //
        // Trusts user-installed CAs and removes any pin-set constraints.
        // This is the standard approach used across the patching community
        // for enabling HTTPS inspection on apps targeting API 24+.
        // ─────────────────────────────────────────────────────────────────────
        get("res/xml/network_security_config.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <!-- Trust system CA certificates (default) -->
            <certificates src="system" />
            <!-- Trust user-installed CA certificates (required for AGP HTTPS inspection) -->
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>"""
        )

        // ─────────────────────────────────────────────────────────────────────
        // Step 2 — Reference the new config in AndroidManifest.xml
        //
        // Adds android:networkSecurityConfig="@xml/network_security_config"
        // to the <application> tag so Android loads our config at runtime.
        // ─────────────────────────────────────────────────────────────────────
        document("AndroidManifest.xml").use { document ->
            val applicationNode = document
                .getElementsByTagName("application")
                .item(0)

            applicationNode
                .attributes
                .setNamedItem(
                    document.createAttribute("android:networkSecurityConfig").also {
                        it.value = "@xml/network_security_config"
                    }
                )
        }
    }
}
