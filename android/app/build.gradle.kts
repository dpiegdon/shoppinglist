plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.p23q.shoppinglist"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.p23q.shoppinglist"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    // room-ktx isn't used: androidx.room:room-runtime 2.8+ already includes its Flow/coroutines
    // support natively.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
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

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

// androidx.hilt:hilt-work's Gradle module metadata requires com.google.dagger:hilt-android:2.59
// (and transitively dagger/hilt-core/dagger-lint-aar at the same version), which Gradle's default
// highest-wins resolution picks over our pinned 2.57.2 — but the Hilt Gradle plugin and
// hilt-android-compiler both stay at 2.57.2 (libs.versions.toml), so the annotation processor
// generates code (e.g. Hilt_MainActivity.java) against a runtime API that no longer matches,
// producing "cannot find symbol: getSavedStateHandleHolder()". Force the whole Dagger/Hilt family
// back to 2.57.2 so compiler, plugin, and runtime all agree; hilt-work's actual code only needs
// long-stable hilt-core APIs, so the downgrade is safe.
configurations.all {
    resolutionStrategy {
        force(
            "com.google.dagger:hilt-android:${libs.versions.hilt.get()}",
            "com.google.dagger:dagger:${libs.versions.hilt.get()}",
            "com.google.dagger:hilt-core:${libs.versions.hilt.get()}",
            "com.google.dagger:dagger-lint-aar:${libs.versions.hilt.get()}",
        )
    }
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
