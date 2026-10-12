plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Plan 51 K4: a sample phone connector, for testing connectors on a real phone and as a starting point. It is a test
 * tool, debug-signed, never part of Cyclone. It uses only the client library and the Android framework.
 */
android {
    namespace = "com.cyclone.connector.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cyclone.connector.sample"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
    implementation(project(":connector-client"))
}
