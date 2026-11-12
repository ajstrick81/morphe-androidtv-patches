package ajstrick81.morphe.patches.youtubetv.misc.clone

import ajstrick81.morphe.patches.youtubetv.shared.Constants
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.util.logging.Logger

// Suffix appended to the original package for the cloned package, e.g.
// com.google.android.youtube.tv -> com.google.android.youtube.tv.mod
private const val CLONE_SUFFIX = "morphe"

// ─────────────────────────────────────────────────────────────────────────────
// Clone YouTube Android TV (side-by-side install)
//
// Most Android TV boxes ship YouTube as a SYSTEM app (/product/app/YouTubeLeanback).
// Installing a patched build over it is impossible without root: PackageManager
// rejects a differently-signed APK with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and
// that check still applies after "pm uninstall --user 0" because the system APK
// keeps the package name reserved. Renaming the applicationId lets the patched
// build install as a separate app.
//
// Renaming the applicationId alone is not always enough: a ContentProvider's
// android:authorities and any custom <permission> android:name must be unique
// device-wide. 7.31.300 declares no <provider>, only one custom permission
// (com.google.android.youtube.tv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION), but
// the rewrite below is generic so it keeps working if a future build adds
// providers. Intent actions and <queries> targets are left alone: they are
// matched by literal string on both ends.
//
// Runs in finalize {} so the rename happens after all other patches, at manifest
// write time. Opt-in (default = false): a normal in-place install is cleaner
// whenever the stock app can be uninstalled.
//
// "GmsCore support" also renames the app, and its rename runs in the execute
// phase, before this finalize block. Two renames would fight over the same
// attribute: this one runs last and would replace app.morphe.android.youtube.tv
// with com.google.android.youtube.tv.mod, breaking the package name MicroG-RE is
// told to spoof. When the manifest package has already been renamed this patch
// therefore makes no changes (the side-by-side install goal is already met).
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone YouTube TV",
    description = "Installs the patched YouTube Android TV as a separate app alongside the stock " +
        "one, instead of replacing it. Enable this when YouTube is a preinstalled system app that " +
        "can't be uninstalled — most Android TV boxes ship it in /product, and a patched build " +
        "cannot be installed over it (signature mismatch). The clone gets its own package " +
        "(suffix .$CLONE_SUFFIX) and its own settings. Leave OFF if the original was uninstalled " +
        "first, or if the app is not a system app. Makes no changes when \"GmsCore support\" is " +
        "applied, because that patch already renames the app. Opt-in.",
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
            // "GmsCore support" renamed the app in its execute block. Do not rename it
            // again: its manifest package is the name MicroG-RE is told to spoof.
            val currentPackageName = document.documentElement.getAttribute("package")
            if (currentPackageName != packageName) {
                Logger.getLogger("Clone YouTube TV").warning(
                    "Package already renamed to \"$currentPackageName\" by another patch " +
                        "(\"GmsCore support\" does this); \"Clone YouTube TV\" makes no changes.",
                )
                return@use
            }

            document.documentElement.setAttribute("package", newPackageName)

            // ── Custom permissions ──────────────────────────────────────────
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
