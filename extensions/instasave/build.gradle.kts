// The Morphe settings plugin auto-includes every directory under `extensions/`
// that contains a build.gradle.kts, applies `com.android.application` to it, and
// sets compileSdk 36 / minSdk 23 / Java 17 plus the namespace declared as
// `settings { extensions { defaultNamespace } }` in the root settings.gradle.kts.
//
// The only thing this file has to declare is the resource path that the compiled
// dex is published under, which is the exact string patches pass to `extendWith`.
extension {
    name = "extensions/instasave.mpe"
}

android {
    // Methods in android.jar are stubs that throw. Returning defaults instead lets the pure
    // logic here (the media graph walk, file naming, flag overrides) be unit tested on a plain
    // JVM, with no device and no Robolectric. Nothing under test touches a real Android API.
    testOptions.unitTests.isReturnDefaultValues = true

    // AGP 8 defaults buildConfig off; the updater needs it on to know its own version.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Single source of truth for the InstaSave version: the repo's gradle `version` property,
        // which already names the release artifact (patches-0.1.0.mpp). The updater compares this
        // against the latest GitHub release tag. Bumping gradle.properties is the only edit a
        // release needs. providers.gradleProperty reads the raw property, which every subproject
        // sees, unlike project.version.
        val instaSaveVersion = providers.gradleProperty("version").getOrElse("0.0.0")
        buildConfigField("String", "INSTASAVE_VERSION", "\"$instaSaveVersion\"")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
