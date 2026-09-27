package ajstrick81.morphe.patches.raiplay.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ─────────────────────────────────────────────────────────────────────────────
// RaiPlay Android TV (it.rainet.androidtv) — ad seam.
//
// Autopsy of 5.0.0: native Kotlin app on androidx.media3/ExoPlayer. NO Google
// IMA. Ads are served by Smartclip (tv.smartclip.smartclientcore, a European
// broadcaster ad SDK) via RAI's own bridge under it/rai/raiplay/androidtv/
// nativeplayer/*:
//   NativePlaybackRequestParser.parseAdContext(JSONObject) → SmartclipAdContext
//     → SmartclipController.initialize (builds a VMAP AdSlot with pre/mid/post
//       AdBreaks) → Media3SmartclipFacade stitches ads into the ExoPlayer timeline.
//
// KEY: parseAdContext ALREADY returns null as the app's "no ads" signal — it
// returns null when the descriptor's "enabled" flag is not true, the provider is
// not "smartclip", or no adTagUrl is present. A null SmartclipAdContext routes
// playback into RaiPlay's own, well-exercised ad-FREE path (plenty of RaiPlay
// content has no ads). So the cleanest, lowest-risk kill is to force parseAdContext
// to return null for every title — no Smartclip session, no VMAP slot, no ad
// breaks, and no mid-session SDK fighting that could wedge playback.
//
// SCOPE: kills client-requested VOD ad breaks. RAI *live* channels may use
// broadcast-side SSAI that this does not touch (a mask/overlay job, not removal).
// ─────────────────────────────────────────────────────────────────────────────

// The ad-context parser. Matched on its STABLE signature — the only method
// returning SmartclipAdContext from a single JSONObject (the SmartclipAdContext
// constructor and copy() take the unpacked fields, not a JSONObject) — and pinned
// to NativePlaybackRequestParser so it can't hit an unrelated helper. The app is
// not obfuscated on 5.0.0, so the class/type names are stable anchors.
object ParseAdContextFingerprint : Fingerprint(
    returnType = "Lit/rai/raiplay/androidtv/nativeplayer/SmartclipAdContext;",
    parameters = listOf("Lorg/json/JSONObject;"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    custom = { _, classDef ->
        classDef.type ==
            "Lit/rai/raiplay/androidtv/nativeplayer/NativePlaybackRequestParser;"
    },
)
