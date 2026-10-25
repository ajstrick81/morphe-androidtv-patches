package ajstrick81.morphe.patches.twitchatv.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import ajstrick81.morphe.patches.twitchatv.ads.SetWebViewClientFingerprint
import ajstrick81.morphe.patches.twitchatv.ads.blockTvAdsPatch

private const val EXTENSION_CLASS =
    "Lajstrick81/morphe/extension/twitchatv/ads/TwitchAtvWebViewHelper;"

/**
 * Twitch Android TV — playback-speed controls.
 *
 * The TV app is the Starshot WebView player (laserarray/hls.js), so speed is
 * simply `<video>.playbackRate`. The existing WebViewClient wrapper already
 * gives us the live WebView; this patch flips the helper's opt-in flag so it
 * installs the JS driver and adds a native key interceptor that opens the speed
 * list on the blue colour key.
 *
 * The blue colour key (KEYCODE_PROG_BLUE) is the primary trigger; long-press OK
 * stays as the fallback for remotes that do not send colour keys. Transport keys
 * are deliberately not hijacked: current TCL/Google TV remotes (e.g. the Q7D
 * Pro) have none, and taking fast-forward/rewind away from remotes that do have
 * them would break seeking for no benefit.
 *
 * Depends on Block TV ads because that patch owns the WebViewClient wrapper
 * (and merges the extension dex). Selecting this also installs that hook.
 */
@Suppress("unused")
val twitchSpeedTogglePatch = bytecodePatch(
    name = "Playback speed controls",
    description = "Adds playback-speed controls to the Twitch Android TV (Starshot) WebView " +
        "player. Press the blue colour key to open a 0.5x-2x list, pick with the D-pad, OK " +
        "applies (hold OK also works on remotes without colour keys). The chosen " +
        "speed stays until it is picked again - nothing changes it automatically. No button is " +
        "added and no existing control is modified: the page only ever gets " +
        "<video>.playbackRate written, so Twitch's own controls and gear/quality menu keep " +
        "working exactly as before. Uses the same WebView hook as Block TV ads (selecting this " +
        "also installs that hook). Opt-in.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            name = "Twitch",
            packageName = "tv.twitch.android.app",
            appIconColor = 0x9147FF,
            targets = listOf(AppTarget("13.0.0.2")),
        ),
    )

    // Owns the setWebViewClient wrapper (and merges extensions/extension.mpe).
    dependsOn(blockTvAdsPatch)

    execute {
        // Flip TwitchAtvWebViewHelper's opt-in flag. onPageStarted/onPageFinished
        // then install the JS driver, and every dispatchKeyEvent goes through the
        // helper so the blue key (or a long OK press) can open the speed list.
        SetWebViewClientFingerprint.method.addInstructions(
            0,
            "invoke-static {}, $EXTENSION_CLASS->setSpeedEnabled()V",
        )

        // Activity-level fallback: StarshotActivity's dispatchKeyEvent/onKeyDown,
        // if this build overrides one. Missing on some builds — skip, not fatal.
        StarshotKeyFingerprint.methodOrNull?.let(::injectActivityKey)
    }
}

/**
 * Route the Activity's key events to the helper. A consuming variant is used
 * when the method has a local register for move-result; otherwise the key is
 * only observed and the Activity keeps handling it.
 */
private fun injectActivityKey(method: MutableMethod) {
    val parameters = method.parameterTypes.map { it.toString() }
    val eventOnly = parameters == listOf("Landroid/view/KeyEvent;")
    val target = if (eventOnly) {
        "onSpeedKeyEvent(Landroid/view/KeyEvent;)Z"
    } else {
        "onSpeedKeyEvent(ILandroid/view/KeyEvent;)Z"
    }
    val args = if (eventOnly) "p1 .. p1" else "p1 .. p2"

    val registerCount = method.implementation?.registerCount
    // dexlib2 parameters exclude `this`; instance methods have one extra p-register.
    val hasLocal = registerCount != null && registerCount > method.parameters.size + 1

    if (!hasLocal) {
        val voidTarget = if (eventOnly) {
            "onSpeedKeyEventVoid(Landroid/view/KeyEvent;)V"
        } else {
            "onSpeedKeyEventVoid(ILandroid/view/KeyEvent;)V"
        }
        method.addInstructions(0, "invoke-static/range {$args}, $EXTENSION_CLASS->$voidTarget")
        return
    }
    method.addInstructions(
        0,
        """
            invoke-static/range {$args}, $EXTENSION_CLASS->$target
            move-result v0
            if-eqz v0, :morphe_speed_twitch_pass
            const/4 v0, 0x1
            return v0
            :morphe_speed_twitch_pass
            nop
        """,
    )
}
