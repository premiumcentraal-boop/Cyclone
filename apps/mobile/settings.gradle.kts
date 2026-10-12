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
// Plan 51 K4: the phone-connector client library (AAR) and a sample connector for testing. Not part of the app.
include(":connector-client")
include(":connector-sample")
