package ajstrick81.morphe.patches.raiplay.live

// RaiPlay 5.0.1 support, live-variant fix and WebView debug patch contributed by
// Michael Bompani (github.com/michaelbompani), from his fork michaelbompani/morphe-androidtv-patches. Thank you!

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.raiplay.shared.Constants

@Suppress("unused")
val fixLiveVariantPatch = bytecodePatch(
    name = "Fix live HLS variant 404",
    description = "Fixes live channels (e.g. Rai 2) that fail with an error toast because the " +
        "relinker hands the Android TV app the bitrate-capped HLS variant " +
        "playlist_ma_1800-3600.m3u8, which does not exist on the Akamai origin (HTTP 404). " +
        "The URL is rewritten to the full master playlist_ma.m3u8; the hdnea token " +
        "(acl=/*) stays valid. Other URLs are left untouched.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // p4 = manifestUrl (see Fingerprints.kt). String.replace is a no-op when the
        // capped variant is not in the URL, so every other stream is unaffected.
        NativePlaybackRequestInitFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "playlist_ma_1800-3600.m3u8"
                const-string v1, "playlist_ma.m3u8"
                invoke-virtual {p4, v0, v1}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
                move-result-object p4
            """,
        )
    }
}
