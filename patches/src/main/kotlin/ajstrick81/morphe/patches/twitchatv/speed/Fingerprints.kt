package ajstrick81.morphe.patches.twitchatv.speed

import app.morphe.patcher.Fingerprint

/**
 * Activity-level key fallback for the Twitch speed toggle.
 *
 * The primary path is the WebView's OnKeyListener plus the page's JS `keydown`
 * listener, both installed by [TwitchAtvWebViewHelper]. That only sees keys if
 * the WebView has focus. If StarshotActivity consumes the remote's media keys
 * first, this seam catches them.
 *
 * Matches either key entry point on the unobfuscated StarshotActivity class:
 *   dispatchKeyEvent(KeyEvent)Z
 *   onKeyDown(int, KeyEvent)Z
 *
 * Resolved with methodOrNull so a build without an override simply skips the
 * fallback and leaves the WebView path in charge.
 */
internal object StarshotKeyFingerprint : Fingerprint(
    definingClass = "Ltv/twitch/starshot64/app/StarshotActivity;",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        val dispatch = method.name == "dispatchKeyEvent" &&
            params == listOf("Landroid/view/KeyEvent;") &&
            method.returnType == "Z"
        val down = method.name == "onKeyDown" &&
            params == listOf("I", "Landroid/view/KeyEvent;") &&
            method.returnType == "Z"
        dispatch || down
    },
)
