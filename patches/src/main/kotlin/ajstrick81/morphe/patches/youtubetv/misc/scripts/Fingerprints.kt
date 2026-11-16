package ajstrick81.morphe.patches.youtubetv.misc.scripts

import app.morphe.patcher.Fingerprint

// ===========================================================================
// YouTube Android TV (7.05.301) — Cobalt leanback web app.
//
// StartupGuardNavigationObserver is the navigation observer Shell registers on
// the main WebContents in initFromNativeTabContents, before the start page
// loads, and its class keeps its real name. Cobalt constructs it with the
// page's WebContents, which is the handle used to inject a script into
// youtube.com/tv (TvScripts.setWebContents -> WebContentsImpl.evaluateJavaScript).
//
// The defining class, names and parameter descriptors below are exactly what
// dexdump shows in the target APK's classes2.dex — none of the three members is
// obfuscated. Re-verify them if the observer shape changes on a version bump.
// ===========================================================================
private const val OBSERVER_CLASS = "Ldev/cobalt/shell/StartupGuardNavigationObserver;"
private const val WEB_CONTENTS = "Lcobalt/org/chromium/content_public/browser/WebContents;"
private const val NAVIGATION_HANDLE = "Lcobalt/org/chromium/content_public/browser/NavigationHandle;"

internal object StartupGuardObserverConstructorFingerprint : Fingerprint(
    definingClass = OBSERVER_CLASS,
    name = "<init>",
    parameters = listOf(WEB_CONTENTS),
)

internal object StartupGuardFinishNavigationFingerprint : Fingerprint(
    definingClass = OBSERVER_CLASS,
    name = "didFinishNavigationInPrimaryMainFrame",
    parameters = listOf(NAVIGATION_HANDLE),
    returnType = "V",
)

internal object StartupGuardRedirectNavigationFingerprint : Fingerprint(
    definingClass = OBSERVER_CLASS,
    name = "didRedirectNavigation",
    parameters = listOf(NAVIGATION_HANDLE),
    returnType = "V",
)
