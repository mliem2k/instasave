group = "app.mliem"

patches {
    about {
        name = "InstaSave Patches"
        description = "Adds a Save/Download option to Instagram posts, reels and stories, and clones the app so it can run alongside the official Instagram."
        source = "git@github.com:mliem2k/instasave.git"
        author = "mliem"
        contact = ""
        website = "https://github.com/mliem2k/instasave"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.morphe.patches.library)
    implementation(libs.instagram.morphe.patches.library)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }

    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
