group = "app.morphe"

patches {
    about {
        name = "lennartabeln Patches"
        description = "Ad-blocking patches for Android TV streaming apps — personal fork of " +
            "ajstrick81/morphe-androidtv-patches"
        source = "https://github.com/lennartabeln/morphe-androidtv-patches"
        author = "lennartabeln"
        contact = "https://github.com/lennartabeln"
        website = "https://github.com/lennartabeln/morphe-androidtv-patches"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    implementation(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"
        dependsOn(build)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("ajstrick81.morphe.util.PatchListGeneratorKt")
    }
    publish {
        dependsOn("generatePatchesList")
    }
}
