plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    // Kyant0 Backdrop 1.0.0 is published with Kotlin 2.2.21 metadata. Align only Kotlin and
    // the Compose compiler plugin; keep Cyclone 4.4.1's AGP line unchanged.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
