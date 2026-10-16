import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app's data layer: API, Room database, repositories and sync, as Kotlin Multiplatform. The
// JVM target is what :app builds on; the iOS targets are for an iPhone client. Platform code
// (Context, DataStore, WorkManager, the Keystore, the Koin modules) lives in :app behind interfaces
// declared here. See README.md for what may be imported and what a platform implements.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        // UnicodeText and Room's AppDbConstructor are expect objects.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Compiled to klibs on any host with kotlin.native.enableKlibsCrossCompilation (gradle.properties);
    // linking a framework for an app needs a Mac. See README.md.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // kotlinx.io.IOException, which ApiException extends: java.io.IOException on the JVM.
            api(libs.kotlinx.io.core)
            // A lock that is kotlin.synchronized on the JVM (SyncStatus).
            implementation(libs.atomicfu)
            // The API client. The engine is the platform's (api.HttpEngineFactory), so only the core here.
            api(libs.ktor.client.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            // The API tests run the real client on the engine :app uses, against MockWebServer.
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.ktor.client.okhttp)
            // Room's bundled SQLite driver: real native SQLite on a plain JVM, for database tests here.
            implementation(libs.sqlite.bundled.jvm)
        }
    }
}

// kotlinx-serialization 1.10 and androidx.sqlite 2.7 are built with Kotlin 2.3, and Kotlin 2.2's
// native compiler cannot read their klibs (the JVM reads their classes fine). The iOS targets, and
// the shared source sets' metadata, compile against the last releases built with 2.2 (for sqlite,
// the one Room 2.8.4 asks for); the JVM, and so the Android app, keeps the catalog's versions.
val nonJvmConfiguration = Regex("^(ios|resolvableIos|apple|native|common|metadata|allSourceSets|allTestSourceSets)")
configurations.matching { nonJvmConfiguration.containsMatchIn(it.name) }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization-")) {
            useVersion(libs.versions.kotlinxSerializationJsonIos.get())
        }
        if (requested.group == "androidx.sqlite") {
            useVersion(libs.versions.sqliteIos.get())
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Room generates the database, its DAOs and AppDbConstructor's actual for each target.
dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

// Room exports each schema version as JSON so migrations can be tested against a real old database
// rather than trusted (T-162). The directory is committed: a diff there is the reviewable record of
// what a schema change actually did. The migrations themselves, and their test, live in :app.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
