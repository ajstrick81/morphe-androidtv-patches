/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the Morphe patches
 * LICENSE file: https://github.com/MorpheApp/morphe-patches/blob/main/NOTICE
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * File-Specific Exception to Section 7b:
 * -------------------------------------
 * Section 7b (Attribution Requirement) of the Morphe patches LICENSE
 * does not apply to THIS FILE. Use of this file does NOT require any
 * user-facing, in-application, or UI-visible attribution.
 *
 * For this file only, attribution under Section 7b is satisfied by
 * retaining this comment block in the source code of this file.
 *
 * Distribution and Derivative Works:
 * ----------------------------------
 * This comment block MUST be preserved in all copies, distributions,
 * and derivative works of this file, whether in source or modified
 * form.
 *
 * All other terms of the Morphe Patches LICENSE, including Section 7c
 * (Project Name Restriction) and the GPLv3 itself, remain fully
 * applicable to this file.
 */


/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/util/PatchListGenerator.kt
 */
package ajstrick81.morphe.util

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.net.URLClassLoader
import java.util.jar.Manifest

typealias PackageName = String
typealias VersionName = String

internal fun main() {
    val patchFiles = setOf(
        File("build/libs/").listFiles { file ->
            val fileName = file.name
            !fileName.contains("javadoc") &&
                    !fileName.contains("sources") &&
                    fileName.endsWith(".mpp")
        }!!.first()
    )
    val loadedPatches = loadPatchesFromJar(patchFiles)
    val patchClassLoader = URLClassLoader(patchFiles.map { it.toURI().toURL() }.toTypedArray())
    val manifest = patchClassLoader.getResources("META-INF/MANIFEST.MF")

    while (manifest.hasMoreElements()) {
        Manifest(manifest.nextElement().openStream())
            .mainAttributes
            .getValue("Version")
            ?.let {
                generatePatchList(it, loadedPatches)
            }
    }
}

/**
 * Emits `version`, root-level `appNames` (package → label), and `patches` with
 * `compatiblePackages`, **`compatibility`** (full `Compatibility` metadata for Manager UI),
 * and options. Requires morphe-patcher 1.3.x and `compatibleWith(Compatibility(...))` in patches.
 */
@Suppress("DEPRECATION")
private fun generatePatchList(version: String, patches: Set<Patch<*>>) {
    val listJson = File("../patches-list.json")

    val appNames = patches
        .asSequence()
        .flatMap { it.compatibility.orEmpty().asSequence() }
        .mapNotNull { c ->
            val pkg = c.packageName ?: return@mapNotNull null
            val label = c.name ?: return@mapNotNull null
            pkg to label
        }
        .toMap()

    val patchesMap = patches.sortedBy { it.name }.map {
        JsonPatch(
            it.name!!,
            it.description,
            it.use,
            it.dependencies.map { dependency -> dependency.javaClass.simpleName },
            it.compatiblePackages?.associate { (packageName, versions) -> packageName to versions },
            it.compatibility.orEmpty().map { c -> c.toJsonCompatibility() },
            it.options.values.map { option ->
                JsonPatch.Option(
                    option.key,
                    option.title,
                    option.description,
                    option.required,
                    option.type.toString(),
                    option.default,
                    option.values,
                )
            },
        )
    }

    val gsonBuilder = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    val jsonObject = JsonObject()
    jsonObject.addProperty("version", "v$version")
    jsonObject.add("appNames", gsonBuilder.toJsonTree(appNames))
    jsonObject.add("patches", gsonBuilder.toJsonTree(patchesMap))

    listJson.writeText(
        gsonBuilder.toJson(jsonObject)
    )
}

private fun Compatibility.toJsonCompatibility() = JsonCompatibility(
    name = name ?: "",
    packageName = packageName ?: "",
    description = description?.takeUnless { it.isBlank() },
    appIconColor = appIconColor,
    targets = targets?.map { t -> t.toJsonAppTarget() },
)

private fun AppTarget.toJsonAppTarget() = JsonAppTarget(
    version = version ?: "",
    experimental = isExperimental,
)

@Suppress("unused")
private class JsonCompatibility(
    val name: String,
    val packageName: String,
    val description: String? = null,
    val appIconColor: Int?,
    val targets: List<JsonAppTarget>?,
)

@Suppress("unused")
private class JsonAppTarget(
    val version: String,
    val experimental: Boolean = false,
)

@Suppress("unused")
private class JsonPatch(
    val name: String? = null,
    val description: String? = null,
    val use: Boolean = true,
    val dependencies: List<String>,
    val compatiblePackages: Map<PackageName, Set<VersionName>?>? = null,
    val compatibility: List<JsonCompatibility>,
    val options: List<Option>,
) {
    class Option(
        val key: String,
        val title: String?,
        val description: String?,
        val required: Boolean,
        val type: String,
        val default: Any?,
        val values: Map<String, Any?>?,
    )
}
