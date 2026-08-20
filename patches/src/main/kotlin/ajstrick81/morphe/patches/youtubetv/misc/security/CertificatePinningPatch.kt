package ajstrick81.morphe.patches.youtubetv.misc.security

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.youtubetv.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Override certificate pinning (YouTube TV / Unplugged) — CAPTURE tooling
//
// Companion to experimental/youtubetv-scte35/ and
// docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md. Repo-consistent (resourcePatch)
// version of experimental/youtubetv-scte35/network_security_config.xml (Route B
// in that dir's README), modeled on vix/pluto CertificatePinningPatch.
//
// WHAT IT DOES
//   res/xml/network_security_config.xml — written authoritative:
//     - base-config trust-anchors = system + user(overridePins)  → opens the
//       PLATFORM TLS stack to an HTTPS-inspection proxy (mitmproxy/AdGuard)
//     - explicit domain-configs (googlevideo/youtube/googleapis/doubleclick/…)
//       so pins on those hosts are overridden too
//     - cleartext stays false (inspection is still TLS)
//   AndroidManifest.xml — <application android:networkSecurityConfig> repointed
//     at our file (setNamedItem replaces any existing reference, so ours wins
//     whether or not YouTube TV ships its own NSC).
//
// CRITICAL LIMIT — read before relying on this
//   This only opens the JAVA/platform TLS stack. YouTube's media + player-response
//   fetches ride NATIVE Cronet (libcronet…so), which does NOT consult
//   network_security_config. To intercept those you MUST also run the
//   BoringSSL unpin in experimental/youtubetv-scte35/frida/yttv_tap.js
//   (SSL_[CTX_]set_custom_verify → ssl_verify_ok). Ship both, or you capture
//   only half the traffic. (This is the Prime-Video-style native-stack wall the
//   vix/pluto notes describe — here it's Cronet.)
//
// This patch removes no ads by itself; it is an optional capture/RE adjunct that
// only matters when a proxy with its CA installed is in the path. Overwriting the
// standard-named file plus repointing the manifest makes our config authoritative
// in the shipped-or-not case; any orphaned original file is harmless.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val certificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Makes YouTube TV trust user-installed CAs (system + user, overridePins) so an " +
        "HTTPS-inspection proxy can read the DASH manifest and ad player-response on the platform " +
        "TLS stack. Capture/RE adjunct — pair with the native Cronet unpin for full coverage. " +
        "Removes no ads on its own.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // ── Step 1 — write an authoritative network_security_config.xml ──────
        get("res/xml/network_security_config.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" overridePins="true" />
        </trust-anchors>
    </base-config>
    <domain-config>
        <domain includeSubdomains="true">googlevideo.com</domain>
        <domain includeSubdomains="true">youtube.com</domain>
        <domain includeSubdomains="true">googleapis.com</domain>
        <domain includeSubdomains="true">google.com</domain>
        <domain includeSubdomains="true">ytimg.com</domain>
        <domain includeSubdomains="true">doubleclick.net</domain>
        <domain includeSubdomains="true">googlesyndication.com</domain>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" overridePins="true" />
        </trust-anchors>
    </domain-config>
</network-security-config>"""
        )

        // ── Step 2 — point <application> at our config (replace if present) ──
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
