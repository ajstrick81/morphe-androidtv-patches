package ajstrick81.morphe.patches.netflix.misc.clone

import app.morphe.patcher.patch.resourcePatch
import ajstrick81.morphe.patches.netflix.shared.Constants
import org.w3c.dom.Element

// Suffix appended to the original applicationId for the cloned package, e.g.
// com.netflix.ninja -> com.netflix.ninja.clone
private const val CLONE_SUFFIX = "clone"

// ─────────────────────────────────────────────────────────────────────────────
// Clone Netflix (side-by-side install) — capture-tooling enabler
//
// On the Onn 4K TV (and most Android TVs / Fire TV) Netflix ships as a
// PREINSTALLED SYSTEM app: it can't be uninstalled without root, so a
// Morphe-signed build can't replace it (INSTALL_FAILED_UPDATE_INCOMPATIBLE;
// full uninstall -> DELETE_FAILED_INTERNAL_ERROR). Proven on-device, see
// experimental/netflix-native-adstrip/HANDOFF.md §0.
//
// Renaming the applicationId alone is NOT enough on two counts:
//
//   1. Device-unique identifiers. A ContentProvider's android:authorities and
//      any custom <permission> android:name must be UNIQUE device-wide,
//      independent of applicationId. They stay pinned to the original package
//      string, so PackageManager rejects the install with
//      INSTALL_FAILED_CONFLICTING_PROVIDER / duplicate-permission while the
//      stock app is present.
//
//   2. RELATIVE component names. Unlike Pluto, Netflix's manifest declares its
//      components with package-relative names (android:name=".NetflixApplication",
//      ".MainActivity", ".NetflixService", 16 in all). A relative name resolves
//      against the manifest `package` attribute AT PARSE TIME, so simply
//      changing `package` would make ".NetflixApplication" resolve to
//      <clone>.NetflixApplication — a class that does NOT exist in the DEX
//      (the bytecode is still com.netflix.ninja.*) -> ClassNotFoundException at
//      launch. We therefore EXPAND every relative android:name to its fully
//      qualified com.netflix.ninja.* form BEFORE renaming the package, pinning
//      each component to the real DEX class regardless of the new applicationId.
//
// Intent actions and <queries> package targets are left untouched: they are not
// device-unique and are matched by literal string on both ends.
//
// REQUIRED on TV boxes: Netflix ships preinstalled + Play-signed, so a
// Morphe-signed build can't replace it. This installs the patched app as a
// SEPARATE package (com.netflix.ninja.clone) alongside untouched stock. The
// clone reads STOCK's genuine signature via the injected <queries> entry to
// satisfy the native RJni_SignatureCheck anti-tamper (§1b), so stock must stay
// installed + enabled (it may auto-update freely — the clone keys off the
// signature, not the version). Verified on-device: the clone logs in, plays,
// and self-applies the ad kills. Default ON for this Android-TV target.
//
// Runs in finalize {} so the rename happens after all other patches, at manifest
// write time.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone Netflix",
    description = "Installs the patched Netflix as a separate app alongside the stock one " +
        "(package suffix .$CLONE_SUFFIX), instead of replacing it. Required on devices where " +
        "Netflix is a preinstalled system app that can't be uninstalled (Onn, Fire TV, most " +
        "Android TV boxes). Keep the stock Netflix installed + enabled — the clone borrows its " +
        "signature to pass Netflix's native tamper check (stock auto-updating is fine). You just " +
        "launch the clone instead. Recommended: leave ON.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY)

    finalize {
        val packageName = packageMetadata.packageName
        val newPackageName = "$packageName.$CLONE_SUFFIX"

        // Authorities may be an @string/... reference rather than a literal; any
        // such string resource names are collected here and rewritten below.
        val providerStringResources = mutableSetOf<String>()

        document("AndroidManifest.xml").use { document ->
            // ── 0. Expand relative component names FIRST ────────────────────
            // Must happen before the `package` attribute changes, so ".Foo"
            // pins to the ORIGINAL packageName.Foo (the real DEX class), not the
            // clone's non-existent one. Covers every element type that carries a
            // relative android:name (application/activity/service/receiver/
            // provider/activity-alias, etc.).
            val allNodes = document.getElementsByTagName("*")
            for (i in 0 until allNodes.length) {
                val node = allNodes.item(i) as? Element ?: continue
                val name = node.getAttribute("android:name")
                if (name.startsWith('.')) {
                    node.setAttribute("android:name", packageName + name)
                }
                // activity-alias targetActivity is also package-relative.
                val target = node.getAttribute("android:targetActivity")
                if (target.startsWith('.')) {
                    node.setAttribute("android:targetActivity", packageName + target)
                }
            }

            // ── 1. Rename the package ───────────────────────────────────────
            document.documentElement.setAttribute("package", newPackageName)

            // ── 1b. Grant visibility of the STOCK package ───────────────────
            // A second, NATIVE anti-tamper (libnetflix RJni_SignatureCheck.cpp,
            // separate from the DexGuard Java CertCheck) hardcodes
            //   getPackageManager().getPackageInfo("com.netflix.ninja",
            //                                       GET_SIGNATURES /*0x40*/)
            // to read + verify the app's signing cert. Under the renamed clone
            // that becomes a CROSS-package query, which Android 11+ package
            // visibility blocks -> getPackageInfo returns null -> native
            // GetObjectClass(null) -> SIGABRT ~2.5s into launch
            // (NetflixService.nativeGibbonStartup). Declaring a <queries> entry
            // for the original package makes it visible again: the query then
            // returns the STOCK Netflix's PackageInfo, whose signature is the
            // GENUINE Netflix cert, so the native check both returns non-null
            // AND passes. (packageName is still the original here.)
            val queries = (document.getElementsByTagName("queries").item(0) as? Element)
                ?: document.createElement("queries").also {
                    document.documentElement.appendChild(it)
                }
            queries.appendChild(document.createElement("package").apply {
                setAttribute("android:name", packageName)
            })

            // ── 2. Custom permissions ───────────────────────────────────────
            // A <permission> android:name must be device-unique. Rename each
            // declaration, keep matching <uses-permission> and any component
            // android:permission / android:readPermission / writePermission in
            // sync so self-guarded components still resolve.
            val permissions = document.getElementsByTagName("permission")
            val renamedPermissions = mutableMapOf<String, String>()
            for (i in 0 until permissions.length) {
                val permission = permissions.item(i) as? Element ?: continue
                val oldName = permission.getAttribute("android:name")
                if (oldName.startsWith('.')) continue
                val newName = if (oldName.startsWith("$packageName.")) {
                    oldName.replaceFirst(packageName, newPackageName)
                } else {
                    "${newPackageName}_$oldName"
                }
                permission.setAttribute("android:name", newName)
                renamedPermissions[oldName] = newName
            }

            // Propagate the permission renames to every attribute that can
            // reference a permission by name.
            if (renamedPermissions.isNotEmpty()) {
                val permAttrs = listOf(
                    "android:name",            // <uses-permission>
                    "android:permission",       // component guards
                    "android:readPermission",   // provider
                    "android:writePermission",  // provider
                )
                for (i in 0 until allNodes.length) {
                    val node = allNodes.item(i) as? Element ?: continue
                    // Skip the <permission> declarations themselves (already done).
                    if (node.tagName == "permission") continue
                    for (attr in permAttrs) {
                        val v = node.getAttribute(attr)
                        val mapped = renamedPermissions[v]
                        if (mapped != null) node.setAttribute(attr, mapped)
                    }
                }
            }

            // ── 3. Provider authorities ─────────────────────────────────────
            // android:authorities is a ';'-separated list; each entry must be
            // device-unique. Rewrite literals and defer @string references.
            val providers = document.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                val provider = providers.item(i) as? Element ?: continue
                val authorities = provider.getAttribute("android:authorities").split(';')
                val newAuthorities = authorities.map {
                    when {
                        it.startsWith("$packageName.") ->
                            it.replaceFirst(packageName, newPackageName)
                        it.startsWith('@') -> {
                            providerStringResources.add(it.removePrefix("@string/"))
                            it
                        }
                        else -> "${newPackageName}_$it"
                    }
                }
                provider.setAttribute("android:authorities", newAuthorities.joinToString(";"))
            }
        }

        // ── 4. @string-backed authorities ───────────────────────────────────
        if (providerStringResources.isNotEmpty()) {
            document("res/values/strings.xml").use { document ->
                val children = document.documentElement.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i) as? Element ?: continue
                    if (node.getAttribute("name") in providerStringResources) {
                        val authority = node.textContent
                        node.textContent = if (authority.startsWith("$packageName.")) {
                            authority.replaceFirst(packageName, newPackageName)
                        } else {
                            "${newPackageName}_$authority"
                        }
                    }
                }
            }
        }
    }
}
