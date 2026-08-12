package ajstrick81.morphe.patches.twitchatv.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// ===========================================================================
// Twitch Android TV ("Twitch Live Streaming Android TV", tv.twitch.android.app
// v13.0.0.2, app package tv.twitch.starshot64.app — codename "Starshot").
//
// The TV app is a thin native shell hosting the Twitch web player ("laserarray",
// hls.js) in a WebView. Live ads are server-stitched (SSAI) into the
// video-weaver media playlist ( *.playlist.ttvnw.net ), fetched by page JS. That
// fetch is NOT service-worker mediated (verified fromServiceWorker=false), so a
// native WebViewClient.shouldInterceptRequest sees it regardless of JS timing —
// which is where we scrub the stitched-ad segments (see TwitchAtvWebViewHelper).
//
// StarshotActivity installs its WebViewClient (Ls4/a extends Ls4/k) via
// WebView.setWebViewClient(...). We wrap that client at the call site so our
// scrubbing shouldInterceptRequest override sits in front of it. Pinned by the
// unobfuscated StarshotActivity class + the framework setWebViewClient call, so
// it survives R8 renames of the client class.
// ===========================================================================
object SetWebViewClientFingerprint : Fingerprint(
    definingClass = "Ltv/twitch/starshot64/app/StarshotActivity;",
    custom = { method, _ ->
        method.implementation?.instructions?.any { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                (insn as? ReferenceInstruction)?.reference?.toString()
                    ?.contains("->setWebViewClient(") == true
        } == true
    },
)
