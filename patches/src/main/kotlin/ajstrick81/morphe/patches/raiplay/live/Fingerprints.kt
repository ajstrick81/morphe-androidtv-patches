package ajstrick81.morphe.patches.raiplay.live

// RaiPlay 5.0.1 support, live-variant fix and WebView debug patch contributed by
// Michael Bompani (github.com/michaelbompani), from his fork michaelbompani/morphe-androidtv-patches. Thank you!

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// RaiPlay Android TV (it.rainet.androidtv) — live manifest URL seam.
//
// Every native playback request (load, refreshContentDescriptor,
// updateStreamSource and NativePlaybackRequest.copy()) is built through the
// PRIMARY constructor of NativePlaybackRequest, so rewriting the manifest URL
// there covers all paths.
//
// Primary constructor shape:
//   5.0.0 → 19 params (…, NativeCmcdRequest)
//   5.0.1 → 20 params (…, NativeCmcdRequest, NativeSsaiRequest)
// Both start with (String hostSessionId, String playbackSessionId,
// String contentId, String manifestUrl, long startPositionMs, …), so
// p4 = manifestUrl on both, and both have 7 locals that are untouched before
// the first instruction (v0/v1 are free at index 0).
//
// The Kotlin synthetic default-args constructor ends with
// DefaultConstructorMarker and is excluded.
// ─────────────────────────────────────────────────────────────────────────────
internal object NativePlaybackRequestInitFingerprint : Fingerprint(
    definingClass = "Lit/rai/raiplay/androidtv/nativeplayer/NativePlaybackRequest;",
    name = "<init>",
    returnType = "V",
    custom = { method, _ ->
        val params = method.parameterTypes
        params.size >= 19 &&
            params.take(4).all { it == "Ljava/lang/String;" } &&
            params[4] == "J" &&
            params.last() != "Lkotlin/jvm/internal/DefaultConstructorMarker;"
    },
)
