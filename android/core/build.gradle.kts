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
    implementation(libs.javax.inject)
    // Tolerated until the module goes multiplatform: the retrofit2.http annotations on Api and
    // okhttp3.Interceptor in the interceptors and the account sessions (README.md).
    implementation(libs.retrofit)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit.kotlinx.serialization.converter)
    // Room's bundled SQLite driver: real native SQLite on a plain JVM, for database tests here.
    testImplementation(libs.sqlite.bundled.jvm)
}

// Room exports each schema version as JSON so migrations can be tested against a real old database
// rather than trusted (T-162). The directory is committed: a diff there is the reviewable record of
// what a schema change actually did. The migrations themselves, and their test, live in :app.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
