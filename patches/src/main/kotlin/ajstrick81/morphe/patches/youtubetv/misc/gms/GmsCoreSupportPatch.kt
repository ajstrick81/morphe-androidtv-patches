package ajstrick81.morphe.patches.youtubetv.misc.gms

import ajstrick81.morphe.patches.youtubetv.shared.Constants
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE_NAME = "com.google.android.youtube.tv"
private const val PATCHED_PACKAGE_NAME = "app.morphe.android.youtube.tv"

// MicroG-RE's package and vendor id. Both the original Morphe patch and the
// reference TV port keep "app.revanced" (MicroG-RE and ReVanced GmsCore share it).
private const val GMS_CORE_VENDOR_GROUP_ID = "app.revanced"
private const val GMS_CORE_PACKAGE_NAME = "$GMS_CORE_VENDOR_GROUP_ID.android.gms"

// SHA-1 of the stock signing certificate (CN=ytandroidtv, O=Google Inc.),
// verified with apksigner on the 7.31.300 APK:
//   61226bdb57cc32c8a2a9ef71f7bc9548e95dcc0b
// MicroG-RE reads it from the SPOOFED_PACKAGE_SIGNATURE metadata and presents the
// stock signature to Google, which is what a resigned build otherwise lacks.
private const val SPOOFED_PACKAGE_SIGNATURE = "61226bdb57cc32c8a2a9ef71f7bc9548e95dcc0b"

private const val EXTENSION_CLASS =
    "Lajstrick81/morphe/extension/youtubetv/gms/GmsCoreSupportPatch;"

// ─────────────────────────────────────────────────────────────────────────────
// GmsCore support (MicroG-RE) for YouTube Android TV
//
// WHY: a resigned YouTube TV build is killed about 4 s after launch with
//   java.lang.SecurityException: PackageVerificationRslt: not allowed:
//   pkg=com.google.android.youtube.tv.mod, sha256=[...]
// "PackageVerificationRslt" only exists in Google Play services
// (/product/priv-app/PrebuiltGmsCorePano), so the check is a GMS API call: the
// signed app asks the real GMS whether a package with that name and signature
// may use it, and GMS says no. No local patch can answer for Google.
//
// The fix is the same one the phone YouTube patch uses:
//   1. rename the app so it installs beside the stock one (and so the patched
//      package is not the system app's reserved name),
//   2. redirect every Play services package name, permission, intent action and
//      content provider in the DEX to MicroG-RE (app.revanced.android.gms),
//   3. tell MicroG-RE, through manifest metadata, which package name and signing
//      certificate to present to Google (the stock ones),
//   4. make the app's own Play services availability checks say "available" and
//      stop the "Play services not available" throw,
//   5. check at start-up that MicroG-RE is installed and running.
//
// Ported from the reference TV port (same app, 7.32.300):
//   farotv-patches/shared/gms/GmsCoreSupportPatch.kt + PlayServicesBypassPatch.kt
// and the phone YouTube TV implementation:
//   mobile-morphe-patches/yttv/misc/GmsCoreSupportPatch.kt
//
// API NOTE: this repository is on morphe-patcher 1.3.3, which has no
// matchAllMethodIndicesForEach()/returnEarly() (those are patcher 2.x helpers).
// String rewriting therefore goes through BytecodePatchContext.classDefByStrings()
// + MutableMethodImplementation.replaceInstruction() below, and the early returns
// are injected as plain smali. Both have the same effect as the reference calls.
//
// GmsCore support owns the package rename. "Clone YouTube TV" would otherwise
// append ".mod" in its finalize block, after this patch's execute block, and
// silently replace the package name MicroG-RE is told to spoof; that patch now
// stands down when the manifest package has already been renamed (see
// CloneAppPatch.kt).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manifest side of the patch: rename the app, rename the identifiers that must
 * stay device-unique, and tell MicroG-RE what to spoof.
 *
 * Unnamed on purpose: PatchLoader skips patches without a name, so this never
 * appears as its own entry in the Manager; it only runs as a dependency.
 */
