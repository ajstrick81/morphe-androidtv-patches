extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "ajstrick81.morphe.extension"
}

dependencies {
    // okhttp3 is already bundled in Peacock's app at runtime — only needed
    // here to compile PeacockAdPatchHelper's invoke-static call target.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // Guava is already bundled in Prime Video's app at runtime — only needed
    // here to compile SkipAdsPatch's ImmutableMap parameter/return types.
    compileOnly("com.google.guava:guava:33.5.0-jre")

    // Prime Video bundles both the media3 and legacy exoplayer2 SSAI stacks
    // simultaneously — SkipAdsPatch.java hooks both. Both are already in the
    // app at runtime, only needed here to compile against.
    compileOnly("androidx.media3:media3-common:1.4.1")
    compileOnly("com.google.android.exoplayer:exoplayer-core:2.19.1")

    // Pluto — the stitched DASH manifest is media3's own (un-obfuscated in the
    // Pluto build). PlutoDashManifestProbe (skipAdsPatch Hook 5) rewrites the parsed
    // manifest at DashManifestParser.parse's return to strip ad periods — a safe
    // seam that never touches the streaming OkHttp client (wrapping that broke
    // playback). Bundled in the app at runtime; compileOnly here for
    // DashManifest/Period/AdaptationSet types. media3-exoplayer is needed too
    // because DashManifest implements FilterableManifest (declared there).
    compileOnly("androidx.media3:media3-exoplayer-dash:1.4.1")
    compileOnly("androidx.media3:media3-exoplayer:1.4.1")

    // Prime Video has no OkHttp — its app-layer HTTP client is Volley
    // (com.amazon.ignitionshared.network.VolleyModule -> BasicNetwork).
    // Already bundled in the app at runtime, only needed here to compile
    // SkipAdsPatch.enforceAdBlock's Request/NoConnectionError types.
    compileOnly("com.android.volley:volley:1.2.1")
}
