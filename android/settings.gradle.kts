pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions JDKs for Gradle toolchain requests (e.g. the JDK 21 Robolectric needs to
    // shadow Android SDK 36 — see app/build.gradle.kts) so this isn't a per-machine setup step.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "shoppinglist"
include(":app")
