import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing (T-49): credentials live in android/keystore.properties next to the gitignored
// keystore — never committed. When the file is absent (fresh checkout), release builds simply have
// no signing config and fail at signing, while debug builds are unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "org.p23q.shoppinglist"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.p23q.shoppinglist"
        minSdk = 26
        targetSdk = 36
        versionCode = 38
        versionName = "3.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the dev-only "trust self-signed certs" toggle in Settings so it
        // is absent from release builds (the trust-all code itself is excluded at the source-set
        // level — see src/debug vs src/release DevCertTrust.kt).
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // AGP's lint FIR analyzer crashes on ItemsRepoTest.kt (internal bug, not a real finding —
        // see the "RAW_FIR to COMPILER_REQUIRED_ANNOTATIONS" stack trace). Test sources aren't
        // shipped, so skip analyzing them entirely rather than work around the crash in test code.
        ignoreTestSources = true
    }
}

dependencies {
    implementation(project(":core"))
    // The screens, view models, navigation, theme, preference stores, strings and drawables, and
    // the common Koin modules (T-352). MainActivity hosts its TuppuApp.
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)

    // Dependency injection (T-350): appPlatformModule is in di/, the common modules in :shared.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)

    // The @Database, entities and DAOs live in :core, which runs the Room compiler; this module
    // only builds the database (Room.databaseBuilder) and holds its migrations. room-ktx isn't
    // used: androidx.room:room-runtime 2.8+ already includes its Flow/coroutines support natively.
    implementation(libs.androidx.room.runtime)

    // :core's API client runs on OkHttp here (data/api/OkHttpEngineFactory.kt).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.robolectric)
    // Brings androidx.test's ApplicationProvider, which the Robolectric tests use.
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric pulls conscrypt-openjdk-uber 2.5.2 transitively, which predates linux-aarch_64
    // native support; force the newer version that bundles it (Gradle picks the highest by default).
    testImplementation(libs.conscrypt.openjdk.uber)
    // Room's KMP SQLite driver (real native SQLite, not a shadow) — used for DAO/repo tests
    // instead of Robolectric's SQLite shadows, both of which lack Linux/aarch64 native support.
    // The plain "sqlite-bundled" coordinate resolves to the Android-target variant here (this is
    // an Android module), which expects natives pre-extracted into an APK's jniLibs/ and won't
    // self-load under a plain JVM test; "-jvm" bundles + self-extracts its native library instead.
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.okhttp.mockwebserver)
    // HeldCertificate / HandshakeCertificates for the self-signed-cert TLS tests that verify the
    // debug bypass connects and the release no-op still rejects untrusted certs.
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

// Robolectric needs JDK 21 to shadow Android SDK 36 (compileSdk here), even though the rest of
// the build runs on JDK 17. Only the Test tasks get the newer toolchain.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}
