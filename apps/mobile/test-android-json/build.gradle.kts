plugins {
    `java-library`
}

// Android's own org.json (AOSP libcore/json), for the app's unit tests only. See README.md. Never shipped: the phone
// has this code in its framework. A separate module because, in an Android unit-test compile, android.jar's stub
// org.json would shadow these classes if they were test sources.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
