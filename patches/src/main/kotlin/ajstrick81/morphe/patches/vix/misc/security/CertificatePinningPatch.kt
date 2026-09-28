package ajstrick81.morphe.patches.vix.misc.security

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.vix.shared.Constants

// ─────────────────────────────────────────────────────────────────────────────
// Certificate Pinning Override (ViX Android TV)
//
// Companion to the AdGuard/AGP SSAI ruleset (see adguard/vix_ssai_v1_0_agp.txt).
// ViX live ads are server-side stitched (true SSAI) via Google DAI Pod Serving,
// relayed through Lura (dcs4-live.mp.lura.live/server/podserve2/...). The
// stream is decisioned by a DAI session call (dai.google.com/ssai/pods/...)
// that the player makes before requesting the manifest — block that call and
// Lura falls back to a content-only manifest (confirmed via HAR capture: the
// fallback uses gam_stream_id=mock-stream with no playback disruption). None
// of this is reachable by a bytecode hook, only at the network layer. But
// apps targeting API 24+ only trust SYSTEM CAs by default, so an
// HTTPS-inspection proxy's user-installed CA is rejected and the proxy never
// sees ViX traffic. This patch makes ViX trust user CAs so AdGuard (or
// mitmproxy) can see and block the DAI session call.
//
// What this creates / changes:
//   res/xml/network_security_config.xml:
//     - base-config trust-anchors = system + user (enables HTTPS inspection)
//     - no <pin-set> entries (any manifest-level pins are dropped)
//   AndroidManifest.xml:
//     - sets/replaces android:networkSecurityConfig on <application> to point
//       at the config above. setNamedItem replaces an existing attribute, so
//       this also overrides whatever NSC ViX already ships.
//
// ViX-specific notes:
//   - Unlike Prime Video, ViX DOES reference xml resources (a network security
//     config is likely already present). Overwriting the standard-named file
//     and repointing the manifest attribute makes our config authoritative in
//     both cases (shipped-or-not), and any orphaned original file is harmless.
//   - The only in-code TLS pin found in the dex belongs to PerimeterX
//     (com/perimeterx/mobile_sdk, sha256/V5L96...), an OkHttp CertificatePinner
//     that network_security_config does NOT override. That is intentional and
//     fine: PerimeterX is bot-defense for login, NOT the video/ad path, and the
//     SSAI ruleset deliberately avoids MITM-ing it (do not intercept PX, or you
//     risk lockouts; confirmed present in the HAR as collector-*.pxchk.net /
//     px-cdn.net / px-cloud.net). The video/ad hosts (dai.google.com,
//     pubads.g.doubleclick.net, *.mp.lura.live) are NOT pinned in code, so
//     user-CA trust is sufficient for AdGuard to see and block them.
//   - cleartextTrafficPermitted stays false — AdGuard inspection is still TLS.
//
// This patch is optional and independent of the Skip ads patch. It only has an
// effect when an HTTPS-inspection proxy with its CA installed is in the path.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val certificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Allows HTTPS traffic inspection tools like AdGuard Premium to intercept app connections. Enables ViX live (Lura SSAI) ad suppression when used alongside AdGuard Premium. Security trade-off: the app will also trust ANY user-installed CA; turn it off if you don't use an HTTPS-filtering proxy.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // ─────────────────────────────────────────────────────────────────────
        // Step 1 — Write a permissive network_security_config.xml
        //
        // Trusts user-installed CAs and declares no pin-sets. Overwrites the
        // file if ViX already ships one under this name.
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
        // Step 2 — Point the <application> tag at our config
        //
        // setNamedItem replaces an existing android:networkSecurityConfig
        // attribute, so this overrides any NSC ViX already references (and adds
        // the attribute if none was present).
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
