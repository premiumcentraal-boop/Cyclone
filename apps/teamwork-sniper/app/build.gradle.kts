plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cyclone.teamworksniper"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cyclone.teamworksniper"
        minSdk = 34
        targetSdk = 35
        versionCode = 7
        versionName = "V1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val verifySemanticOnly by tasks.registering {
    group = "verification"
    doLast {
        val prohibited = listOf(
            "screencap",
            "takescreenshot",
            "mediaprojection",
            "textrecognizer",
            "bitmapfactory",
            "pixelcopy",
        )
        val failures = mutableListOf<String>()
        fileTree("src/main") { include("**/*.kt", "**/*.java") }.forEach { sourceFile ->
            val lower = sourceFile.readText().lowercase()
            prohibited.forEach { token ->
                if (lower.contains(token)) failures += sourceFile.path + ": " + token
            }
            if (Regex("\\bocr\\b", RegexOption.IGNORE_CASE).containsMatchIn(sourceFile.readText())) {
                failures += sourceFile.path + ": OCR"
            }
        }
        check(failures.isEmpty()) {
            "Semantic-only guard failed:\n" + failures.joinToString("\n")
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySemanticOnly) }
tasks.named("check").configure { dependsOn(verifySemanticOnly) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
