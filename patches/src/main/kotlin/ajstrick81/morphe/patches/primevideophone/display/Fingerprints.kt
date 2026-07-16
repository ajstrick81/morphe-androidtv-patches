package ajstrick81.morphe.patches.primevideophone.display

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// AppCompatActivity.attachBaseContext(Context)
// classes2.dex / smali/androidx/appcompat/app/AppCompatActivity.smali
//
// The single app-wide hook for overriding the app's own display density. Every
// Prime Video phone activity converges on AppCompatActivity:
//   MainActivity      -> ConfigurationAwareActivity -> AppCompatActivity
//   PlayerActivity    -> ConfigurationAwareActivity -> AppCompatActivity
//   DetailPageActivity-> BaseClientActivity -> BaseActivity
//                        -> AsyncDependencyInjectingActivity -> AppCompatActivity
// so wrapping the base context here scales every screen's density (browse,
// detail, player) without touching the SYSTEM display — avoiding the Google TV
// "does not support 1920x1080" launcher crash that `wm density` triggers.
// ─────────────────────────────────────────────────────────────────────────────
object AppCompatAttachBaseContextFingerprint : Fingerprint(
    definingClass = "Landroidx/appcompat/app/AppCompatActivity;",
    name = "attachBaseContext",
    parameters = listOf("Landroid/content/Context;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)
