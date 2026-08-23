plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val upstreamMobilerun = file("${rootDir}/../../third_party/mobilerun-portal/app/src/main")
val adaptedSources = layout.buildDirectory.dir("generated/mobilerun-src")

// Keep the upstream git submodule pristine and reproducible. Cyclone adapts only the build input:
// - Kotlin 2 makes PackageInfo.versionName nullable.
// - Cyclone Enhanced Control does not need touch-exploration/two-finger-passthrough modes. Those
//   modes are accessibility-user interaction features, not requirements for observation, gestures
//   or screenshots, and requesting them can make service startup device/OEM-sensitive.
val prepareMobilerunSources by tasks.registering(Copy::class) {
    from(upstreamMobilerun.resolve("java"))
    into(adaptedSources)
    filteringCharset = "UTF-8"
    filesMatching("**/MobilerunAccessibilityService.kt") {
        filter { line: String ->
            line
                .replace(
                    "packageManager.getPackageInfo(packageName, 0).versionName",
                    "packageManager.getPackageInfo(packageName, 0).versionName ?: \"unknown\"",
                )
                .replace(
                    "AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE",
                    "0",
                )
                .replace(
                    "flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH",
                    "flags = flags",
                )
        }
    }
}

android {
    namespace = "com.mobilerun.portal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField(
            "String",
            "UPDATE_FEED_URL",
            "\"https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest.json\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(adaptedSources)
            res.srcDir(upstreamMobilerun.resolve("res"))
            assets.srcDir(upstreamMobilerun.resolve("assets"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareMobilerunSources)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
