package ajstrick81.morphe.patches.pluto.misc.security

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.pluto.shared.Constants
import org.w3c.dom.Element

// ─────────────────────────────────────────────────────────────────────────────
// Certificate-pinning / trust override for HTTPS inspection
//
// Purpose: let an HTTPS-filtering proxy (e.g. AdGuard Premium) with its own CA
// installed on the device intercept Pluto's traffic — the prerequisite for the
// HLS manifest `$replace` (SCTE-35 CUE-OUT/IN) ad-suppression approach.
//
// Why Pluto is a good candidate (confirmed by decompiling 5.66.0-leanback):
//   - All of Pluto's traffic rides the Java/platform TLS stack — OkHttp,
//     Volley, and media3's DefaultHttpDataSource. There is NO native (libcurl/
//     Cronet) HTTP stack, so unlike Prime Video the media/manifest plane is
//     reachable by an Android trust-config change. (Prime's ad plane is native
//     libcurl with its own CA bundle and rejected interception — CURL err 35.)
//   - No active certificate pinning found: zero hardcoded pin hashes, no
//     app-owned TrustManager, and no <pin-set> in the shipped config. The only
//     CertificatePinner references are OkHttp's bundled class.
//
// Why this is Pluto-specific (NOT the generic create-a-config patch):
//   Pluto ALREADY ships res/xml/network_security_config.xml, and the manifest
//   already references it, so we must EDIT it, not create one. Critically, its
//   single <domain-config> lists `pluto.tv` (includeSubdomains) — the stitcher
//   / manifest host — with `system`-only trust anchors and no <base-config>.
//   On targetSdk 35 that means user CAs are trusted NOWHERE, and the manifest
//   host specifically is system-only. So we must inject user-CA trust into:
//     1. a new <base-config>  (covers CDN/segment hosts not listed explicitly)
//     2. EVERY existing <domain-config>  (covers pluto.tv itself)
//   `overridePins="true"` is added defensively in case a future build adds pins.
//
// Optional adjunct (OPT-IN, default OFF): independent of the Skip ads patch. It does not remove any
// ads by itself — it only opens the platform TLS stack so the proxy layer can
// see and rewrite the HLS manifest.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val certificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Edits Pluto's network_security_config to trust user CAs (system + user, " +
        "overridePins) on both a new base-config and every existing domain-config — including " +
        "the pluto.tv entry that is otherwise system-only — so an HTTPS-filtering proxy can " +
        "inspect the HLS manifest. Optional adjunct: removes no ads by itself and is not needed " +
        "for the in-app ad removal. Opt-in. Security trade-off: the app will also trust ANY user-installed CA, so leave it off unless you run an HTTPS-filtering proxy.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Pluto ships this file and the manifest already references it, so we
        // edit in place. (If a future build drops it, this call would fail and
        // signal that the create-a-config path is needed instead.)
        document("res/xml/network_security_config.xml").use { document ->
            val root = document
                .getElementsByTagName("network-security-config")
                .item(0) as Element

            fun firstChildElement(parent: Element, tag: String): Element? {
                val children = parent.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element && node.tagName == tag) return node
                }
                return null
            }

            // Ensure <config>/<trust-anchors> contains <certificates src="user"
            // overridePins="true">, preserving existing system trust.
            fun ensureUserTrust(config: Element) {
                var anchors = firstChildElement(config, "trust-anchors")
                if (anchors == null) {
                    anchors = document.createElement("trust-anchors")
                    config.appendChild(anchors)
                    anchors.appendChild(
                        document.createElement("certificates").apply {
                            setAttribute("src", "system")
                        },
                    )
                }

                // If a user entry already exists, just force overridePins on it.
                val certs = anchors.childNodes
                for (i in 0 until certs.length) {
                    val node = certs.item(i)
                    if (node is Element && node.tagName == "certificates" &&
                        node.getAttribute("src") == "user"
                    ) {
                        node.setAttribute("overridePins", "true")
                        return
                    }
                }

                anchors.appendChild(
                    document.createElement("certificates").apply {
                        setAttribute("src", "user")
                        setAttribute("overridePins", "true")
                    },
                )
            }

            // 1) base-config — fallback trust for every host not in an explicit
            //    domain-config (segment/CDN hosts). Pluto ships none, so create
            //    it. Keep the platform default of no cleartext; we only add
            //    user-CA trust, we do not loosen cleartext policy.
            var baseConfig = firstChildElement(root, "base-config")
            if (baseConfig == null) {
                baseConfig = document.createElement("base-config").apply {
                    setAttribute("cleartextTrafficPermitted", "false")
                }
                root.appendChild(baseConfig)
            }
            ensureUserTrust(baseConfig)

            // 2) every existing domain-config — including the pluto.tv entry that
            //    is system-only and therefore un-interceptable without this.
            val domainConfigs = root.getElementsByTagName("domain-config")
            for (i in 0 until domainConfigs.length) {
                (domainConfigs.item(i) as? Element)?.let(::ensureUserTrust)
            }
        }
    }
}
