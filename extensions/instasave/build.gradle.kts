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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
