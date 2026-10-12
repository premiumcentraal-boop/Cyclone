plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * Plan 51 K4: the client library for phone connectors (contract cyclone.connector/1). Connector apps add this AAR,
 * declare their manifest (tools/cyclone-connector-sdk/SPEC.md) and talk to Cyclone through [com.cyclone.connector.client.CycloneConnector].
 * No dependencies besides the Android framework.
 */
android {
    namespace = "com.cyclone.connector.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
