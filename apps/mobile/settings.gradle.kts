pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CycloneMobile"
include(":app")
include(":mobilerun-embedded")
// Android's own org.json, so the app's unit tests see the phone's JSON behaviour (test-android-json/README.md).
include(":test-android-json")
// Plan 51 K4: the phone-connector client library (AAR) and a sample connector for testing. Not part of the app.
include(":connector-client")
include(":connector-sample")
