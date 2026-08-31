import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun materializeKeystore(storeName: String, encodedName: String): File {
    val store = rootProject.file(storeName)
    val encoded = rootProject.file(encodedName)
    if (!store.exists() && encoded.exists()) {
        store.writeBytes(Base64.getDecoder().decode(encoded.readText().trim()))
    }
    return store
}

android {
    namespace = "com.cyclone.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cyclone.mobile"
        minSdk = 34
        targetSdk = 35
        versionCode = 45
        versionName = "3.8.0-beta"
        ndk {
            // Pixel 8 is arm64. Dropping other ABIs shrinks the sideload APK and
            // avoids packaging 4KB-aligned 32-bit WebRTC libs Android 15 rejects.
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        create("ciDebug") {
            storeFile = materializeKeystore("debug.keystore", "debug.keystore.b64")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("ciRelease") {
            storeFile = materializeKeystore("release.keystore", "release.keystore.b64")
            storePassword = "Cyclone36Release!"
            keyAlias = "cyclone"
            keyPassword = "Cyclone36Release!"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ciDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ciRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":mobilerun-embedded"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
