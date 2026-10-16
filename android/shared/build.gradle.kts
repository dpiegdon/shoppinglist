import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The code the Android and iPhone clients share (docs/ios-port-design.md): the screens, their view
// models, the navigation, the theme, the preference stores, the dependency wiring (Koin) and the
// user-facing strings and drawables (Compose resources in src/commonMain/composeResources). What
// needs the platform is an expect in commonMain with its actual in androidMain and iosMain, or an
// interface the app implements. See README.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

kotlin {
    compilerOptions {
        // PlatformLocale is an expect class whose Android actual is java.util.Locale.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Compiled to klibs on any host with kotlin.native.enableKlibsCrossCompilation (gradle.properties);
    // linking a framework for an app needs a Mac. See ../core/README.md.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            // api: :app reads Res.string.* and resolves them through this library's functions.
            api(libs.compose.components.resources)
            implementation(libs.navigation.compose.multiplatform)
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(libs.lifecycle.runtime.compose.multiplatform)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // The device-local preferences: DataStore is multiplatform, and the platform hands each
            // store its file (the app's appPlatformModule).
            api(libs.androidx.datastore.preferences.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            // The androidx versions the app pins, so the Android build draws exactly what it drew
            // before the screens moved here.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.ui)
            implementation(libs.androidx.material3)
            implementation(libs.androidx.material.icons.core)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
            implementation(libs.koin.test.junit4)
            implementation(libs.robolectric)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.ui.test.junit4)
            implementation(libs.kotlinx.coroutines.test)
            // See app/build.gradle.kts: the conscrypt Robolectric needs on Linux/aarch64.
            implementation(libs.conscrypt.openjdk.uber)
            // Room's bundled SQLite with a self-extracting native library, for the in-memory
            // databases of the screen and view-model tests.
            implementation(libs.androidx.room.runtime)
            implementation(libs.sqlite.bundled.jvm)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.ktor.client.okhttp)
            // The screenshot tests' server answers through Ktor's MockEngine.
            implementation(libs.ktor.client.mock)
            // Screenshot tests (T-325): Roborazzi captures Compose screens under Robolectric's
            // native graphics; see "Screenshot tests" in ../README.md.
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
        }
    }
}

// As in ../core/build.gradle.kts: kotlinx-serialization 1.10+ and androidx.sqlite 2.7 are built
// with Kotlin 2.3, whose klibs this Kotlin (2.2) cannot read. The iOS targets, and the shared
// source sets' metadata, compile against the last releases built with 2.2; Android keeps the
// catalog's versions.
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

android {
    namespace = "org.p23q.shoppinglist.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric runs the tests on the SDK the app targets, as it did when they were in :app.
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // As in app/build.gradle.kts: the tests aren't shipped, and lint's FIR analyzer has crashed on them.
        ignoreTestSources = true
    }
}

dependencies {
    // The Activity createComposeRule() starts, for the screen tests.
    "debugImplementation"(libs.androidx.ui.test.manifest)
}

compose.resources {
    // One fixed, public name for the generated accessors, so code in other modules can import
    // org.p23q.shoppinglist.resources.Res and every Res.string.* / Res.drawable.* beside it.
    publicResClass = true
    packageOfResClass = "org.p23q.shoppinglist.resources"
    generateResClass = always
}

// Robolectric needs JDK 21 to shadow Android SDK 36, as in app/build.gradle.kts; and the
// screenshot tests need its native graphics, which has no Linux/aarch64 build, so on such a host
// they are left out and verifyRoborazziDebug has nothing to compare.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64") {
        filter { excludeTestsMatching("org.p23q.shoppinglist.ui.screenshots.*") }
        doFirst { logger.warn("Linux/aarch64: the screenshot tests are skipped (no native graphics for Robolectric here)") }
    }
}

// Screenshot tests (T-325). The reference PNGs are committed next to the tests; a comparison's
// actual and diff images land in build/outputs/roborazzi. See "Screenshot tests" in ../README.md.
roborazzi {
    outputDir.set(file("src/androidUnitTest/screenshots"))
}
