plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val upstreamMobilerun = file("${rootDir}/../../third_party/mobilerun-portal/app/src/main")
val adaptedSources = layout.buildDirectory.dir("generated/mobilerun-src")

fun wrapGeneratedCallback(
    source: String,
    signature: String,
    nextSignature: String,
    stage: String,
    afterStatement: String? = null,
    disableOnFailure: Boolean = false,
): String {
    val methodStart = source.indexOf(signature)
    require(methodStart >= 0) { "Pinned Mobilerun callback not found: $signature" }
    val bodyStart = source.indexOf('{', methodStart) + 1
    val nextStart = source.indexOf(nextSignature, bodyStart)
    require(nextStart > bodyStart) { "Pinned Mobilerun next callback not found: $nextSignature" }
    val methodEnd = source.lastIndexOf("\n    }", nextStart)
    require(methodEnd > bodyStart) { "Pinned Mobilerun callback end not found: $signature" }

    val rawBody = source.substring(bodyStart, methodEnd)
    val statement = afterStatement?.let { rawBody.indexOf(it) } ?: -1
    val prefixEnd = if (statement >= 0) statement + afterStatement!!.length else 0
    val prefix = rawBody.substring(0, prefixEnd)
    val protectedBody = rawBody.substring(prefixEnd)
    val guard = buildString {
        append(prefix)
        append("\n        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.markStage(this, \"")
        append(stage)
        append("\")\n        try {")
        append(protectedBody)
        append("\n        } catch (error: Throwable) {\n")
        append("            com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.recordNonFatal(this, \"")
        append(stage)
        append("\", error)\n")
        if (disableOnFailure) append("            runCatching { disableSelf() }\n")
        append("        }\n")
    }
    return source.substring(0, bodyStart) + guard + source.substring(methodEnd)
}

// Keep the upstream git submodule pristine and reproducible. Cyclone adapts only generated input:
// - Kotlin 2 makes PackageInfo.versionName nullable.
// - Enhanced Control does not request touch-exploration/two-finger-passthrough modes.
// - Enhanced Control subscribes only to the event classes it actually consumes, avoiding a second
//   typeAllMask firehose across the entire phone.
// - Android-owned accessibility callbacks are guarded so an embedded optional subsystem cannot
//   crash the shared Cyclone process or leave Android reporting "service is malfunctioning".
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
                .replace(
                    "eventTypes = AccessibilityEvent.TYPES_ALL_MASK",
                    "eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED",
                )
        }
    }

    doLast {
        val root = adaptedSources.get().asFile

        val applicationFile = root.resolve("com/mobilerun/portal/PortalApplication.kt")
        var applicationSource = applicationFile.readText(Charsets.UTF_8)
        val applicationNeedle = "        super.onCreate()\n"
        require(applicationSource.contains(applicationNeedle)) { "Pinned PortalApplication onCreate changed upstream" }
        applicationSource = applicationSource.replaceFirst(
            applicationNeedle,
            applicationNeedle +
                "        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.install(this)\n" +
                "        com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.markStage(this, \"portal.application.onCreate\")\n",
        )
        applicationFile.writeText(applicationSource, Charsets.UTF_8)

        val serviceFile = root.resolve("com/mobilerun/portal/service/MobilerunAccessibilityService.kt")
        var serviceSource = serviceFile.readText(Charsets.UTF_8)
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onCreate()",
            "    override fun onServiceConnected()",
            "enhanced.accessibility.onCreate",
            "\n        super.onCreate()",
            disableOnFailure = true,
        )
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onServiceConnected()",
            "    override fun onAccessibilityEvent(event: AccessibilityEvent?)",
            "enhanced.accessibility.onServiceConnected",
            "\n        super.onServiceConnected()",
            disableOnFailure = true,
        )
        serviceSource = wrapGeneratedCallback(
            serviceSource,
            "    override fun onAccessibilityEvent(event: AccessibilityEvent?)",
            "    override fun onConfigurationChanged(newConfig: Configuration)",
            "enhanced.accessibility.event",
            disableOnFailure = false,
        )
        serviceFile.writeText(serviceSource, Charsets.UTF_8)
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
