extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "ajstrick81.morphe.extension"
}

// The primevideo/ads *Test.java files under src/test are standalone main()
// assertion runners (verified via `java …Test`, see the native-interception
// handoff), not JUnit tests. Since Gradle 8.13 the unit-test task fails the
// build when it finds test sources but discovers no JUnit tests. They still
// compile here (a build-time check); just don't require JUnit discovery.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
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

    // Prime Video has no OkHttp — its app-layer HTTP client is Volley
    // (com.amazon.ignitionshared.network.VolleyModule -> BasicNetwork).
    // Already bundled in the app at runtime, only needed here to compile
    // SkipAdsPatch.enforceAdBlock's Request/NoConnectionError types.
    compileOnly("com.android.volley:volley:1.2.1")
}