private val gmsCoreSupportResourcePatch = resourcePatch {
    execute {
        val fromPackageName = packageMetadata.packageName
        val toPackageName = PATCHED_PACKAGE_NAME

        // Authorities may be an @string/... reference rather than a literal; any
        // such string resource names are collected here and rewritten below.
        val providerStringResources = mutableSetOf<String>()

        document("AndroidManifest.xml").use { document ->
            // rename owned by Clone YouTube TV

            // ── Custom permissions ──────────────────────────────────────────
            // A <permission> android:name is device-unique even across package
            // names, so leaving com.google.android.youtube.tv.DYNAMIC_RECEIVER_
            // NOT_EXPORTED_PERMISSION (the only custom permission 7.31.300 and
            // 7.05.301 declare) would fail the install with a duplicate-permission
            // error while the stock app is present.
            val permissions = document.getElementsByTagName("permission")
            val usesPermissions = document.getElementsByTagName("uses-permission")

            for (i in 0 until permissions.length) {
                val permission = permissions.item(i) as? Element ?: continue
                val oldName = permission.getAttribute("android:name")
                val newName = when {
                    oldName.startsWith('.') -> continue
                    oldName.startsWith("$fromPackageName.") ->
                        oldName.replaceFirst(fromPackageName, toPackageName)
                    else -> "${toPackageName}_$oldName"
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
            // Neither target declares a <provider>, but the rewrite is kept
            // generic so a build that adds one still installs beside the stock
            // app instead of failing with INSTALL_FAILED_CONFLICTING_PROVIDER.
            val providers = document.getElementsByTagName("provider")

            for (i in 0 until providers.length) {
                val provider = providers.item(i) as? Element ?: continue
                val authorities = provider.getAttribute("android:authorities").split(';')
                val newAuthorities = authorities.map {
                    when {
                        it.startsWith("$fromPackageName.") ->
                            it.replaceFirst(fromPackageName, toPackageName)
                        it.startsWith('@') -> {
                            providerStringResources.add(it.removePrefix("@string/"))
                            it
                        }
                        else -> "${toPackageName}_$it"
                    }
                }
                provider.setAttribute("android:authorities", newAuthorities.joinToString(";"))
            }

            // ── MicroG-RE spoofing metadata ─────────────────────────────────
            // GmsCore (MicroG-RE) presents these values to Google instead of the
            // patched app's own package name and signature. This is exactly what
            // "PackageVerificationRslt: not allowed" was rejecting.
            val application = document.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("AndroidManifest.xml has no <application> element")

            fun addMetaData(name: String, value: String) {
                application.appendChild(
                    document.createElement("meta-data").apply {
                        setAttribute("android:name", name)
                        setAttribute("android:value", value)
                    },
                )
            }

            addMetaData("$GMS_CORE_PACKAGE_NAME.SPOOFED_PACKAGE_NAME", fromPackageName)
            addMetaData("$GMS_CORE_PACKAGE_NAME.SPOOFED_PACKAGE_SIGNATURE", SPOOFED_PACKAGE_SIGNATURE)
            // GmsCore presence detection, as in both reference patches.
            addMetaData("$GMS_CORE_VENDOR_GROUP_ID.MICROG_PACKAGE_NAME", GMS_CORE_PACKAGE_NAME)

            // ── Package visibility (Android 11+) ────────────────────────────
            // Without this the extension's PackageManager query cannot see
            // MicroG-RE. Both targets already declare <queries> (7.31.300: line 61
            // of the manifest), so the entry is appended instead of creating the
            // element; the create branch covers a future build that drops it.
            val queries = document.getElementsByTagName("queries").item(0) as? Element
                ?: document.createElement("queries").also { document.documentElement.appendChild(it) }
            queries.appendChild(
                document.createElement("package").apply {
                    setAttribute("android:name", GMS_CORE_PACKAGE_NAME)
                },
            )
        }

        // ── @string-backed provider authorities ─────────────────────────────
        if (providerStringResources.isNotEmpty()) {
            document("res/values/strings.xml").use { document ->
                val children = document.documentElement.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i) as? Element ?: continue

                    if (node.getAttribute("name") in providerStringResources) {
                        val authority = node.textContent
                        node.textContent = if (authority.startsWith("$fromPackageName.")) {
                            authority.replaceFirst(fromPackageName, toPackageName)
                        } else {
                            "${toPackageName}_$authority"
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val gmsCoreSupportPatch = bytecodePatch(
    name = "GmsCore support",
    description = "Signs in to Google through MicroG-RE (GmsCore) instead of Google Play services. " +
        "Renames the app to $PATCHED_PACKAGE_NAME and points every Play services package name, " +
        "permission, intent action and content provider at $GMS_CORE_PACKAGE_NAME, telling " +
        "MicroG-RE to present the stock package name and signing certificate. Required for " +
        "resigned builds: Google Play services rejects them with \"PackageVerificationRslt: not " +
        "allowed\". MicroG-RE must be installed; without it the app cannot sign in.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(gmsCoreSupportResourcePatch)

    // Merge the extension the injected smali calls into (GmsCoreSupportPatch).
    extendWith("extensions/extension.mpe")

    execute {
        // Resolve every fingerprint BEFORE the string rewrite below: two of them
        // match string literals that the rewrite changes.
        val googlePlayUtility = GooglePlayUtilityFingerprint.methodOrNull
            ?: throw PatchException(
                "GMS availability method (Context, int) not found — the bundled Play services " +
                    "code changed in this APK; re-verify the fingerprint against dexdump",
            )
        val serviceCheck = ServiceCheckFingerprint.methodOrNull
            ?: throw PatchException(
                "GooglePlayServicesNotAvailable throw not found — re-verify the fingerprint",
            )
        val prime = PrimeMethodFingerprint.methodOrNull
            ?: throw PatchException(
                "Primes package allowlist method not found — re-verify the fingerprint",
            )
        val mainActivityOnCreate = CobaltActivityOnCreateFingerprint.methodOrNull
            ?: throw PatchException(
                "CobaltActivity.onCreate(Bundle) not found — re-verify the fingerprint",
            )

        // 1) Play services is always "available".
        //    dma.b(Context,int) (7.31.300) / cut.e(Context,int) (7.05.301) returns
        //    the ConnectionResult status, 0 = SUCCESS; dma.c(Context,int) throws
        //    GooglePlayServicesNotAvailableException. With the GMS package names
        //    redirected, MicroG answers the real calls, so these two would
        //    otherwise abort start-up whenever the local version/signature check
        //    no longer matches. Both methods have at least one local register
        //    (v0) in every supported build and return immediately, so writing v0
        //    before the first instruction is safe.
        googlePlayUtility.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        // (returnEarly() does not exist in patcher 1.3.3; a bare return-void is
        // exactly what the reference injects for a void method.)
        serviceCheck.addInstructions(0, "return-void")

        // 2) Redirect the Play services package name itself. This is the broad
        //    replacement the references use; the narrower sets below cover
        //    strings that contain it but are not equal to it.
        val rewritten = replaceStrings("com.google.android.gms") { GMS_CORE_PACKAGE_NAME }
        if (rewritten == 0) {
            throw PatchException(
                "No \"com.google.android.gms\" string found in the DEX — \"GmsCore support\" would " +
                    "have no effect; the APK layout changed",
            )
        }

        // 3) Permissions, intent actions and content provider authorities that
        //    GmsCore declares under its own vendor id. Exact matches only, so the
        //    app's own "com.google.android.youtube.tv" strings are untouched.
        (GmsCoreConstants.PERMISSIONS + GmsCoreConstants.ACTIONS + GmsCoreConstants.AUTHORITIES)
            .forEach { replaceStrings(it) }

        // 4) content:// URIs of the GMS/GSF providers, e.g.
        //    content://com.google.android.gsf.gservices/prefix (used by the
        //    extension's own MicroG check as content://app.revanced...).
        val authorityPrefixes = GmsCoreConstants.AUTHORITIES.map { it to "content://$it" }
        replaceStrings("content://", StringComparisonType.STARTS_WITH) { uri ->
            authorityPrefixes.firstOrNull { (_, prefix) -> uri.startsWith(prefix) }
                ?.let { (authority, prefix) ->
                    uri.replaceFirst(
                        prefix,
                        "content://" + authority.replace("com.google", GMS_CORE_VENDOR_GROUP_ID),
                    )
                }
        }

        // 5) The app's own provider authorities (none in the two targets, but the
        //    references rename them so a future build cannot clash with the stock
        //    app's provider).
        listOf("SuggestionProvider", "fileprovider").forEach { authority ->
            replaceStrings("$ORIGINAL_PACKAGE_NAME.$authority") {
                "$PATCHED_PACKAGE_NAME.$authority"
            }
        }

        // 6) Keep the renamed app on the Primes allowlist. The method reads the
        //    app's own package name and checks it against a literal list that
        //    still holds "com.google.android.youtube.tv"; without the swap the
        //    renamed app is not "prime" any more.
        val packageNameIndex = prime.implementation!!.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.CONST_STRING &&
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string == ORIGINAL_PACKAGE_NAME
        }
        if (packageNameIndex < 0) {
            throw PatchException(
                "\"$ORIGINAL_PACKAGE_NAME\" not found in the Primes allowlist method",
            )
        }
        val packageNameRegister =
            prime.getInstruction<OneRegisterInstruction>(packageNameIndex).registerA
        prime.replaceInstruction(
            packageNameIndex,
            "const-string v$packageNameRegister, \"$PATCHED_PACKAGE_NAME\"",
        )

        // 7) Check MicroG-RE is installed at start-up. Injected at index 0 of
        //    CobaltActivity.onCreate, before super.onCreate(): p0 is the activity
        //    (CobaltActivity.onCreate has .ins 2, so p0/p1 are the last two
        //    registers — v25/v26 on 7.31.300, v20/v21 on 7.05.301), and the check
        //    only reads the package manager, the content resolver and Toasts.
        //    /range is required: p0 is register 25, beyond invoke-static's 4-bit
        //    register operand.
        mainActivityOnCreate.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->checkGmsCore(Ljava/lang/Object;)V",
        )
    }
}

/**
 * Rewrites every const-string/const-string-jumbo operand whose value matches
 * [query] under [comparison] with the value returned by [replace] (null leaves the
 * instruction alone). Returns how many instructions were rewritten.
 *
 * The class list comes from the string map (classDefByStrings), like the
 * reference's matchAllMethodIndicesForEach(); each method's instructions are then
 * scanned here because patcher 1.3.3 exposes no "replace all occurrences" helper.
 * Occurrences are collected before rewriting so the indices stay valid.
 */
private fun BytecodePatchContext.replaceStrings(
    query: String,
    comparison: StringComparisonType = StringComparisonType.EQUALS,
    replace: (String) -> String? = { it.replace("com.google", GMS_CORE_VENDOR_GROUP_ID) },
): Int {
    var rewritten = 0

    classDefByStrings(query, comparison).forEach { classDef ->
        mutableClassDefBy(classDef.type).methods.forEach methodLoop@ { method ->
            val implementation = method.implementation ?: return@methodLoop
            val occurrences = mutableListOf<Pair<Int, String>>()

            implementation.instructions.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.CONST_STRING &&
                    instruction.opcode != Opcode.CONST_STRING_JUMBO
                ) {
                    return@forEachIndexed
                }
                val value = ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string ?: return@forEachIndexed
                if (!comparison.compare(value, query)) return@forEachIndexed

                replace(value)?.let { occurrences += index to it }
            }

            occurrences.forEach { (index, newValue) ->
                val instruction = implementation.instructions[index]
                val register = (instruction as OneRegisterInstruction).registerA
                val reference = ImmutableStringReference(newValue)

                // Keep the original width: a jumbo instruction can address
                // registers that const-string (4-bit) cannot encode.
                implementation.replaceInstruction(
                    index,
                    if (instruction.opcode == Opcode.CONST_STRING_JUMBO) {
                        BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, register, reference)
                    } else {
                        BuilderInstruction21c(Opcode.CONST_STRING, register, reference)
                    },
                )
                rewritten++
            }
        }
    }

    return rewritten
}
