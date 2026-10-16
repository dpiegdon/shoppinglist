import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The code the Android and iPhone clients will share (docs/ios-port-design.md). For now it holds
// the user-facing strings and the drawables, as Compose Multiplatform resources in
// src/commonMain/composeResources; the screens follow. Only an Android target so far: the iOS
// targets come with the screens. See ../README.md, "Strings and drawables".
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            // api: :app reads Res.string.* and resolves them through this library's functions.
            api(libs.compose.components.resources)
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
}

compose.resources {
    // One fixed, public name for the generated accessors, so code in other modules can import
    // org.p23q.shoppinglist.resources.Res and every Res.string.* / Res.drawable.* beside it.
    publicResClass = true
    packageOfResClass = "org.p23q.shoppinglist.resources"
    generateResClass = always
}
