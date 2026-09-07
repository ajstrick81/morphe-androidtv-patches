package app.morphe.patches.espn

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Element

// Suffix appended to the original applicationId for the cloned package, e.g.
// com.espn.score_center -> com.espn.score_center.mod
private const val CLONE_SUFFIX = "mod"

// ─────────────────────────────────────────────────────────────────────────────
// Clone ESPN (side-by-side install)
//
// Many Android TVs / TV boxes — and notably Amazon Fire TV devices — ship ESPN
// as a SYSTEM app that cannot be uninstalled without root. Installing a patched
// build over the stock system app is impossible, and renaming the applicationId
// alone is NOT enough:
//
//   A ContentProvider's android:authorities and any custom <permission>
//   android:name must be UNIQUE device-wide, independent of applicationId. They
//   stay pinned to the original package string, so PackageManager rejects the
//   install with INSTALL_FAILED_CONFLICTING_PROVIDER (or a duplicate-permission
//   error) as long as the stock app is present.
//
// This patch renames the package AND every package-scoped identifier that must
// be device-unique (provider authorities + custom permissions), so the clone
// installs cleanly alongside the stock app. The rewrite is generic: it rewrites
// any authority/permission whose name is prefixed with the app's own package,
// and defers @string-backed authorities to the strings.xml pass. It adapts to
// whatever ESPN's manifest declares in a given build, without a hardcoded list.
//
// Intent actions and <queries> package targets are left untouched: they are not
// device-unique and are matched by literal string on both ends.
//
// Runs in finalize {} so the rename happens after all other patches, at manifest
// write time. Opt-in (default = false): only clone when explicitly selected.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone ESPN",
    description = "Installs the patched ESPN as a separate app alongside the stock one, instead of " +
        "replacing it. Enable this when ESPN is a preinstalled system app that can't be " +
        "uninstalled — most commonly on Amazon Fire TV, and on some Android TV boxes and Onn " +
        "devices. The clone gets its own package (suffix .$CLONE_SUFFIX), so it shows up as a " +
        "second ESPN icon and keeps its own settings. Leave OFF if you were able to uninstall the " +
        "original ESPN first (a normal in-place install is cleaner). Opt-in.",
    default = false,
) {
    compatibleWith(AppCompatibilities.ESPN_TV)

    finalize {
        val packageName = packageMetadata.packageName
        val newPackageName = "$packageName.$CLONE_SUFFIX"

        // Authorities may be an @string/... reference rather than a literal; any
        // such string resource names are collected here and rewritten below.
        val providerStringResources = mutableSetOf<String>()

        document("AndroidManifest.xml").use { document ->
            document.documentElement.setAttribute("package", newPackageName)

            // ── Custom permissions ──────────────────────────────────────────
            // A <permission> android:name must be device-unique. Rename each
            // declaration prefixed with the old package, and keep any matching
            // <uses-permission> in sync so the app can still request its own.
            val permissions = document.getElementsByTagName("permission")
            val usesPermissions = document.getElementsByTagName("uses-permission")

            for (i in 0 until permissions.length) {
                val permission = permissions.item(i) as? Element ?: continue
                val oldName = permission.getAttribute("android:name")
                val newName = when {
                    oldName.startsWith('.') -> continue
                    oldName.startsWith("$packageName.") ->
                        oldName.replaceFirst(packageName, newPackageName)
                    else -> "${newPackageName}_$oldName"
                }
                permission.setAttribute("android:name", newName)

                for (j in 0 until usesPermissions.length) {
                    val usePerm = usesPermissions.item(j) as? Element ?: continue
                    if (usePerm.getAttribute("android:name") == oldName) {
                        usePerm.setAttribute("android:name", newName)
                        break
                    }
                }
            }

            // ── Provider authorities ────────────────────────────────────────
            // android:authorities is a ';'-separated list; each entry must be
            // device-unique. Rewrite literals prefixed with the old package and
            // defer @string references to the strings.xml pass below.
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

        // ── @string-backed authorities ──────────────────────────────────────
        // Rewrite any provider authorities that were declared as @string/...
        // references rather than manifest literals.
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
