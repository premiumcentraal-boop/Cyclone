plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
            java.srcDir("${rootDir}/../../third_party/mobilerun-portal/app/src/main/java")
            res.srcDir("${rootDir}/../../third_party/mobilerun-portal/app/src/main/res")
            assets.srcDir("${rootDir}/../../third_party/mobilerun-portal/app/src/main/assets")
        }
        getByName("test") {
            java.srcDir("${rootDir}/../../third_party/mobilerun-portal/app/src/test/java")
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.mockk:mockk:1.13.12")
}
