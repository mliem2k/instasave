rootProject.name = "instasave"

// Gradle requires pluginManagement before any other configuration block in a settings script.
// It previously sat after dependencyResolutionManagement here, which is the wrong order.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.2-dev.2"
}

settings {
    extensions {
        // The Morphe settings plugin auto includes every directory under `extensions/` that has
        // a build.gradle.kts, applies com.android.application to it, and uses this namespace.
        defaultNamespace = "app.mliem.extension"
    }
}

// The plugin already registers mavenLocal, mavenCentral, google, jitpack and the Morphe registry.
// These two are the extra GitHub Packages feeds the patch libraries are published to.
dependencyResolutionManagement {
    repositories {
        maven {
            name = "InstagramPatchesLibrary"
            url = uri("https://maven.pkg.github.com/brosssh/instagram-morphe-patches-library")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
            }
        }
        maven {
            name = "MorphePatches"
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patches")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
            }
        }
    }
}
