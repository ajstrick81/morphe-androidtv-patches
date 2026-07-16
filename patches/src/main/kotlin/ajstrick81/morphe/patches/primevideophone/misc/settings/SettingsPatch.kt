package ajstrick81.morphe.patches.primevideophone.misc.settings

import ajstrick81.morphe.patches.primevideophone.controls.dpadPlayerControlsPatch
import ajstrick81.morphe.patches.primevideophone.display.reduceUiZoomPatch
import ajstrick81.morphe.patches.primevideophone.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.util.logging.Logger

private val logger = Logger.getLogger("MorpheSettingsPatch")

private const val HOST_ACTIVITY = "com.google.android.gms.common.api.GoogleApiActivity"
private const val SETTINGS_INTENT_DATA = "morphe_settings_intent"
private const val HOOK_CLASS =
    "Lajstrick81/morphe/extension/primevideophone/settings/SettingsActivityHook;"

// PV's root settings PreferenceScreen on the pinned target (3.0.452.1045). Verified
// present and plaintext: res/xml/main_settings_bottom_nav.xml.
private const val ROOT_PREFERENCES_FILE = "res/xml/main_settings_bottom_nav.xml"

// ─────────────────────────────────────────────────────────────────────────────
// Resource half: make the hijacked activity presentable, and give the user a way
// in from Prime Video's own settings list.
// ─────────────────────────────────────────────────────────────────────────────
private val settingsResourcePatch = resourcePatch {
    finalize {
        document("AndroidManifest.xml").use { document ->
            val activities = document.getElementsByTagName("activity")
            var host: Element? = null
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as? Element ?: continue
                if (activity.getAttribute("android:name") == HOST_ACTIVITY) {
                    host = activity
                    break
                }
            }
            checkNotNull(host) { "$HOST_ACTIVITY not found in the manifest" }

            // Stock theme is Theme.Translucent.NoTitleBar (fine for an invisible GMS
            // dialog shim, unusable for a real settings screen — the content would
            // render over whatever is behind it). DeviceDefault is a platform theme,
            // so this adds no AppCompat/resource dependency.
            host.setAttribute("android:theme", "@android:style/Theme.DeviceDefault")

            // Keep the fragment alive across configuration changes instead of
            // recreating the activity (upstream Morphe does the same).
            host.setAttribute("android:configChanges", "orientation|screenSize|keyboardHidden")

            // Upstream Morphe found some devices crash when undeclared data is passed
            // to an intent; declaring a text/plain data filter fixes it. Our intent
            // carries a plain-string data value, so mirror that workaround.
            val mimeType = document.createElement("data")
            mimeType.setAttribute("android:mimeType", "text/plain")
            val intentFilter = document.createElement("intent-filter")
            intentFilter.appendChild(mimeType)
            host.appendChild(intentFilter)
        }

        // Add an entry to Prime Video's own settings list that fires our intent.
        // NOTE: android:targetPackage is the stock package name. If the Clone patch
        // (package rename) is ever applied to the phone app, this must be resolved
        // dynamically the way upstream does via setOrGetFallbackPackageName.
        //
        // This row is a CONVENIENCE, not the entry point: holding OK in the player
        // opens the same screen (DpadPlayerControls.openSettings). So a missing file
        // is a downgrade, not a failure — 3.0.461 migrated settings to React Native
        // and deleted every PreferenceScreen xml, and refusing to patch there would
        // cost a working screen to protect a shortcut.
        //
        // It still must not be injected BLINDLY: document() on a non-existent path
        // hands back an empty document, and we would happily write our node into it,
        // report "Applied", and show nothing — the silently-inert failure that cost
        // a night on the ATV media3 hooks. Hence the explicit exists() check.
        val rootPreferences = get(ROOT_PREFERENCES_FILE)
        if (!rootPreferences.exists()) {
            logger.warning(
                "$ROOT_PREFERENCES_FILE not found — Prime Video's settings screen has moved, " +
                    "so no row was added to its settings list. The settings screen is still " +
                    "reachable: hold OK (D-pad center) during playback."
            )
            return@finalize
        }

        document(ROOT_PREFERENCES_FILE).use { document ->
            val screen = document.documentElement
                ?: throw PatchException("$ROOT_PREFERENCES_FILE has no root element")

            // The entry only renders if it is inside a PreferenceScreen. If the root
            // is something else, the file is not what we think it is — fail rather
            // than inject a node that nothing will inflate.
            if (screen.tagName != "PreferenceScreen") {
                throw PatchException(
                    "$ROOT_PREFERENCES_FILE root is <${screen.tagName}>, expected <PreferenceScreen>"
                )
            }

            val preference = document.createElement("Preference")
            preference.setAttribute("android:key", "morphe_settings")
            preference.setAttribute("android:title", "Morphe settings")
            preference.setAttribute("android:summary", "Patch options for TV use")
            preference.setAttribute("android:persistent", "false")

            val intent = document.createElement("intent")
            intent.setAttribute("android:targetPackage", Constants.COMPATIBILITY.packageName)
            intent.setAttribute("android:targetClass", HOST_ACTIVITY)
            intent.setAttribute("android:data", SETTINGS_INTENT_DATA)
            preference.appendChild(intent)

            // Put it first so it is reachable without scrolling on a TV.
            screen.insertBefore(preference, screen.firstChild)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bytecode half: hijack the activity.
//
// Technique borrowed from upstream Morphe's YouTube settings patch. A new activity
// cannot simply be added to the manifest (that breaks root installations), so an
// expendable existing one is repurposed. GoogleApiActivity only hosts GMS error
// resolution dialogs, which this app effectively never shows.
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
val morpheSettingsPatch = bytecodePatch(
    name = "Morphe settings",
    description = "Adds a settings screen where the other phone-on-TV patches can be configured " +
        "(D-pad controls on/off, UI zoom level) instead of being fixed at patch time. Open it by " +
        "holding OK during playback, or from the \"Morphe settings\" row added to Prime Video's " +
        "own settings list. Works by repurposing the unused GoogleApiActivity as the host. Opt-in.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(settingsResourcePatch)

    // The hold-OK gesture that opens this screen lives in the D-pad patch's window
    // callback, and it is the only entry point guaranteed to exist (Prime Video's
    // own settings list is React Native from 3.0.461 and has nowhere to inject a
    // row). Without this dependency the screen could ship unreachable.
    dependsOn(dpadPlayerControlsPatch)

    // The screen exposes a "UI zoom" control that does nothing unless the density
    // override is actually injected — so pull that patch in too, rather than
    // present an inert setting. Consequence: enabling this screen also applies the
    // zoom patch's default scale (0.80); a user who wants stock density can set it
    // back to 100% in the screen. (The D-pad toggle above is already backed.)
    dependsOn(reduceUiZoomPatch)

    // Required — without extendWith the extension dex is never merged and the
    // invoke-static below resolves to a missing class at runtime.
    extendWith("extensions/extension.mpe")

    execute {
        val hostClass = GoogleApiActivityOnCreateFingerprint.classDef
        val onCreate = GoogleApiActivityOnCreateFingerprint.method

        // Take over onCreate: call through to Activity.onCreate so the framework is
        // satisfied, hand the Activity to the extension, and return before any of
        // GMS's original setup can run. The original body remains after this
        // return-void as unreachable code — never verified, never executed.
        onCreate.addInstructions(
            0,
            """
                invoke-super { p0, p1 }, ${hostClass.superclass}->onCreate(Landroid/os/Bundle;)V
                invoke-static { p0 }, $HOOK_CLASS->initialize(Landroid/app/Activity;)V
                return-void
            """
        )

        // Deliberately NOT removing the class's other methods, which is what the
        // upstream YouTube patch does. That removal was verified against this app's
        // smali and is both unnecessary and harmful here:
        //
        //  * onSaveInstanceState is the only one the framework calls on our hijacked
        //    instance, and it merely reads the primitive `zaa:I` field (default 0)
        //    and calls super — it cannot NPE, so upstream's stated reason
        //    ("they will break as the onCreate method is modified") does not apply.
        //  * `zaa(Context, PendingIntent, int, boolean)` is a PUBLIC STATIC factory
        //    that other GMS code calls to build the launch Intent. Deleting it would
        //    risk NoSuchMethodError in code we do not control.
        //  * onActivityResult/onCancel/zab are only reachable via the original flow,
        //    which our onCreate returns before ever starting.
        //
        // Keeping them is strictly safer: if GMS does launch this activity for error
        // resolution, initialize() sees a foreign intent and finishes cleanly.
    }
}
