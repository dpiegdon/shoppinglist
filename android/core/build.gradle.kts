import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app's data layer: API, Room database, repositories and sync. A plain Kotlin/JVM module, so
// the Android SDK is not on its classpath and nothing here can reach it — platform code (Context,
// DataStore, WorkManager, the Keystore, Hilt modules) lives in :app behind interfaces declared here.
// See README.md for what may be imported.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Kotlin Multiplatform artifacts: resolve to their JVM variant here and to the Android one in :app.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // kotlinx.io.IOException, which ApiException extends: java.io.IOException on the JVM.
    api(libs.kotlinx.io.core)
    // A lock that is kotlin.synchronized on the JVM (SyncStatus).
    implementation(libs.atomicfu)
    // The API client. The engine is the platform's (api.HttpEngineFactory), so only the core here.
    api(libs.ktor.client.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The API tests run the real client on the engine :app uses, against MockWebServer.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.ktor.client.okhttp)
    // Room's bundled SQLite driver: real native SQLite on a plain JVM, for database tests here.
    testImplementation(libs.sqlite.bundled.jvm)
}

// Room exports each schema version as JSON so migrations can be tested against a real old database
// rather than trusted (T-162). The directory is committed: a diff there is the reviewable record of
// what a schema change actually did. The migrations themselves, and their test, live in :app.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
